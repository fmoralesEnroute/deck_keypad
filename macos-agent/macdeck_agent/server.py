"""The WebSocket server phones connect to, and what it tells them."""
import asyncio
import json
import secrets
import subprocess
import threading

import websockets

from . import actions, config, home, icons

state = {"bundle_id": "", "app_name": ""}
clients = set()
loop = None  # asyncio loop running in the server thread
_token = None
# Per connection: the image references that phone already has.
_sent_images = {}


def layout_json_for(ws, msg):
    """The layout as this phone should get it: with the bytes of images it hasn't seen yet."""
    have = _sent_images.setdefault(ws, set())
    new = sorted(home.image_refs(msg) - have)
    have.update(new)
    return json.dumps({**msg, "images": {ref: icons.IMAGES[ref] for ref in new}})


async def send_layout(ws):
    await ws.send(layout_json_for(ws, home.layout(state["bundle_id"], state["app_name"])))


async def broadcast_layout():
    if not clients:
        return
    msg = home.layout(state["bundle_id"], state["app_name"])
    await asyncio.gather(*(c.send(layout_json_for(c, msg)) for c in list(clients)), return_exceptions=True)


async def handle_message(msg):
    """One message from an authenticated phone."""
    kind = msg.get("type")
    if kind in ("pin", "unpin"):
        if home.set_pinned(str(msg.get("app", "")), kind == "pin"):
            await broadcast_layout()
        return
    if kind == "reorder" and isinstance(msg.get("ids"), list):
        if home.set_home_order(msg["ids"][:200]):
            await broadcast_layout()
        return
    if kind != "press":
        return
    # A pinned app on the Home keys: bring it to the front.
    pressed = str(msg.get("id", ""))
    if pressed.startswith("pin:"):
        bundle_id = pressed[4:]
        if bundle_id in home.load_pins():
            print(f"Press: open {bundle_id}")
            subprocess.Popen(["open", "-b", bundle_id])
        return
    # Use the app the phone was showing, so a tap during a switch
    # never fires a button from the wrong profile.
    buttons = home.profile_for(msg.get("app", state["bundle_id"])).get("buttons", [])
    try:
        idx = int(pressed)
    except ValueError:
        return
    if 0 <= idx < len(buttons):
        action = buttons[idx].get("action", {})
        print(f"Press: {buttons[idx].get('label')} -> {action.get('kind')}")
        try:
            await asyncio.get_running_loop().run_in_executor(None, actions.run_action, action)
        except Exception as e:
            print(f"Action failed: {e}")


async def handle(ws):
    # First message must be {"type":"hello","token":"..."} within 5 seconds.
    try:
        hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
    except Exception:
        await ws.close(code=4001, reason="hello expected")
        return
    if not isinstance(hello, dict) or hello.get("type") != "hello" or not secrets.compare_digest(
        str(hello.get("token", "")), _token
    ):
        await ws.close(code=4003, reason="bad token")
        return

    clients.add(ws)
    print(f"Phone connected ({len(clients)} total)")
    try:
        await send_layout(ws)
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            if isinstance(msg, dict):
                await handle_message(msg)
    except websockets.ConnectionClosed:
        pass
    finally:
        clients.discard(ws)
        _sent_images.pop(ws, None)
        print(f"Phone disconnected ({len(clients)} total)")


def start_server_thread(host, token, ssl_context):
    global _token
    _token = token

    def run():
        global loop
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        async def main():
            async with websockets.serve(handle, host, config.PORT, ping_interval=15, ssl=ssl_context):
                await asyncio.Future()

        loop.run_until_complete(main())

    threading.Thread(target=run, daemon=True).start()


def app_changed(bundle_id, app_name):
    """The frontmost app changed (called from the main thread)."""
    if bundle_id == state["bundle_id"]:
        return
    # e.g. Android Studio mirroring the phone: clicking the mirrored keys brings it to the
    # front, which must not swap the keys out from under the click.
    if bundle_id in home.load_profiles().get("ignore_apps", []):
        print(f"Active app: {app_name} ({bundle_id}) is in ignore_apps; keeping the current keys")
        return
    state["bundle_id"], state["app_name"] = bundle_id or "", app_name or ""
    print(f"Active app: {app_name} ({bundle_id})")
    if loop:
        asyncio.run_coroutine_threadsafe(broadcast_layout(), loop)
