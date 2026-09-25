"""Performing actions on the Mac: hotkeys, text, media keys, apps, URLs, scripts."""
import subprocess
import time

from AppKit import NSEvent
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
