# Party TV

<img src="docs/logo/banner.png" alt="Party TV logo" width="480">

Party games for the living-room TV. The TV is the game table, and everyone plays from their own phone by scanning a QR code. Nothing is installed on the phones.

Built for an Android TV (Xiaomi Mi TV, Android 9, 1 GB RAM) and tested on it.

## Games

| Game | How it plays |
|---|---|
| Karaoke | Search songs from your phone. The TV plays them with synced lyrics in English letters (Hindi and Punjabi script are converted), a word-by-word highlight, and an optional vocal-reduction switch |
| Accent Wheel | Two wheels pick a voice and a task. Perform it in 45 seconds, and the room scores you |
| Reverse Charades | The whole team acts out a Bollywood movie while one person guesses. The movie only shows on the actors' phones, never on the TV or the guesser's phone |
| Pictionary | Draw on your phone, and the drawing appears live on the TV while your team guesses |
| Lost in Translation | Guess the Bollywood song from a literal English translation of its lyrics |
| Emoji Movie | Guess the Bollywood movie from emojis |

The guessing games have phone buzzers, a host phone that sees the answer, clues that get easier over time, and steals. Every game can also be run with just the TV remote.

## How it works

Everything runs on the TV. There is no cloud backend.

```
 Phones (browser) --WiFi--> TV: Party TV app
                              |- Web server (NanoHTTPD, port 8765): pages, JSON API, all game state
                              |- Music player (Media3 ExoPlayer) with a live vocal-reduction filter
                              |- Full-screen WebView showing the TV screens
                              '- Android shell: starts everything, routes remote keys
```

- **The server holds all state.** Phones and the TV screen poll it about twice a second, so every screen agrees. Secret answers are simply never sent to the screens that must not see them.
- **`core/`** is plain Java with no Android code: the server, the six games, lyric matching and Hindi-to-Latin conversion. It runs and is tested on a laptop.
- **`app/`** is the thin Android part: player, WebView and remote.
- **Web pages** (`core/src/main/resources/web/`) are ES5 JavaScript with no external files, for old TV browsers.
- **Adding a game** means a folder of pages, one line in `apps.json`, and optionally one Java class.

Design notes for each part are in [`docs/design/`](docs/design/).

## Build

Requires JDK 17 and the Android SDK (set `sdk.dir` in `local.properties`).

```
./gradlew :core:test            # 320+ unit tests
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
./gradlew :core:runHarness      # the real server on the laptop, with a simulated player
```

Install on a TV with `adb install -r -g app-release.apk`, or use `scripts/deploy-to-tv.ps1 -TvIp <your TV IP>`. The TV needs developer debugging switched on.

### Song source

Karaoke searches songs through JioSaavn's public web API, which is unofficial and undocumented, so it can change at any time. It is used here for personal, non-commercial home use only. This project is not affiliated with JioSaavn. Lyrics come from [LRCLIB](https://lrclib.net).

An optional stream key can be placed in `core/src/main/resources/saavn.key` (see `saavn.key.example`). It is not included in this repository. Without it, stream links are resolved through the token endpoint instead.

## Notes

Built with AI coding tools (Claude Code). I chose the games and their rules, directed the build, and tested it on a real TV at a real party.
