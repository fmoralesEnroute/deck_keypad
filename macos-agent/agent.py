#!/usr/bin/env python3
"""
MacDeck agent — the macOS side of an app-aware Android keypad.

- Watches which app is frontmost (NSWorkspace notifications).
- Pushes the matching button layout from profiles.json to connected phones.
- Receives button presses and performs the action (hotkeys, text, apps, scripts).

Run:  python3 agent.py
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
import py_compile
import secrets
import shlex
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
from pathlib import Path

import websockets
from AppKit import (
    NSBitmapImageFileTypePNG,
    NSBezierPath,
    NSBitmapImageRep,
    NSColor,
    NSColorSpace,
    NSCompositingOperationSourceOver,
    NSDeviceRGBColorSpace,
    NSEvent,
    NSGraphicsContext,
    NSImage,
    NSMakeRect,
    NSWorkspace,
    NSZeroRect,
    NSWorkspaceApplicationKey,
    NSWorkspaceDidActivateApplicationNotification,
)
from Foundation import NSFileManager, NSObject
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
PINS_PATH = HERE / "pins.json"
ORDER_PATH = HERE / "home_order.json"
LOGOS_DIR = HERE / "logos"
APP_ID = "com.example.macdeck"  # the Android app, for USB pairing

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


# The "app" the phone sends with presses from its Home keys. It is never a real bundle ID,
# so profile_for() resolves it to the "default" profile whatever app is in front.
HOME_APP = "macdeck.home"


# -------------------------------------------------------------------- icons ---

_icon_cache = {}


def app_path(ref):
    """Path of an installed app, by bundle ID ("com.spotify.client") or name ("Spotify")."""
    ws = NSWorkspace.sharedWorkspace()
    url = ws.URLForApplicationWithBundleIdentifier_(ref)
    return url.path() if url else ws.fullPathForApplication_(ref)


def app_display_name(ref):
    path = app_path(ref)
    if not path:
        return ref
    return NSFileManager.defaultManager().displayNameAtPath_(path).removesuffix(".app")


ICON_SIZE = 96


def render_png(image, tile=False, size=ICON_SIZE):
    """Draw an NSImage centered in a square (keeping its aspect ratio) and return a bitmap rep.

    tile=True puts it on a white rounded square, for logos too dark to see on the phone's keys.
    """
    rep = NSBitmapImageRep.alloc().initWithBitmapDataPlanes_pixelsWide_pixelsHigh_bitsPerSample_samplesPerPixel_hasAlpha_isPlanar_colorSpaceName_bytesPerRow_bitsPerPixel_(
        None, size, size, 8, 4, True, False, NSDeviceRGBColorSpace, 0, 0
    )
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.setCurrentContext_(NSGraphicsContext.graphicsContextWithBitmapImageRep_(rep))
    inset = 0
    if tile:
        NSColor.whiteColor().setFill()
        NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(
            NSMakeRect(0, 0, size, size), size * 0.22, size * 0.22
        ).fill()
        inset = size * 0.18
    box = size - 2 * inset
    w, h = image.size().width, image.size().height
    scale = box / max(w, h, 1)
    dw, dh = w * scale, h * scale
    image.drawInRect_fromRect_operation_fraction_(
        NSMakeRect((size - dw) / 2, (size - dh) / 2, dw, dh), NSZeroRect, NSCompositingOperationSourceOver, 1.0
    )
    NSGraphicsContext.restoreGraphicsState()
    return rep


def is_dark(rep, size=ICON_SIZE):
    """Whether the visible pixels are mostly near-black (e.g. OpenAI's logo)."""
    total = weight = 0.0
    for y in range(0, size, 4):
        for x in range(0, size, 4):
            c = rep.colorAtX_y_(x, y)
            if c is None or c.alphaComponent() < 0.5:
                continue
            c = c.colorUsingColorSpace_(NSColorSpace.genericRGBColorSpace()) or c
            total += 0.2126 * c.redComponent() + 0.7152 * c.greenComponent() + 0.0722 * c.blueComponent()
            weight += 1
    return weight > 0 and total / weight < 0.25


def png_base64(rep):
    return base64.b64encode(bytes(rep.representationUsingType_properties_(NSBitmapImageFileTypePNG, {}))).decode()


def app_icon_png(ref):
    """The app's real macOS icon as base64 PNG, or None if the app isn't installed."""
    key = ("app", ref)
    if key not in _icon_cache:
        path = app_path(ref)
        _icon_cache[key] = png_base64(render_png(NSWorkspace.sharedWorkspace().iconForFile_(path))) if path else None
    return _icon_cache[key]


def logo_png(name):
    """A brand logo from logos/<name>.svg (CC0, github.com/gilbarbara/logos) as base64 PNG."""
    key = ("logo", name)
    if key not in _icon_cache:
        path = LOGOS_DIR / f"{Path(name).name}.svg"   # no path tricks: logos/ only
        image = NSImage.alloc().initWithContentsOfFile_(str(path)) if path.is_file() else None
        if image is None:
            print(f"Logo not found: {path.name} (see {LOGOS_DIR.name}/)")
            _icon_cache[key] = None
        else:
            rep = render_png(image)
            _icon_cache[key] = png_base64(render_png(image, tile=True) if is_dark(rep) else rep)
    return _icon_cache[key]


def icon_image(icon):
    """Base64 PNG for "app:<bundle id or name>" / "logo:<name>" icons, else None."""
    if icon.startswith("app:"):
        return app_icon_png(icon[4:])
    if icon.startswith("logo:"):
        return logo_png(icon[5:])
    return None


# --------------------------------------------------------------------- pins ---

def load_pins():
    """Bundle IDs pinned to the phone's Home keys, in the order they were pinned."""
    try:
        pins = json.loads(PINS_PATH.read_text())
        return [p for p in pins if isinstance(p, str)]
    except (OSError, ValueError):
        return []


def save_pins(pins):
    PINS_PATH.write_text(json.dumps(pins, indent=2) + "\n")


# ------------------------------------------------------------------ layouts ---

def button_json(i, b):
    out = {
        "id": str(i),
        "label": b.get("label", ""),
        "icon": b.get("icon", ""),
        "color": b.get("color", "#5B6770"),
    }
    # "app:<bundle id or name>" shows the app's real icon; "logo:<name>" a logo from logos/.
    if out["icon"].startswith(("app:", "logo:")):
        image = icon_image(out["icon"])
        out["icon"] = ""
        if image:
            out["image"] = image
    return out


def buttons_json(profile):
    return [button_json(i, b) for i, b in enumerate(profile.get("buttons", []))]


def default_app_paths():
    """Apps the "default" keys already open (e.g. Spotify), so pinning them would duplicate."""
    paths = set()
    for b in load_profiles().get("default", {}).get("buttons", []):
        action = b.get("action", {})
        if action.get("kind") == "open_app" and action.get("app"):
            path = app_path(action["app"])
            if path:
                paths.add(path)
    return paths


def already_on_home(bundle_id):
    path = app_path(bundle_id)
    return bool(path) and path in default_app_paths()


def pinned_buttons_json():
    buttons = []
    for bundle_id in load_pins():
        if already_on_home(bundle_id):
            continue
        profile = load_profiles().get("apps", {}).get(bundle_id, {})
        b = {
            "id": f"pin:{bundle_id}",
            "label": app_display_name(bundle_id),
            "icon": "",
            "color": profile.get("color", "#5B6770"),
            "pinned": bundle_id,
        }
        # The profile's "logo" if it has one, else the app's own icon.
        image = (profile.get("logo") and logo_png(profile["logo"])) or app_icon_png(bundle_id)
        if image:
            b["image"] = image
        buttons.append(b)
    return buttons


def load_order():
    """Home key order the user arranged on the phone, as stable keys (see home_key)."""
    try:
        order = json.loads(ORDER_PATH.read_text())
        return [k for k in order if isinstance(k, str)]
    except (OSError, ValueError):
        return []


def home_key(button_id):
    """A stable name for a Home key. Default keys are named by label, not index, so the saved
    order survives editing the "default" list in profiles.json."""
    if button_id.startswith("pin:"):
        return button_id
    buttons = load_profiles().get("default", {}).get("buttons", [])
    try:
        b = buttons[int(button_id)]
    except (ValueError, IndexError):
        return None
    return f"default:{b.get('label') or button_id}"


def home_buttons_json():
    """The Home keys: the "default" profile plus pinned apps, in the user's order.
    Keys the saved order doesn't mention (new ones) keep their natural place at the end."""
    buttons = buttons_json(load_profiles().get("default", {})) + pinned_buttons_json()
    rank = {k: n for n, k in enumerate(load_order())}
    return [
        b for _, b in sorted(
            enumerate(buttons),
            key=lambda item: (rank.get(home_key(item[1]["id"]), len(rank)), item[0]),
        )
    ]


def layout_message(bundle_id, app_name):
    data = load_profiles()
    profile = profile_for(bundle_id)
    has_profile = bundle_id in data.get("apps", {})
    msg = {
        "type": "layout",
        "app": bundle_id,
        "appName": profile.get("name") or app_name,
        # An app without its own keys gets the Home keys themselves (defaults + pins).
        "buttons": buttons_json(profile) if has_profile else home_buttons_json(),
        "isHome": not has_profile,
    }
    # When an app has its own keys, also send the Home keys so the phone can switch to them
    # instantly, and say whether this app is pinned.
    if has_profile:
        default = data.get("default", {})
        msg["home"] = {
            "app": HOME_APP,
            "appName": default.get("name") or "Home",
            "buttons": home_buttons_json(),
        }
        # "onHome": a default key already opens this app, so the phone shows "On Home" instead of Pin.
        msg["onHome"] = already_on_home(bundle_id)
        msg["pinned"] = msg["onHome"] or bundle_id in load_pins()
    return json.dumps(msg)


# ------------------------------------------------------------------ server ---

state = {"bundle_id": "", "app_name": ""}
clients = set()
loop = None  # asyncio loop running in the server thread


async def broadcast_layout():
    if not clients:
        return
    msg = layout_message(state["bundle_id"], state["app_name"])
    await asyncio.gather(*(c.send(msg) for c in list(clients)), return_exceptions=True)


async def set_pinned(bundle_id, pinned):
    # Only apps with their own profile can be pinned (that's when the phone offers it).
    if bundle_id not in load_profiles().get("apps", {}) or already_on_home(bundle_id):
        return
    pins = load_pins()
    if pinned and bundle_id not in pins:
        pins.append(bundle_id)
    elif not pinned and bundle_id in pins:
        pins.remove(bundle_id)
    else:
        return
    save_pins(pins)
    print(f"{'Pinned' if pinned else 'Unpinned'} {app_display_name(bundle_id)} ({bundle_id})")
    await broadcast_layout()


async def set_home_order(button_ids):
    keys = [k for k in (home_key(str(i)) for i in button_ids) if k]
    if not keys:
        return
    ORDER_PATH.write_text(json.dumps(keys, indent=2) + "\n")
    print("Rearranged the Home keys")
    await broadcast_layout()


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
            if not isinstance(msg, dict):
                continue
            kind = msg.get("type")
            if kind in ("pin", "unpin"):
                await set_pinned(str(msg.get("app", "")), kind == "pin")
                continue
            if kind == "reorder" and isinstance(msg.get("ids"), list):
                await set_home_order(msg["ids"][:200])
                continue
            if kind != "press":
                continue
            # A pinned app on the Home keys: bring it to the front.
            pressed = str(msg.get("id", ""))
            if pressed.startswith("pin:"):
                bundle_id = pressed[4:]
                if bundle_id in load_pins():
                    print(f"Press: open {bundle_id}")
                    subprocess.Popen(["open", "-b", bundle_id])
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


def start_server_thread(host):
    def run():
        global loop
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        async def main():
            async with websockets.serve(handle, host, PORT, ping_interval=15, ssl=tls_context()):
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


def interface_ip(iface):
    result = subprocess.run(["ipconfig", "getifaddr", iface], capture_output=True, text=True)
    return result.stdout.strip() or None


def lan_ip():
    """The Mac's Wi-Fi/Ethernet address, which is what a phone on the same network can reach.

    A VPN (utun*) can own the default route or the 10.x routes; its address is useless to
    the phone, so prefer the interface of the default route only if it's en*, then any en*.
    """
    if os.environ.get("MACDECK_HOST"):
        return os.environ["MACDECK_HOST"]
    route = subprocess.run(["route", "-n", "get", "default"], capture_output=True, text=True).stdout
    default_iface = next((l.split(":")[1].strip() for l in route.splitlines() if "interface:" in l), "")
    candidates = ([default_iface] if default_iface.startswith("en") else []) + [f"en{i}" for i in range(8)]
    for iface in candidates:
        ip = interface_ip(iface)
        if ip:
            return ip
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


# ------------------------------------------------------------- self-reload ---

def watch_for_code_changes():
    """Restart when agent.py changes, so an update never runs half-applied.

    The restart only happens if the new file compiles; otherwise the running version stays.
    Phones reconnect by themselves within a second or two.
    """
    source = Path(__file__).resolve()
    mtime = source.stat().st_mtime
    while True:
        time.sleep(2)
        try:
            current = source.stat().st_mtime
        except OSError:
            continue
        if current == mtime:
            continue
        mtime = current
        try:
            py_compile.compile(str(source), doraise=True)
        except py_compile.PyCompileError as e:
            print(f"agent.py changed but doesn't compile; keeping the running version.\n{e.msg}")
            continue
        print("agent.py changed; restarting to apply it…", flush=True)
        os.execv(sys.executable, [sys.executable, str(source), *sys.argv[1:]])


# ---------------------------------------------------------------------- usb ---

def find_adb():
    for candidate in (
        shutil.which("adb"),
        os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"),
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
    ):
        if candidate and os.path.isfile(candidate):
            return candidate
    return None


def usb_pairing_payload():
    return json.dumps({"h": "127.0.0.1", "p": PORT, "t": TOKEN, "f": CERT_FP}, separators=(",", ":"))


def connect_usb_device(adb, serial):
    """Tunnel the phone's localhost:PORT to this Mac and hand the app the pairing."""
    reverse = subprocess.run(
        [adb, "-s", serial, "reverse", f"tcp:{PORT}", f"tcp:{PORT}"], capture_output=True, text=True
    )
    if reverse.returncode != 0:
        print(f"USB: couldn't set up {serial}: {reverse.stderr.strip()}")
        return False
    # The app asks the user to confirm before it accepts a pairing sent this way.
    start = subprocess.run(
        [adb, "-s", serial, "shell", "am", "start", "-n", f"{APP_ID}/.MainActivity",
         "-a", f"{APP_ID}.PAIR", "--es", "pairing", shlex.quote(usb_pairing_payload())],
        capture_output=True, text=True,
    )
    if start.returncode != 0 or "Error" in start.stdout + start.stderr:
        print(f"USB: {serial} is tunnelled, but the MacDeck app didn't open. Is it installed?")
    else:
        print(f"USB: {serial} connected. Confirm \"Connect over USB\" on the phone.")
    return True


def watch_usb_devices():
    adb = find_adb()
    if not adb:
        print("USB: adb not found. Install Android platform-tools or set ANDROID_HOME.")
        return
    connected, warned = set(), set()
    while True:
        try:
            out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=10).stdout
        except (OSError, subprocess.TimeoutExpired):
            time.sleep(2)
            continue
        states = dict(line.split()[:2] for line in out.splitlines()[1:] if len(line.split()) >= 2)
        for serial, device_state in states.items():
            if device_state == "unauthorized" and serial not in warned:
                print(f"USB: {serial} is waiting for you to allow USB debugging on the phone.")
                warned.add(serial)
            if device_state == "device" and serial not in connected and connect_usb_device(adb, serial):
                connected.add(serial)
        # Forget unplugged phones so plugging them back in sets them up again.
        connected &= {s for s, st in states.items() if st == "device"}
        time.sleep(2)


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
    parser = argparse.ArgumentParser(description="MacDeck agent")
    parser.add_argument(
        "--usb", action="store_true",
        help="connect phones over the USB cable (adb) instead of Wi-Fi; nothing listens on the network",
    )
    args = parser.parse_args()

    if sys.platform != "darwin":
        sys.exit("This agent only runs on macOS.")
    load_profiles()
    check_accessibility()
    threading.Thread(target=watch_for_code_changes, daemon=True).start()
    if args.usb:
        # Only reachable through adb's tunnel: the phone's localhost -> this Mac's localhost.
        start_server_thread("127.0.0.1")
        print("\nUSB mode: plug in the phone (USB debugging on). It pairs automatically.")
        threading.Thread(target=watch_usb_devices, daemon=True).start()
    else:
        start_server_thread("0.0.0.0")
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
