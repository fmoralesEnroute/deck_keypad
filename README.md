# MacDeck

An app-aware keypad: your Android phone shows buttons for whichever app is
in front on your Mac, and tapping a button runs a shortcut, types text,
opens an app or runs a script.

```
macos-agent/   Python agent that runs on your Mac
android-app/   Kotlin + Jetpack Compose app for the phone
```

## 1. Start the agent on your Mac

```bash
cd macos-agent
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python3 agent.py
```

The first time, macOS will block the keystrokes. Open **System Settings →
Privacy & Security → Accessibility**, enable the app you ran the agent from
(Terminal, iTerm, VS Code…), then restart the agent. If macOS asks whether
Python may accept incoming network connections, click Allow.

The agent prints a QR code, plus the IP, port, token and certificate
fingerprint in case you'd rather type them in.

### Or connect over USB

```bash
python3 agent.py --usb
```

Plug the phone in with USB debugging on. The agent tunnels the phone's
`localhost` to your Mac through the cable (`adb reverse`), opens MacDeck and
hands it the pairing; tap **Connect** on the phone. No Wi-Fi or QR code is
involved, and in this mode the agent only listens on `127.0.0.1`, so nothing on
the network can reach it. Unplugging and plugging back in reconnects. The agent
finds `adb` on your `PATH`, in `$ANDROID_HOME`, or in Android Studio's default
SDK folder.

On Wi-Fi, the agent advertises your Wi-Fi/Ethernet address even when a VPN is
connected (a VPN address is unreachable from the phone). Set `MACDECK_HOST` to
override it.

## 2. Run the Android app

Open `android-app/` in Android Studio (File → Open) and let it sync, or build
from the command line with `./gradlew installDebug`.
Run it on your phone, tap **Scan pairing code**, and scan the QR code.

Switch apps on your Mac and watch the keys change.

## 3. Make it yours: `profiles.json`

Profiles are keyed by the app's bundle ID. The agent prints the bundle ID
each time you switch apps (`Active app: Figma (com.figma.Desktop)`), and the
phone shows it when an app has no profile yet. Edits are picked up as soon
as you save; no restart needed.

```json
"com.figma.Desktop": {
  "name": "Figma",
  "buttons": [
    { "label": "Frame", "icon": "#", "color": "#A259FF",
      "action": { "kind": "hotkey", "keys": ["f"] } }
  ]
}
```

Apps without a profile get the `"default"` buttons. While an app's own keys are
showing, the phone's **Home** button switches to the `"default"` keys (and
**‹ App** switches back); bringing another app to the front on the Mac shows
that app's keys again.

### Pinning apps to Home

While an app's keys are showing, **Pin** adds that app to the Home keys (with
its real icon); tapping it there brings the app to the front on your Mac.
**Pinned** unpins it. Pins are stored in `macos-agent/pins.json`. Apps that a default
key already opens (like Spotify) show **On Home** instead, so they can't be
added twice.

### Rearranging Home

On the Home keys, long-press any key (or tap **Edit**), then drag keys where you
want them. Pinned apps show a **✕** to remove them. Tap **Done** when finished.
The order is saved on the Mac in `macos-agent/home_order.json` (by key name, so
editing `profiles.json` doesn't scramble it). With TalkBack, use the key's
**Move earlier** / **Move later** actions instead of dragging.

When the app in front has no keys of its own, the phone shows the Home keys
(defaults plus pinned apps, in your order) and they can be edited there too.

### Logos and app icons

A key's `"icon"` can be an emoji, or:

- `"logo:spotify-icon"`: a brand logo from `macos-agent/logos/`. These come from
  [SVG Logos](https://github.com/gilbarbara/logos) (the set Streamline hosts at
  streamlinehq.com/icons/svg-logos), which is CC0. To add one, download
  `https://cdn.jsdelivr.net/gh/gilbarbara/logos/logos/<name>.svg` into `logos/`.
  Very dark logos (like OpenAI's) are drawn on a white tile so they show up on the keys.
- `"app:com.spotify.client"`: the app's own macOS icon (bundle ID or app name).

An app profile's `"logo"` is used for that app's key when it's pinned to Home.

Apps listed in `"ignore_apps"` never change the keys: the phone keeps showing
the previous app's buttons when one of them comes to the front. It ships with
Android Studio, so clicking a phone mirrored (or emulated) in Android Studio
doesn't swap the keys for the default ones.

### Action kinds

| kind          | fields                         | example |
|---------------|--------------------------------|---------|
| `hotkey`      | `keys`                         | `["cmd","shift","p"]` |
| `text`        | `text`                         | `"git status\n"` |
| `open_app`    | `app`                          | `"Spotify"` |
| `open_url`    | `url`                          | `"https://github.com"` |
| `shell`       | `cmd`                          | `"say hello"` |
| `applescript` | `script`                       | `"tell application \"Music\" to playpause"` |
| `media`       | `key`                          | `"play_pause"`, `"next"`, `"previous"`, `"volume_up"`, `"volume_down"`, `"mute"` |
| `delay`       | `ms`                           | `300` |
| `sequence`    | `steps` (a list of actions)    | see the VS Code "git status" key |

`media` presses the keyboard's media keys, so it controls whatever is playing
and needs no extra permission. `applescript` additionally needs **Automation**
permission (System Settings → Privacy & Security → Automation) for the app you
run the agent from to control the target app.

Modifiers: `cmd`, `shift`, `ctrl`, `alt`/`opt`. Keys: letters, digits,
punctuation, `f1`–`f12`, `up/down/left/right`, `return`, `tab`, `space`,
`escape`, `delete`, `home`, `end`, `pageup`, `pagedown`.

## Troubleshooting

If the phone says **agent.py is out of date**, the agent running on the Mac
doesn't announce a feature this app uses (Home, pins, rearranging or images).
Update `macos-agent/` and restart it.

The agent restarts itself when any of its code changes (if it all compiles),
so updates apply without restarting it by hand. `profiles.json` reloads on save.

**"Can't reach your Mac".** Check the host the phone saved matches your Mac's
Wi-Fi address (the agent prints it), not a VPN address; unpair and scan again,
or use `--usb`.

**A key does nothing.** Look at the agent's terminal: every press prints
`Press: <label> -> <kind>`, and a failure prints `Action failed:` with the
reason.

- *macOS is blocking keystrokes*: turn on your terminal app (Terminal, iTerm…)
  under System Settings → Privacy & Security → **Accessibility**, then quit and
  restart the agent. If it's already on, toggle it off and on. macOS sometimes
  keeps a stale entry after a terminal or Python update.
- *macOS blocked the AppleScript*: allow your terminal to control that app under
  Privacy & Security → **Automation**. If it isn't listed, run
  `tccutil reset AppleEvents com.googlecode.iterm2` (use your terminal's bundle
  ID) and press the key again to get the prompt.

## How it works

```
Mac app comes to front ──▶ agent (NSWorkspace notification)
                            │  looks up profiles.json
                            ▼
                   {"type":"layout", ...}  ──▶  phone redraws the grid
phone tap ──▶ {"type":"press","id":"3","app":"com.microsoft.VSCode"}
                            │
                            ▼
              agent posts the keystroke with CGEvent
```

The phone includes the app it was showing with every press, so a tap made
right as you switch windows never fires a button from the wrong profile.
Connections must start with `{"type":"hello","token":"…"}`; the token lives in
`macos-agent/.token` (delete it to re-pair every phone).

### Security

The connection is TLS (`wss://`). On first run the agent creates a self-signed
certificate (`macos-agent/.cert.pem` / `.key.pem`) and puts its SHA-256
fingerprint in the QR code; the phone trusts that certificate and nothing else.
So someone else on the same Wi-Fi can neither read the token nor impersonate
your Mac. Delete the `.cert.pem`/`.key.pem` files to rotate the certificate
(phones then need to scan again).

Phones paired before TLS was added drop their old pairing and ask you to scan
again. The token and pairing stay on the phone: they're excluded from Android
backups and device-to-device transfer.

## Development

```bash
cd android-app
./gradlew testDebugUnitTest      # unit, integration (TLS WebSocket) and Compose UI tests
./gradlew lintDebug              # security/accessibility lint checks fail the build
./gradlew verifyRoborazziDebug   # compare screenshots with src/test/snapshots
./gradlew recordRoborazziDebug   # re-record them after an intended UI change
./gradlew assembleRelease        # R8-minified release build
```

Agent tests (from `macos-agent/`, in the virtualenv):

```bash
pip install -r requirements-dev.txt
```
```bash
python -m pytest tests
```

`protocol/layout-example.json` is a real layout message from the agent. Both the
agent's tests and the app's `ProtocolContractTest` check it, so neither side can
change the message format without the other noticing. After an intended change,
regenerate it with `UPDATE_PROTOCOL=1 python -m pytest tests/test_protocol.py`.

```
macos-agent/
  agent.py              entry point: arguments, startup, frontmost-app watcher
  macdeck_agent/
    actions.py          hotkeys, text, media keys, apps, AppleScript
    icons.py            logos and app icons, sent to each phone once by hash
    home.py             profiles, Home keys, pins, order, layout messages
    server.py           the WebSocket server
    pairing.py          token, TLS certificate, QR code, Wi-Fi address
    usb.py              --usb: adb tunnel (repaired if adb restarts), pairing
    reload.py           restart when the agent's code changes
    config.py, store.py paths, features; safe JSON writes
  tests/                pytest suite

app/src/main/java/com/example/macdeck/
  Pairing.kt         pairing data, QR parsing and validation
  DeckProtocol.kt    wire format (pure Kotlin, JVM-testable)
  DeckTransport.kt   OkHttp WebSocket as a Flow + certificate pinning
  PairingStore.kt    persistence
  NetworkMonitor.kt  reconnect immediately when the network changes
  DeckViewModel.kt   connection lifecycle and UI state
  DeckScreenModel.kt what the deck screen shows (pure, unit-tested)
  MacDeckApp.kt      root composable, USB pairing dialog
  DeckScreen.kt      header (Home · Pin · Edit · Unpair), notices
  KeyGrid.kt         keys, drag-to-rearrange
  PairScreen.kt      pairing screen
  Theme.kt           colors and theme
```

CI (`.github/workflows/ci.yml`) runs lint, the tests and a release build on
every push and pull request; screenshots are verified on macOS, where the
goldens are recorded.

## Ideas for next steps

- Multiple pages per profile and a "back to default" key
- App icons in the header (NSRunningApplication.icon → PNG → base64)
- A menu-bar app (rumps or a Swift wrapper) instead of a terminal window
- A visual profile editor on the phone that writes back to the agent
- Auto-discovery with Bonjour (zeroconf on the Mac, NsdManager on Android)
