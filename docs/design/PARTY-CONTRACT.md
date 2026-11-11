# Party TV: shared contract

One APK with a home screen that holds several apps. Today: Karaoke and Accent Wheel. Adding a game later must mean one new folder plus one entry in a list. `CONTRACT.md` still describes Karaoke's own API and stays valid.

Target: Xiaomi Mi TV, Android TV 9, 1 GB RAM, WebView maybe as old as Chrome 66. Package stays `com.raghav.karaoke` so the install and removal scripts keep working. The launcher label becomes "Party TV".

## Rules for everyone

- NEVER touch the real TV. Never run `adb connect`, `scripts/deploy-to-tv.ps1`, or `scripts/remove-from-tv.ps1`. Any adb command uses `-s emulator-5554`, and only QA uses adb.
- Only edit files you own. If you need a change elsewhere, say so in your report.
- No commits. No new external services. No files outside this project except scratch work in the session scratchpad.
- UI text and docs: no emojis and no em dashes. Draw icons with inline SVG or plain CSS.
- Web pages: ES5 only (`var`, `function`, `XMLHttpRequest`), no external resources or CDNs, system fonts. Light on a 1 GB TV: no blur, no large shadows. Animating `transform` is fine. Must scale from 960x540 to 1920x1080. TV pages use the same `rem` fit as `apps/karaoke/tv.html`.
- Colours: deep maroon/near-black, marigold and diya gold, as in the karaoke pages.
- Never show a blank screen or get stuck. A TV has no refresh button. Polling loops must survive exceptions, like `apps/karaoke/tv.html` does.

## Layout and ownership

| Path under `core/src/main/resources/web/` | Owner | Contents |
|---|---|---|
| `apps.json` | Backend | The app list |
| `shared/hub-client.js` | Backend | Keeps every page on the active app |
| `hub/tv.html`, `apps/karaoke/tv.html`, `apps/accent-wheel/tv.html` | TV frontend | TV pages |
| `hub/phone.html`, `apps/karaoke/phone.html`, `apps/accent-wheel/phone.html` | Phone frontend | Phone pages |

| Other path | Owner |
|---|---|
| `core/src/main/java/...` and `core/src/test/java/...` | Backend |
| `app/` | TV frontend |

Karaoke's pages have already moved to `apps/karaoke/`. Keep their behaviour exactly, including every recovery feature. Only add what this contract asks for.

## App list: `apps.json`

```json
[
  {"id": "karaoke", "title": "Karaoke", "subtitle": "Pick songs from your phone and sing", "icon": "mic", "backend": true},
  {"id": "accent-wheel", "title": "Accent Wheel", "subtitle": "Spin a voice and a task, then perform", "icon": "wheel", "backend": true}
]
```

Adding a game means adding `apps/<id>/tv.html` and `phone.html` and an entry here. Page serving must need no Java change. A game with server state also registers one `AppBackend`.

## HTTP routes (port 8765), added to ApiServer

| Route | Behaviour |
|---|---|
| `GET /` | `hub/phone.html` |
| `GET /tv` | `hub/tv.html` |
| `GET /tv/<id>` | `apps/<id>/tv.html`, 404 for an unknown id. Validate ids so no path can escape `web/` |
| `GET /phone/<id>` | `apps/<id>/phone.html` |
| `GET /shared/hub-client.js` | The shared script |
| `GET /api/hub` | `{"active": null or id, "apps": [...], "joinUrl": "http://..."}` |
| `POST /api/hub/open` body `{"id":"..."}` | Sets active, 404 for an unknown id |
| `POST /api/hub/home` | Sets active to null |
| `GET or POST /api/app/<id>/<rest>` | Goes to that app's `AppBackend` |

All existing Karaoke routes (`/api/search`, `/api/queue`, `/api/control`, `/api/state`, `/api/lyrics`, `/qr.svg`) stay unchanged.

```java
public interface AppBackend {
  /** Returns the JSON-serialisable reply. body is null for GET. Throw IllegalArgumentException for a bad request (400). */
  Object handle(String method, String path, java.util.Map<String, String> body);
}
// ApiServer: public void registerApp(String id, AppBackend backend);
```

**Music when leaving Karaoke:** when active changes away from `karaoke`, pause if playing and remember that. When it returns to `karaoke`, resume only if the hub paused it. A pause the user made stays paused. No music plays over a game. The queue is kept.

Also expose `public String activeApp()` and `public void goHome()`, `public void openApp(String id)` on ApiServer for the Android shell.

## `shared/hub-client.js`

Each page includes `<script src="/shared/hub-client.js"></script>` and calls `HubClient.start({kind: "tv" | "phone", app: null | "<id>"})`.

It polls `/api/hub` every 1000 ms. If active differs from this page's `app`, it navigates with `location.replace`: to `/tv` or `/` when active is null, otherwise to `/tv/<id>` or `/phone/<id>`. So a choice on any phone or the remote moves the TV and every phone together. It survives failed polls and exceptions. Exposes `HubClient.open(id)`, `HubClient.home()`.

## Remote control

The shell keeps Android key handling simple. On the Karaoke page, OK/Play-Pause toggles pause and Right/Next skips, as now. Everywhere else, keys go to the page through `window.onRemoteKey(name)` if it exists, with name one of `up`, `down`, `left`, `right`, `ok`, called via `evaluateJavascript`. TV pages must also accept keyboard arrows and Enter, so they can be tested in a browser.

Back: on any app, returns home (`goHome`). On the home screen, keep the existing second-press-to-exit behaviour.

The hub TV page: big tiles from `apps.json` with a focus ring, left/right moves, OK opens. Also shows the QR (`/qr.svg`) and "Scan to choose on your phone". The hub phone page: big tiles that open each app.

Karaoke and Accent Wheel phone pages get a small "Home" button that asks for confirmation first, because it moves everyone.

## Accent Wheel server: `AccentWheelGame` (pure Java, implements AppBackend, id `accent-wheel`)

All state lives on the server, so the TV and any phone agree. Time comes through an injectable clock for tests. Thread-safe. Kept in memory for the session and kept while moving between apps.

`GET /api/app/accent-wheel/state` returns:

```json
{"phase":"ready", "spinId":3, "spinMs":4500, "phaseMsLeft":0, "round":2,
 "voice":{"id":"v1","text":"Bollywood villain","group":"Characters"}, "task":{"id":"t1","text":"Propose marriage"},
 "players":[{"id":"p1","name":"Raghav","score":14}], "currentPlayerId":"p1",
 "timerSeconds":45, "secondsLeft":45, "speedRound":false,
 "voices":[...], "tasks":[...], "lastAward":null, "serverNow":1730000000000}
```

Phases: `ready` (waiting to spin), `spinning`, `landed`, `performing`, `scoring`. Phase changes that depend on time are worked out from the clock when state is read, so no timer thread is needed: `spinning` becomes `landed` after `spinMs`, and `performing` becomes `scoring` when the timer runs out.

`POST /api/app/accent-wheel/action` body `{"action":"...", ...string fields}` returns the state. Unknown actions or bad values give 400.

| action | Fields | Effect |
|---|---|---|
| `add_player` | `name` | Adds, trims, max 30 chars, no empty names, no duplicate names, max 30 players. Teams are just players named after the team |
| `remove_player` | `id` | Removes |
| `set_current` | `id` | Chooses who performs |
| `spin` | | From `ready` or `landed`: picks voice and task, increments spinId, goes to `spinning`. In Speed Round it goes on to `performing` by itself after landing |
| `start_timer` | | From `landed`: `performing`, with 45 s, or 15 s in Speed Round |
| `stop_timer` | | From `performing`: `scoring` |
| `score` | `points` 1 to 5, `commitment` true/false, `crack` true/false, `stealerId` optional | From `scoring`. Total = points, +3 commitment, -2 crack. Never below 0. With a stealer, the stealer gets half the points, rounded up, with no bonus, and the performer gets nothing. Sets `lastAward`, increments round, moves current to the next player, goes to `ready` |
| `skip_turn` | | From any phase: back to `ready`, next player, no points |
| `add_slice` | `wheel` voice or task, `text` | Adds a custom slice, max 60 chars, no duplicates |
| `remove_slice` | `wheel`, `id` | Removes, but each wheel keeps at least 2 slices |
| `set_speed` | `on` | Speed Round on or off |
| `reset_scores` | | Scores to 0, round to 1, keeps players and custom slices |

**Spin rules.** Rounds 1 to 3 pick voices only from group `Absurd` (low pressure, so shy people warm up). After that any voice. No repeat on a wheel until every slice has been used. The first `add_slice` entries count as unused.

**Voices** (group, text):
- Characters: Bollywood villain, Daily-soap saas, Motivational guru, Shakespearean actor, Wedding DJ, Horror movie whisper, Royal British butler
- Professions: Railway announcer, Cricket commentator, News anchor (shouting), Airline pilot, Hindi teacher marking answer sheets, Customer care ("Your call is important..."), Sabziwala bargaining
- Absurd: Google Maps / Siri, Nature documentary narrator, Robot running out of battery, Your phone's autocorrect, A sleepy toddler, A ghost who's very polite, Drunk uncle at a wedding

Custom voices get group `Custom`.

**Tasks:** Propose marriage; Explain how a solar cell works; Apologise for bursting a cracker indoors; Order momos on the phone; Break up with your Wi-Fi router; Give a eulogy for the last gulab jamun; Convince Papa to raise your pocket money; Teach Nani to use Instagram; Defend yourself in court for stealing kaju katli; Give a pep talk to a team losing at Ludo; Explain the plot of your favourite movie; Announce the winner of a beauty contest for pets.

## Accent Wheel TV page

Polls state every 500 ms. Layout: title and timer at the top, two wheels side by side with VOICE and TASK labels, the result in large text under each wheel, a hint of the next action, and a scoreboard along the bottom, sorted by score, with the current player highlighted.

Both wheels are SVG with wedges and rotate with a CSS `transform` transition over `spinMs`, landing exactly on the server's choice. A new `spinId` starts a spin. Reloading during a spin must land correctly without replaying. Wheels with many slices only need short labels or none on wedges, because the full text shows below once landed.

Timer turns red in the last 10 seconds. Sounds with Web Audio, no files: ticks during a spin, a chime on landing, beeps in the last 5 seconds, a buzzer at the end. A sound failure must never break the page.

Remote works with no phone at all: OK does the next sensible step (ready: spin; landed: start timer; performing: stop; scoring: choose). Scoring on TV: left/right picks 1 to 5, OK confirms, with no bonuses. If there are no players, score to a built-in "Everyone", and show the QR with "Scan to add players".

## Accent Wheel phone page

For whoever is hosting. Big buttons that follow the phase: Spin, Start, Stop. In scoring: 1 to 5 buttons, Commitment +3 and Crack -2 toggles, "Stolen by" picker, Give points. Sections: players (add, remove, tap to set current, show scores), Speed Round switch, add your own voice or task (the Audience Wheel), remove custom slices, Skip turn, Reset scores (with confirmation). A short "How to play" with the 1-finger to 5-finger hand-vote rule.

Several phones may be open at once. Polling must not lose what someone is typing or reset the page position.
