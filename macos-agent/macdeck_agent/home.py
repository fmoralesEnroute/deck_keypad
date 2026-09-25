"""Profiles, the Home keys (defaults + pinned apps, in the user's order) and layouts.

Everything here is plain data in, plain data out; only icons touch AppKit, so it's easy to test.
"""
import json
from collections import Counter

from . import config, icons, store

# The "app" the phone sends with presses from its Home keys. It is never a real bundle ID,
# so profile_for() resolves it to the "default" profile whatever app is in front.
HOME_APP = "macdeck.home"

_profiles = {"path": None, "mtime": None, "data": {}}
_default_paths = {"version": None, "paths": set()}


# ----------------------------------------------------------------- profiles ---

def load_profiles():
    """profiles.json, reloaded whenever it changes on disk."""
    path = config.PROFILES_PATH
    mtime = path.stat().st_mtime
    if (path, mtime) != (_profiles["path"], _profiles["mtime"]):
        try:
            data = json.loads(path.read_text())
        except json.JSONDecodeError as e:
            print(f"profiles.json has an error, keeping previous version: {e}")
            return _profiles["data"]
        _profiles.update(path=path, mtime=mtime, data=data)
        print("Loaded profiles.json")
        _warn_duplicate_home_keys(data)
    return _profiles["data"]


def profile_for(bundle_id):
    data = load_profiles()
    return data.get("apps", {}).get(bundle_id) or data.get("default", {"buttons": []})


def default_key_name(i, b):
    """How a default key is known in the saved Home order: its "key", else its label."""
    return f"default:{b.get('key') or b.get('label') or i}"


def _warn_duplicate_home_keys(data):
    names = [default_key_name(i, b) for i, b in enumerate(data.get("default", {}).get("buttons", []))]
    for name, count in Counter(names).items():
        if count > 1:
            print(f"profiles.json: {count} default keys are called {name[8:]!r}; give each a unique "
                  f"\"key\" so rearranging Home keeps them apart.")


# --------------------------------------------------------------------- keys ---

def button_json(i, b):
    out = {
        "id": str(i),
        "label": b.get("label", ""),
        "icon": b.get("icon", ""),
        "color": b.get("color", "#5B6770"),
    }
    # "app:<bundle id or name>" shows the app's real icon; "logo:<name>" a logo from logos/.
    if out["icon"].startswith(("app:", "logo:")):
        ref = icons.icon_ref(out["icon"])
        out["icon"] = ""
        if ref:
            out["image"] = ref
    return out


def buttons_json(profile):
    return [button_json(i, b) for i, b in enumerate(profile.get("buttons", []))]


# --------------------------------------------------------------------- pins ---

def load_pins():
    """Bundle IDs pinned to the phone's Home keys, in the order they were pinned."""
    return store.read_json_list(config.PINS_PATH)


def default_app_paths():
    """Apps the "default" keys already open (e.g. Spotify), so pinning them would duplicate.
    Cached per version of profiles.json: resolving apps is a LaunchServices lookup each."""
    load_profiles()
    version = (_profiles["path"], _profiles["mtime"])
    if _default_paths["version"] != version:
        paths = set()
        for b in _profiles["data"].get("default", {}).get("buttons", []):
            action = b.get("action", {})
            if action.get("kind") == "open_app" and action.get("app"):
                path = icons.app_path(action["app"])
                if path:
                    paths.add(path)
        _default_paths.update(version=version, paths=paths)
    return _default_paths["paths"]


def already_on_home(bundle_id):
    path = icons.app_path(bundle_id)
    return bool(path) and path in default_app_paths()


def set_pinned(bundle_id, pinned):
    """Pin/unpin an app. Returns True if anything changed.
    Only apps with their own profile can be pinned (that's when the phone offers it)."""
    if bundle_id not in load_profiles().get("apps", {}) or already_on_home(bundle_id):
        return False
    pins = load_pins()
    if pinned and bundle_id not in pins:
        pins.append(bundle_id)
    elif not pinned and bundle_id in pins:
        pins.remove(bundle_id)
    else:
        return False
    store.write_json_atomic(config.PINS_PATH, pins)
    print(f"{'Pinned' if pinned else 'Unpinned'} {icons.app_display_name(bundle_id)} ({bundle_id})")
    return True


def pinned_buttons_json():
    buttons = []
    for bundle_id in load_pins():
        if already_on_home(bundle_id):
            continue
        profile = load_profiles().get("apps", {}).get(bundle_id, {})
        b = {
            "id": f"pin:{bundle_id}",
            "label": icons.app_display_name(bundle_id),
            "icon": "",
            "color": profile.get("color", "#5B6770"),
            "pinned": bundle_id,
        }
        # The profile's "logo" if it has one, else the app's own icon.
        ref = (profile.get("logo") and icons.logo_ref(profile["logo"])) or icons.app_icon_ref(bundle_id)
        if ref:
            b["image"] = ref
        buttons.append(b)
    return buttons


# -------------------------------------------------------------------- order ---

def home_key(button_id):
    """A stable name for a Home key, used in the saved order. Default keys are named by their
    "key" or label, not their index, so editing profiles.json doesn't scramble the order."""
    if button_id.startswith("pin:"):
        return button_id
    buttons = load_profiles().get("default", {}).get("buttons", [])
    try:
        i = int(button_id)
        return default_key_name(i, buttons[i]) if i >= 0 else None
    except (ValueError, IndexError):
        return None


def set_home_order(button_ids):
    """Save the order the phone sent (button ids). Returns True if it was saved."""
    keys = [k for k in (home_key(str(i)) for i in button_ids) if k]
    if not keys:
        return False
    store.write_json_atomic(config.ORDER_PATH, keys)
    print("Rearranged the Home keys")
    return True


def home_buttons_json():
    """The Home keys: the "default" profile plus pinned apps, in the user's order.
    Keys the saved order doesn't mention (new ones) keep their natural place at the end."""
    buttons = buttons_json(load_profiles().get("default", {})) + pinned_buttons_json()
    rank = {k: n for n, k in enumerate(store.read_json_list(config.ORDER_PATH))}
    return [
        b for _, b in sorted(
            enumerate(buttons),
            key=lambda item: (rank.get(home_key(item[1]["id"]), len(rank)), item[0]),
        )
    ]


# ------------------------------------------------------------------- layout ---

def layout(bundle_id, app_name):
    """The layout message for the app in front (a dict; image bytes are added per phone)."""
    data = load_profiles()
    profile = profile_for(bundle_id)
    has_profile = bundle_id in data.get("apps", {})
    msg = {
        "type": "layout",
        "features": config.FEATURES,
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
    return msg


def image_refs(msg):
    """Every image reference a layout uses."""
    keys = msg.get("buttons", []) + msg.get("home", {}).get("buttons", [])
    return {b["image"] for b in keys if b.get("image")}
