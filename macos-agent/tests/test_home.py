import json

from conftest import labels
from macdeck_agent import config, home


def test_home_keys_are_defaults_then_pins(agent):
    assert labels(home.home_buttons_json()) == ["Spotlight", "Spotify", "Lock"]
    assert home.set_pinned("com.jetbrains.pycharm", True)
    assert labels(home.home_buttons_json()) == ["Spotlight", "Spotify", "Lock", "PyCharm"]


def test_saved_order_is_by_name_and_survives_profile_edits(agent):
    home.set_pinned("com.jetbrains.pycharm", True)
    assert home.set_home_order(["pin:com.jetbrains.pycharm", "2", "0", "1"])
    assert json.loads(config.ORDER_PATH.read_text()) == [
        "pin:com.jetbrains.pycharm", "default:Lock", "default:Spotlight", "default:Spotify",
    ]
    assert labels(home.home_buttons_json()) == ["PyCharm", "Lock", "Spotlight", "Spotify"]

    # Insert a new default key at the top: indices shift, the saved order still holds,
    # and the new key goes at the end.
    profiles = json.loads(config.PROFILES_PATH.read_text())
    profiles["default"]["buttons"].insert(0, {"label": "Mission Control", "icon": "🪟"})
    config.PROFILES_PATH.write_text(json.dumps(profiles))
    home._profiles.update(mtime=None)
    assert labels(home.home_buttons_json()) == ["PyCharm", "Lock", "Spotlight", "Spotify", "Mission Control"]


def test_ids_keep_pointing_at_the_right_action_after_reordering(agent):
    home.set_home_order(["2", "0", "1"])
    lock = home.home_buttons_json()[0]
    assert lock["label"] == "Lock"
    assert home.profile_for(home.HOME_APP)["buttons"][int(lock["id"])]["label"] == "Lock"


def test_bogus_order_ids_are_ignored(agent):
    assert not home.set_home_order(["99", "-1", "x"])
    assert not config.ORDER_PATH.exists()


def test_explicit_key_names_and_duplicate_warning(agent, capsys):
    profiles = json.loads(config.PROFILES_PATH.read_text())
    profiles["default"]["buttons"] = [
        {"label": "Open", "key": "open-a"}, {"label": "Open", "key": "open-b"}, {"label": "Same"}, {"label": "Same"},
    ]
    config.PROFILES_PATH.write_text(json.dumps(profiles))
    home.load_profiles()
    assert "2 default keys are called 'Same'" in capsys.readouterr().out
    home.set_home_order(["1", "0"])
    assert json.loads(config.ORDER_PATH.read_text()) == ["default:open-b", "default:open-a"]


def test_pinning_rules(agent):
    assert not home.set_pinned("com.unknown.app", True)       # no profile: can't pin
    assert not home.set_pinned("com.spotify.client", True)    # a default key already opens it
    assert home.set_pinned("com.jetbrains.pycharm", True)
    assert not home.set_pinned("com.jetbrains.pycharm", True) # already pinned: no change
    assert home.set_pinned("com.jetbrains.pycharm", False)
    assert home.load_pins() == []


def test_layout_for_an_app_with_its_own_keys(agent):
    msg = home.layout("com.jetbrains.pycharm", "PyCharm")
    assert msg["features"] == config.FEATURES
    assert (msg["appName"], msg["isHome"], msg["pinned"], msg["onHome"]) == ("PyCharm", False, False, False)
    assert labels(msg["buttons"]) == ["Run"]
    assert msg["home"]["app"] == home.HOME_APP
    assert labels(msg["home"]["buttons"]) == ["Spotlight", "Spotify", "Lock"]


def test_layout_for_an_app_already_on_home(agent):
    msg = home.layout("com.spotify.client", "Spotify")
    assert (msg["onHome"], msg["pinned"]) == (True, True)


def test_layout_for_an_app_without_keys_is_the_home(agent):
    home.set_pinned("com.jetbrains.pycharm", True)
    msg = home.layout("com.unknown.app", "Unknown")
    assert msg["isHome"] is True and "home" not in msg
    assert labels(msg["buttons"]) == ["Spotlight", "Spotify", "Lock", "PyCharm"]


def test_images_are_references_not_bytes(agent):
    home.set_pinned("com.jetbrains.pycharm", True)
    msg = home.layout("com.unknown.app", "Unknown")
    refs = home.image_refs(msg)
    assert len(refs) == 2 and all(r.startswith("sha1:") for r in refs)
    assert {b["label"] for b in msg["buttons"] if b.get("image")} == {"Spotify", "PyCharm"}


def test_default_app_lookups_are_cached(agent):
    home.set_pinned("com.jetbrains.pycharm", True)
    home.layout("com.jetbrains.pycharm", "PyCharm")
    before = agent.lookups.count("Spotify")
    for _ in range(5):
        home.layout("com.jetbrains.pycharm", "PyCharm")
    assert agent.lookups.count("Spotify") == before
