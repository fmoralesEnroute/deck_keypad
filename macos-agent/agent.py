#!/usr/bin/env python3
"""
MacDeck agent — the macOS side of an app-aware Android keypad.

- Watches which app is frontmost (NSWorkspace notifications).
- Pushes the matching button layout from profiles.json to connected phones.
- Receives button presses and performs the action (hotkeys, text, apps, scripts).

Run:  python3 agent.py          (Wi-Fi: prints a QR code to pair)
      python3 agent.py --usb    (USB cable: pairs through adb, nothing on the network)

The code lives in macdeck_agent/: actions, icons, home (profiles, pins, layouts), server,
pairing, usb and reload.
"""
import argparse
import sys
import threading

from AppKit import (
    NSWorkspace,
    NSWorkspaceApplicationKey,
    NSWorkspaceDidActivateApplicationNotification,
)
from Foundation import NSObject
from PyObjCTools import AppHelper

from macdeck_agent import actions, home, pairing, reload, server, usb


class ActivationObserver(NSObject):
    def appActivated_(self, notification):
        app = notification.userInfo()[NSWorkspaceApplicationKey]
        server.app_changed(app.bundleIdentifier(), app.localizedName())


def main():
    parser = argparse.ArgumentParser(description="MacDeck agent")
    parser.add_argument(
        "--usb", action="store_true",
        help="connect phones over the USB cable (adb) instead of Wi-Fi; nothing listens on the network",
    )
    args = parser.parse_args()

    if sys.platform != "darwin":
        sys.exit("This agent only runs on macOS.")
    token = pairing.load_or_create_token()
    cert_fp = pairing.load_or_create_cert()
    home.load_profiles()
    actions.check_accessibility()
    threading.Thread(target=reload.watch_for_code_changes, daemon=True).start()
    if args.usb:
        # Only reachable through adb's tunnel: the phone's localhost -> this Mac's localhost.
        server.start_server_thread("127.0.0.1", token, pairing.tls_context())
        print("\nUSB mode: plug in the phone (USB debugging on). It pairs automatically.")
        print(f"The phone will ask you to confirm a certificate ending in …{cert_fp[-6:]}")
        threading.Thread(target=usb.watch_usb_devices, args=(token, cert_fp), daemon=True).start()
    else:
        server.start_server_thread("0.0.0.0", token, pairing.tls_context())
        pairing.show_pairing(pairing.lan_ip(), token, cert_fp)

    front = NSWorkspace.sharedWorkspace().frontmostApplication()
    if front:
        server.app_changed(front.bundleIdentifier(), front.localizedName())

    observer = ActivationObserver.new()
    NSWorkspace.sharedWorkspace().notificationCenter().addObserver_selector_name_object_(
        observer, "appActivated:", NSWorkspaceDidActivateApplicationNotification, None
    )
    # NSWorkspace only delivers notifications while a run loop is running.
    AppHelper.runConsoleEventLoop(installInterrupt=True)


if __name__ == "__main__":
    main()
