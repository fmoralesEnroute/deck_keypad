"""The protocol contract shared with the Android app.

protocol/layout-example.json is a real layout message from this agent. The app's unit tests
parse the same file (ProtocolContractTest), so the two sides can't drift apart silently.
Regenerate after an intended protocol change:  UPDATE_PROTOCOL=1 pytest tests/test_protocol.py
"""
import json
import os
from pathlib import Path

from macdeck_agent import home, server

EXAMPLE = Path(__file__).resolve().parents[2] / "protocol" / "layout-example.json"


def test_layout_example_matches_what_the_agent_sends(agent):
    home.set_pinned("com.jetbrains.pycharm", True)
    home.set_home_order(["pin:com.jetbrains.pycharm", "0", "1", "2"])
    msg = json.loads(server.layout_json_for(object(), home.layout("com.jetbrains.pycharm", "PyCharm")))

    if os.environ.get("UPDATE_PROTOCOL"):
        EXAMPLE.write_text(json.dumps(msg, indent=2, ensure_ascii=False) + "\n")
    assert msg == json.loads(EXAMPLE.read_text()), "protocol changed: see this test's docstring"
