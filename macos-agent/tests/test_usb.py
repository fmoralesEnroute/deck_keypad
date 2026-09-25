import subprocess

from macdeck_agent import config, usb


class FakeAdb:
    """Plays adb: `devices`, `reverse`, `reverse --list`, `run-as … cat`, `am start`."""

    def __init__(self, devices, paired_prefs=""):
        self.devices = devices
        self.tunnels = set()
        self.paired_prefs = paired_prefs
        self.calls = []

    def run(self, args, **kwargs):
        args = args[1:]
        self.calls.append(args)
        out = ""
        if args == ["devices"]:
            out = "List of devices attached\n" + "".join(f"{s}\t{st}\n" for s, st in self.devices.items())
        elif args[2:4] == ["reverse", "--list"]:
            out = "".join(f"{s} tcp:{config.PORT} tcp:{config.PORT}\n" for s in self.tunnels if s == args[1])
        elif args[2] == "reverse":
            self.tunnels.add(args[1])
        elif args[2] == "shell" and args[3].startswith("run-as"):
            out = self.paired_prefs
        return subprocess.CompletedProcess(args, 0, out, "")

    def started_app(self):
        return [c for c in self.calls if "am" in c and "start" in c]


PAIRED = (f'<string name="host">127.0.0.1</string><int name="port" value="{config.PORT}" />'
          '<string name="token">tok</string><string name="cert_sha256">fp</string>')


def run_pass(monkeypatch, adb, connected, warned=None):
    monkeypatch.setattr(usb.subprocess, "run", adb.run)
    usb.check_devices("adb", "tok", "fp", connected, warned if warned is not None else set())


def test_a_new_phone_gets_a_tunnel_and_the_pairing(monkeypatch):
    adb, connected = FakeAdb({"PHONE": "device"}), set()
    run_pass(monkeypatch, adb, connected)
    assert connected == {"PHONE"} and "PHONE" in adb.tunnels
    assert len(adb.started_app()) == 1


def test_an_already_paired_phone_isnt_popped_open(monkeypatch):
    adb, connected = FakeAdb({"PHONE": "device"}, paired_prefs=PAIRED), set()
    run_pass(monkeypatch, adb, connected)
    assert connected == {"PHONE"} and adb.started_app() == []


def test_a_tunnel_dropped_by_an_adb_restart_is_restored_quietly(monkeypatch):
    adb, connected = FakeAdb({"PHONE": "device"}), set()
    run_pass(monkeypatch, adb, connected)
    adb.tunnels.clear()                       # adb server restarted
    run_pass(monkeypatch, adb, connected)
    assert "PHONE" in adb.tunnels
    assert len(adb.started_app()) == 1        # the tunnel came back without re-opening the app


def test_unplugged_phones_are_forgotten_and_unauthorized_ones_warned_once(monkeypatch, capsys):
    adb, connected, warned = FakeAdb({"PHONE": "device"}), set(), set()
    run_pass(monkeypatch, adb, connected, warned)
    adb.devices = {"OTHER": "unauthorized"}
    run_pass(monkeypatch, adb, connected, warned)
    run_pass(monkeypatch, adb, connected, warned)
    assert connected == set()
    assert capsys.readouterr().out.count("waiting for you to allow USB debugging") == 1
