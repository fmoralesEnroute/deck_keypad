import asyncio
import json

from macdeck_agent import home, icons, server


class FakeSocket:
    def __init__(self):
        self.sent = []

    async def send(self, text):
        self.sent.append(json.loads(text))


def test_each_image_is_sent_once_per_connection(agent):
    home.set_pinned("com.jetbrains.pycharm", True)
    msg = home.layout("com.unknown.app", "Unknown")
    phone, other = FakeSocket(), FakeSocket()

    first = json.loads(server.layout_json_for(phone, msg))
    assert set(first["images"]) == home.image_refs(msg)
    assert all(first["images"][r] == icons.IMAGES[r] for r in first["images"])
    assert json.loads(server.layout_json_for(phone, msg))["images"] == {}   # already has them
    assert set(json.loads(server.layout_json_for(other, msg))["images"]) == home.image_refs(msg)


def test_pin_and_reorder_messages_update_every_phone(agent):
    phone = FakeSocket()
    server.clients.add(phone)
    server.state.update(bundle_id="com.jetbrains.pycharm", app_name="PyCharm")

    asyncio.run(server.handle_message({"type": "pin", "app": "com.jetbrains.pycharm"}))
    assert phone.sent[-1]["pinned"] is True
    asyncio.run(server.handle_message({"type": "reorder", "ids": ["pin:com.jetbrains.pycharm", "0", "1", "2"]}))
    assert [b["label"] for b in phone.sent[-1]["home"]["buttons"]][0] == "PyCharm"
    sent = len(phone.sent)
    asyncio.run(server.handle_message({"type": "pin", "app": "com.evil.app"}))   # refused: no update
    assert len(phone.sent) == sent


def test_only_pinned_apps_can_be_opened_from_home(agent, monkeypatch):
    opened = []
    monkeypatch.setattr(server.subprocess, "Popen", lambda args: opened.append(args))
    home.set_pinned("com.jetbrains.pycharm", True)
    asyncio.run(server.handle_message({"type": "press", "app": home.HOME_APP, "id": "pin:com.jetbrains.pycharm"}))
    asyncio.run(server.handle_message({"type": "press", "app": home.HOME_APP, "id": "pin:com.evil.app"}))
    assert opened == [["open", "-b", "com.jetbrains.pycharm"]]


def test_presses_run_the_action_from_the_app_the_phone_showed(agent, monkeypatch):
    ran = []
    monkeypatch.setattr(server.actions, "run_action", lambda action: ran.append(action))
    server.state.update(bundle_id="com.spotify.client")   # the Mac has moved on to Spotify…
    asyncio.run(server.handle_message({"type": "press", "app": "com.jetbrains.pycharm", "id": "0"}))
    assert ran == [{"kind": "hotkey", "keys": ["ctrl", "r"]}]   # …but PyCharm's Run still fires
