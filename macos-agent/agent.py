#!/usr/bin/env python3
"""
MacDeck agent — the macOS side of an app-aware Android keypad.

- Watches which app is frontmost (NSWorkspace notifications).
- Pushes the matching button layout from profiles.json to connected phones.
- Receives button presses and performs the action (hotkeys, text, apps, scripts).

Run:  python3 agent.py
"""
import asyncio
import base64
import hashlib
import json
import os
import secrets
import socket
import ssl
import subprocess
import sys
import threading
import time
from pathlib import Path

import websockets
from AppKit import (
    NSWorkspace,
    NSWorkspaceApplicationKey,
    NSWorkspaceDidActivateApplicationNotification,
)
from Foundation import NSObject
from PyObjCTools import AppHelper
from Quartz import (
    CGEventCreateKeyboardEvent,
    CGEventKeyboardSetUnicodeString,
    CGEventPost,
    CGEventSetFlags,
    kCGEventFlagMaskAlternate,
    kCGEventFlagMaskCommand,
    kCGEventFlagMaskControl,
    kCGEventFlagMaskShift,
    kCGHIDEventTap,
)

PORT = int(os.environ.get("MACDECK_PORT", "8765"))
HERE = Path(__file__).resolve().parent
PROFILES_PATH = HERE / "profiles.json"
TOKEN_PATH = HERE / ".token"
CERT_PATH = HERE / ".cert.pem"
KEY_PATH = HERE / ".key.pem"

# ---------------------------------------------------------------- keyboard ---

MODIFIERS = {
    "cmd": kCGEventFlagMaskCommand, "command": kCGEventFlagMaskCommand,
    "shift": kCGEventFlagMaskShift,
    "ctrl": kCGEventFlagMaskControl, "control": kCGEventFlagMaskControl,
    "alt": kCGEventFlagMaskAlternate, "opt": kCGEventFlagMaskAlternate,
    "option": kCGEventFlagMaskAlternate,
}

# macOS virtual key codes (ANSI layout).
KEYCODES = {
    "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8,
    "v": 9, "b": 11, "q": 12, "w": 13, "e": 14, "r": 15, "y": 16, "t": 17,
    "1": 18, "2": 19, "3": 20, "4": 21, "6": 22, "5": 23, "=": 24, "9": 25,
    "7": 26, "-": 27, "8": 28, "0": 29, "]": 30, "o": 31, "u": 32, "[": 33,
    "i": 34, "p": 35, "return": 36, "enter": 36, "l": 37, "j": 38, "'": 39,
    "k": 40, ";": 41, "\\": 42, ",": 43, "/": 44, "n": 45, "m": 46, ".": 47,
    "tab": 48, "space": 49, "`": 50, "backspace": 51, "delete": 51,
    "escape": 53, "esc": 53,
    "f1": 122, "f2": 120, "f3": 99, "f4": 118, "f5": 96, "f6": 97, "f7": 98,
    "f8": 100, "f9": 101, "f10": 109, "f11": 103, "f12": 111,
    "home": 115, "pageup": 116, "forwarddelete": 117, "end": 119,
    "pagedown": 121, "left": 123, "right": 124, "down": 125, "up": 126,
}


def press_hotkey(keys):
    flags, code = 0, None
    for k in keys:
        k = k.lower()
        if k in MODIFIERS:
            flags |= MODIFIERS[k]
        elif k in KEYCODES:
            code = KEYCODES[k]
        else:
            raise ValueError(f"Unknown key: {k!r}")
    if code is None:
        raise ValueError(f"Hotkey needs one non-modifier key: {keys}")
    for is_down in (True, False):
        ev = CGEventCreateKeyboardEvent(None, code, is_down)
        CGEventSetFlags(ev, flags)
        CGEventPost(kCGHIDEventTap, ev)
        time.sleep(0.01)


def type_text(text):
    for ch in text:
        units = len(ch.encode("utf-16-le")) // 2
        for is_down in (True, False):
            ev = CGEventCreateKeyboardEvent(None, 0, is_down)
            CGEventSetFlags(ev, 0)
            CGEventKeyboardSetUnicodeString(ev, units, ch)
            CGEventPost(kCGHIDEventTap, ev)
        time.sleep(0.005)


# ----------------------------------------------------------------- actions ---

def run_action(action):
    kind = action.get("kind")
    if kind == "hotkey":
        press_hotkey(action["keys"])
    elif kind == "text":
        type_text(action["text"])
    elif kind == "open_app":
        subprocess.Popen(["open", "-a", action["app"]])
    elif kind == "open_url":
        subprocess.Popen(["open", action["url"]])
    elif kind == "shell":
        subprocess.Popen(action["cmd"], shell=True)
    elif kind == "applescript":
        subprocess.Popen(["osascript", "-e", action["script"]])
    elif kind == "delay":
        time.sleep(action.get("ms", 100) / 1000)
    elif kind == "sequence":
        for step in action["steps"]:
            run_action(step)
    else:
        raise ValueError(f"Unknown action kind: {kind!r}")


# ---------------------------------------------------------------- profiles ---

_profiles_cache = {"mtime": None, "data": {}}


def load_profiles():
    """Reload profiles.json whenever it changes on disk."""
    mtime = PROFILES_PATH.stat().st_mtime
    if mtime != _profiles_cache["mtime"]:
        try:
            _profiles_cache["data"] = json.loads(PROFILES_PATH.read_text())
            _profiles_cache["mtime"] = mtime
            print("Loaded profiles.json")
        except json.JSONDecodeError as e:
            print(f"profiles.json has an error, keeping previous version: {e}")
    return _profiles_cache["data"]


def profile_for(bundle_id):
    data = load_profiles()
    return data.get("apps", {}).get(bundle_id) or data.get("default", {"buttons": []})


def layout_message(bundle_id, app_name):
    profile = profile_for(bundle_id)
    buttons = [
        {
            "id": str(i),
            "label": b.get("label", ""),
            "icon": b.get("icon", ""),
            "color": b.get("color", "#5B6770"),
        }
        for i, b in enumerate(profile.get("buttons", []))
    ]
    return json.dumps({
        "type": "layout",
        "app": bundle_id,
        "appName": profile.get("name") or app_name,
        "buttons": buttons,
    })


# ------------------------------------------------------------------ server ---

state = {"bundle_id": "", "app_name": ""}
clients = set()
loop = None  # asyncio loop running in the server thread


async def broadcast_layout():
    if not clients:
        return
    msg = layout_message(state["bundle_id"], state["app_name"])
    await asyncio.gather(*(c.send(msg) for c in list(clients)), return_exceptions=True)


async def handle(ws):
    # First message must be {"type":"hello","token":"..."} within 5 seconds.
    try:
        hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
    except Exception:
        await ws.close(code=4001, reason="hello expected")
        return
    if hello.get("type") != "hello" or not secrets.compare_digest(
        str(hello.get("token", "")), TOKEN
    ):
        await ws.close(code=4003, reason="bad token")
        return

    clients.add(ws)
    print(f"Phone connected ({len(clients)} total)")
    try:
        await ws.send(layout_message(state["bundle_id"], state["app_name"]))
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(msg, dict) or msg.get("type") != "press":
                continue
            # Use the app the phone was showing, so a tap during a switch
            # never fires a button from the wrong profile.
            buttons = profile_for(msg.get("app", state["bundle_id"])).get("buttons", [])
            try:
                idx = int(msg.get("id", -1))
            except (TypeError, ValueError):
                continue
            if 0 <= idx < len(buttons):
                action = buttons[idx].get("action", {})
                print(f"Press: {buttons[idx].get('label')} -> {action.get('kind')}")
                try:
                    await asyncio.get_running_loop().run_in_executor(None, run_action, action)
                except Exception as e:
                    print(f"Action failed: {e}")
    except websockets.ConnectionClosed:
        pass
    finally:
        clients.discard(ws)
        print(f"Phone disconnected ({len(clients)} total)")


def start_server_thread():
    def run():
        global loop
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        async def main():
            async with websockets.serve(handle, "0.0.0.0", PORT, ping_interval=15, ssl=tls_context()):
                await asyncio.Future()

        loop.run_until_complete(main())

    threading.Thread(target=run, daemon=True).start()


# ------------------------------------------------------- frontmost watcher ---

def on_app_changed(bundle_id, app_name):
    if bundle_id == state["bundle_id"]:
        return
    state["bundle_id"], state["app_name"] = bundle_id or "", app_name or ""
    print(f"Active app: {app_name} ({bundle_id})")
    if loop:
        asyncio.run_coroutine_threadsafe(broadcast_layout(), loop)


class ActivationObserver(NSObject):
    def appActivated_(self, notification):
        app = notification.userInfo()[NSWorkspaceApplicationKey]
        on_app_changed(app.bundleIdentifier(), app.localizedName())


# ------------------------------------------------------------------- setup ---

def load_or_create_token():
    if TOKEN_PATH.exists():
        return TOKEN_PATH.read_text().strip()
    token = secrets.token_urlsafe(16)
    TOKEN_PATH.write_text(token)
    os.chmod(TOKEN_PATH, 0o600)
    return token


def load_or_create_cert():
    """Self-signed TLS certificate. The phone pins its fingerprint from the QR code,
    so no CA is involved and the LAN IP never needs to match the certificate."""
    if not (CERT_PATH.exists() and KEY_PATH.exists()):
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
             "-subj", "/CN=MacDeck", "-keyout", str(KEY_PATH), "-out", str(CERT_PATH)],
            check=True, capture_output=True,
        )
        os.chmod(KEY_PATH, 0o600)
    der = ssl.PEM_cert_to_DER_cert(CERT_PATH.read_text())
    return base64.urlsafe_b64encode(hashlib.sha256(der).digest()).decode().rstrip("=")


def tls_context():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(CERT_PATH, KEY_PATH)
    return ctx


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def check_accessibility():
    try:
        from ApplicationServices import AXIsProcessTrusted
        if not AXIsProcessTrusted():
            print(
                "\n⚠️  Keystrokes will be ignored until you allow this app to control\n"
                "   your Mac: System Settings → Privacy & Security → Accessibility,\n"
                "   then enable the app you ran this from (Terminal, iTerm, VS Code…)\n"
                "   and restart the agent.\n"
            )
    except ImportError:
        pass


def show_pairing(ip):
    payload = json.dumps({"h": ip, "p": PORT, "t": TOKEN, "f": CERT_FP}, separators=(",", ":"))
    print("\nScan this with the MacDeck app to pair:\n")
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(payload)
        qr.print_ascii(invert=True)
    except ImportError:
        pass
    print(f"\nOr enter manually  →  host: {ip}   port: {PORT}   token: {TOKEN}")
    print(f"                       certificate: {CERT_FP}\n")


TOKEN = load_or_create_token()
CERT_FP = load_or_create_cert()

if __name__ == "__main__":
    if sys.platform != "darwin":
        sys.exit("This agent only runs on macOS.")
    load_profiles()
    check_accessibility()
    start_server_thread()
    show_pairing(lan_ip())

    front = NSWorkspace.sharedWorkspace().frontmostApplication()
    if front:
        on_app_changed(front.bundleIdentifier(), front.localizedName())

    observer = ActivationObserver.new()
    NSWorkspace.sharedWorkspace().notificationCenter().addObserver_selector_name_object_(
        observer, "appActivated:", NSWorkspaceDidActivateApplicationNotification, None
    )
    # NSWorkspace only delivers notifications while a run loop is running.
    AppHelper.runConsoleEventLoop(installInterrupt=True)
