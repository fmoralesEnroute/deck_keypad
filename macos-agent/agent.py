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
    NSEvent,
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
    CGEventSourceCreate,
    kCGEventFlagMaskAlternate,
    kCGEventFlagMaskCommand,
    kCGEventFlagMaskControl,
    kCGEventFlagMaskNumericPad,
    kCGEventFlagMaskSecondaryFn,
    kCGEventFlagMaskShift,
    kCGEventSourceStateHIDSystemState,
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


# Virtual key codes of the modifier keys themselves (left-hand keys).
MODIFIER_KEYCODES = {
    kCGEventFlagMaskCommand: 55,
    kCGEventFlagMaskShift: 56,
    kCGEventFlagMaskAlternate: 58,
    kCGEventFlagMaskControl: 59,
}

# A real keyboard reports these keys with the fn flag (and arrows also with the keypad
# flag). macOS system shortcuts are defined with those flags, e.g. Mission Control is
# ctrl+fn+up, so a synthetic ctrl+up without them never triggers it.
ARROW_KEYS = {"left", "right", "up", "down"}
FN_KEYS = ARROW_KEYS | {"home", "end", "pageup", "pagedown", "forwarddelete"} | {f"f{i}" for i in range(1, 13)}

ACCESSIBILITY_HELP = (
    "macOS is blocking keystrokes from the agent. Open System Settings → Privacy & Security → "
    "Accessibility, turn on the app you run agent.py from (e.g. iTerm), then restart the agent."
)


def accessibility_trusted(prompt=False):
    """Whether macOS lets this process post keyboard events. prompt=True shows the system dialog."""
    try:
        from ApplicationServices import AXIsProcessTrustedWithOptions, kAXTrustedCheckOptionPrompt
    except ImportError:
        return True
    return bool(AXIsProcessTrustedWithOptions({kAXTrustedCheckOptionPrompt: prompt}))


def require_accessibility():
    # Without the permission CGEventPost silently drops events; say so instead.
    if not accessibility_trusted():
        raise PermissionError(ACCESSIBILITY_HELP)


def press_hotkey(keys):
    require_accessibility()
    modifiers, code, key_flags = [], None, 0
    for k in keys:
        k = k.lower()
        if k in MODIFIERS:
            if MODIFIERS[k] not in modifiers:
                modifiers.append(MODIFIERS[k])
        elif k in KEYCODES:
            code = KEYCODES[k]
            if k in FN_KEYS:
                key_flags |= kCGEventFlagMaskSecondaryFn
            if k in ARROW_KEYS:
                key_flags |= kCGEventFlagMaskNumericPad
        else:
            raise ValueError(f"Unknown key: {k!r}")
    if code is None:
        raise ValueError(f"Hotkey needs one non-modifier key: {keys}")

    # Press the modifiers, tap the key, release the modifiers — like a person would.
    # System shortcuts (Spotlight, screenshots, Mission Control) need the real modifier
    # key events, not just flags on the key event.
    source = CGEventSourceCreate(kCGEventSourceStateHIDSystemState)

    def post(keycode, is_down, flags):
        ev = CGEventCreateKeyboardEvent(source, keycode, is_down)
        CGEventSetFlags(ev, flags)
        CGEventPost(kCGHIDEventTap, ev)
        time.sleep(0.01)

    flags = 0
    for m in modifiers:
        flags |= m
        post(MODIFIER_KEYCODES[m], True, flags)
    post(code, True, flags | key_flags)
    post(code, False, flags | key_flags)
    for m in reversed(modifiers):
        flags &= ~m
        post(MODIFIER_KEYCODES[m], False, flags)


# Media keys (NX_KEYTYPE_*): they control whatever is playing (Spotify, Music, a browser tab)
# and only need the Accessibility permission, not per-app Automation.
MEDIA_KEYS = {
    "play_pause": 16, "next": 19, "previous": 20,
    "volume_up": 0, "volume_down": 1, "mute": 7,
}


def press_media_key(key):
    require_accessibility()
    if key not in MEDIA_KEYS:
        raise ValueError(f"Unknown media key: {key!r} (use one of {', '.join(MEDIA_KEYS)})")
    code = MEDIA_KEYS[key]
    for is_down in (True, False):
        state = 0xA if is_down else 0xB
        ev = NSEvent.otherEventWithType_location_modifierFlags_timestamp_windowNumber_context_subtype_data1_data2_(
            14,             # NSEventTypeSystemDefined
            (0, 0),
            state << 8,
            0, 0, None,
            8,              # NX_SUBTYPE_AUX_CONTROL_BUTTONS
            (code << 16) | (state << 8),
            -1,
        )
        CGEventPost(kCGHIDEventTap, ev.CGEvent())
        time.sleep(0.01)


def run_applescript(script):
    """Run AppleScript and surface failures; osascript's errors used to vanish."""
    result = subprocess.run(["osascript", "-e", script], capture_output=True, text=True, timeout=15)
    if result.returncode != 0:
        error = result.stderr.strip()
        if "-1743" in error or "Not authorized" in error:
            raise PermissionError(
                "macOS blocked the AppleScript. Open System Settings → Privacy & Security → "
                "Automation and allow the app you run agent.py from (e.g. iTerm) to control the "
                f"target app. ({error})"
            )
        raise RuntimeError(error or f"osascript exited with {result.returncode}")


def type_text(text):
    require_accessibility()
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
        run_applescript(action["script"])
    elif kind == "media":
        press_media_key(action["key"])
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
    # e.g. Android Studio mirroring the phone: clicking the mirrored keys brings it to the
    # front, which must not swap the keys out from under the click.
    if bundle_id in load_profiles().get("ignore_apps", []):
        print(f"Active app: {app_name} ({bundle_id}) is in ignore_apps; keeping the current keys")
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
    # prompt=True makes macOS show its dialog and add the terminal app to the Accessibility list.
    if not accessibility_trusted(prompt=True):
        print(
            "\n⚠️  Keystrokes will be ignored until you allow this app to control\n"
            "   your Mac: System Settings → Privacy & Security → Accessibility,\n"
            "   then enable the app you ran this from (Terminal, iTerm, VS Code…)\n"
            "   and restart the agent.\n"
        )
    else:
        print("Accessibility permission: OK")


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
