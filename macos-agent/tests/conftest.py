import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from macdeck_agent import config, home, icons, server  # noqa: E402

# A small but realistic profiles.json.
PROFILES = {
    "ignore_apps": ["com.google.android.studio"],
    "default": {
        "name": "Desktop",
        "buttons": [
            {"label": "Spotlight", "icon": "🔍", "color": "#5B6770", "action": {"kind": "hotkey", "keys": ["cmd", "space"]}},
            {"label": "Spotify", "icon": "logo:spotify-icon", "color": "#1DB954", "action": {"kind": "open_app", "app": "Spotify"}},
            {"label": "Lock", "icon": "🔒", "color": "#B5543A", "action": {"kind": "hotkey", "keys": ["ctrl", "cmd", "q"]}},
        ],
    },
    "apps": {
        "com.jetbrains.pycharm": {
            "name": "PyCharm", "logo": "pycharm",
            "buttons": [{"label": "Run", "icon": "▶️", "color": "#15803D", "action": {"kind": "hotkey", "keys": ["ctrl", "r"]}}],
        },
        "com.spotify.client": {
            "name": "Spotify", "logo": "spotify-icon",
            "buttons": [{"label": "Play/Pause", "icon": "⏯️", "color": "#1DB954", "action": {"kind": "media", "key": "play_pause"}}],
        },
    },
}

# What's "installed": bundle IDs and names -> app paths.
INSTALLED = {
    "com.spotify.client": "/Applications/Spotify.app",
    "Spotify": "/Applications/Spotify.app",
    "com.jetbrains.pycharm": "/Applications/PyCharm.app",
}


@pytest.fixture
def agent(tmp_path, monkeypatch):
    """An agent pointed at a temporary folder, with fake installed apps and fake images."""
    (tmp_path / "profiles.json").write_text(json.dumps(PROFILES))
    for name in ("PROFILES_PATH", "PINS_PATH", "ORDER_PATH"):
        monkeypatch.setattr(config, name, tmp_path / Path(getattr(config, name)).name)
    home._profiles.update(path=None, mtime=None, data={})
    home._default_paths.update(version=None, paths=set())
    icons.IMAGES.clear()
    icons._refs.clear()
    server.state.update(bundle_id="", app_name="")
    server.clients.clear()
    server._sent_images.clear()

    lookups = []

    def app_path(ref):
        lookups.append(ref)
        return INSTALLED.get(ref)

    monkeypatch.setattr(icons, "app_path", app_path)
    monkeypatch.setattr(icons, "app_display_name", lambda ref: {"com.jetbrains.pycharm": "PyCharm"}.get(ref, ref))
    monkeypatch.setattr(icons, "logo_ref", lambda name: icons.register(f"logo:{name}".encode()))
    monkeypatch.setattr(icons, "app_icon_ref", lambda ref: icons.register(f"app:{ref}".encode()) if ref in INSTALLED else None)
    return type("Agent", (), {"dir": tmp_path, "lookups": lookups})


def labels(buttons):
    return [b["label"] for b in buttons]
