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

Apps without a profile get the `"default"` buttons.

### Action kinds

| kind          | fields                         | example |
|---------------|--------------------------------|---------|
| `hotkey`      | `keys`                         | `["cmd","shift","p"]` |
| `text`        | `text`                         | `"git status\n"` |
| `open_app`    | `app`                          | `"Spotify"` |
| `open_url`    | `url`                          | `"https://github.com"` |
| `shell`       | `cmd`                          | `"say hello"` |
| `applescript` | `script`                       | `"tell application \"Spotify\" to playpause"` |
| `delay`       | `ms`                           | `300` |
| `sequence`    | `steps` (a list of actions)    | see the VS Code "git status" key |

Modifiers: `cmd`, `shift`, `ctrl`, `alt`/`opt`. Keys: letters, digits,
punctuation, `f1`–`f12`, `up/down/left/right`, `return`, `tab`, `space`,
`escape`, `delete`, `home`, `end`, `pageup`, `pagedown`.

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

```
app/src/main/java/com/example/macdeck/
  Pairing.kt         pairing data, QR parsing and validation
  DeckProtocol.kt    wire format (pure Kotlin, JVM-testable)
  DeckTransport.kt   OkHttp WebSocket as a Flow + certificate pinning
  PairingStore.kt    persistence
  NetworkMonitor.kt  reconnect immediately when the network changes
  DeckViewModel.kt   connection lifecycle and UI state
  DeckUi.kt          Compose UI
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
