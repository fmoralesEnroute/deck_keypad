"""--usb mode: reach phones through adb's tunnel instead of the network."""
import os
import re
import shlex
import shutil
import subprocess
import time

from . import config, pairing


def find_adb():
    for candidate in (
        shutil.which("adb"),
        os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"),
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
    ):
        if candidate and os.path.isfile(candidate):
            return candidate
    return None


def _adb(adb, serial, *args, timeout=10):
    return subprocess.run([adb, "-s", serial, *args], capture_output=True, text=True, timeout=timeout)


def tunnel_alive(adb, serial):
    """Whether the phone's localhost:PORT still reaches this Mac. adb drops the tunnel whenever
    its server restarts (Android Studio does this), while the phone stays listed as connected."""
    rule = f"tcp:{config.PORT} tcp:{config.PORT}"
    return rule in _adb(adb, serial, "reverse", "--list").stdout


def ensure_tunnel(adb, serial):
    result = _adb(adb, serial, "reverse", f"tcp:{config.PORT}", f"tcp:{config.PORT}")
    if result.returncode != 0:
        print(f"USB: couldn't set up {serial}: {result.stderr.strip()}")
        return False
    return True


def phone_has_pairing(adb, serial, token, cert_fp):
    """Whether the app on the phone is already paired with this agent over USB.
    Readable on debug builds only (run-as); for anything else this says no."""
    prefs = _adb(adb, serial, "shell", f"run-as {config.APP_ID} cat shared_prefs/macdeck.xml").stdout
    saved = dict(re.findall(r'<string name="(\w+)">([^<]*)</string>', prefs))
    port = re.search(r'<int name="port" value="(\d+)"', prefs)
    return (
        saved.get("host") == "127.0.0.1"
        and port is not None and int(port.group(1)) == config.PORT
        and saved.get("token") == token
        and saved.get("cert_sha256") == cert_fp
    )


def connect_usb_device(adb, serial, token, cert_fp):
    """Tunnel the phone's localhost:PORT to this Mac and, if needed, hand the app the pairing."""
    if not ensure_tunnel(adb, serial):
        return False
    if phone_has_pairing(adb, serial, token, cert_fp):
        print(f"USB: {serial} connected.")
        return True
    # The app asks the user to confirm before it accepts a pairing sent this way.
    start = _adb(
        adb, serial, "shell", "am", "start", "-n", f"{config.APP_ID}/.MainActivity",
        "-a", f"{config.APP_ID}.PAIR", "--es", "pairing",
        shlex.quote(pairing.payload("127.0.0.1", token, cert_fp)),
    )
    if start.returncode != 0 or "Error" in start.stdout + start.stderr:
        print(f"USB: {serial} is tunnelled, but the MacDeck app didn't open. Is it installed?")
    else:
        print(f"USB: {serial} connected. Confirm \"Connect over USB\" on the phone.")
    return True


def check_devices(adb, token, cert_fp, connected, warned):
    """One pass: set up newly plugged-in phones and repair tunnels adb dropped."""
    out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=10).stdout
    states = dict(line.split()[:2] for line in out.splitlines()[1:] if len(line.split()) >= 2)
    for serial, device_state in states.items():
        if device_state == "unauthorized" and serial not in warned:
            print(f"USB: {serial} is waiting for you to allow USB debugging on the phone.")
            warned.add(serial)
        if device_state != "device":
            continue
        if serial not in connected:
            if connect_usb_device(adb, serial, token, cert_fp):
                connected.add(serial)
        elif not tunnel_alive(adb, serial) and ensure_tunnel(adb, serial):
            print(f"USB: restored the tunnel to {serial} (adb had restarted).")
    # Forget unplugged phones so plugging them back in sets them up again.
    connected &= {s for s, st in states.items() if st == "device"}


def watch_usb_devices(token, cert_fp):
    adb = find_adb()
    if not adb:
        print("USB: adb not found. Install Android platform-tools or set ANDROID_HOME.")
        return
    connected, warned = set(), set()
    while True:
        try:
            check_devices(adb, token, cert_fp, connected, warned)
        except (OSError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
