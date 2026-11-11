# Pictionary: game contract

Fourth app in Party TV, id `pictionary`. Read `PARTY-CONTRACT.md` first: all its rules apply. Use `ReverseCharadesGame.java`, `AccentWheelGame.java` and their pages as the pattern.

One person draws on their phone, the drawing appears live on the TV, and their team shouts guesses. This is the reverse of Charades: **the TV must never show the prompt during a turn**, because everyone can see the TV.

## Gameplay rules that must hold

1. **Only the drawer's phone gets the prompt.** A phone taps "I'll draw" and receives a secret `drawerToken`. The prompt is returned only for a request carrying that token, and only while the turn is live. It never appears in `state`, which the TV and other phones read, until the turn is over. Song and game traffic stays on the local network.
2. **The TV shows the drawing live**, within about half a second of the stroke, full screen and easy to see across a room. It must redraw completely after a reload.
3. **Getting ready.** The clock starts only when the drawer taps Start on their own phone, so they can read the prompt first. The prompt is visible to them in a `ready to draw` step for up to 15 s. If they wait longer, the clock starts anyway.
4. **Whoever judges is not the drawer.** Got it is on the TV remote and on every phone except the drawer's. The drawer's phone only draws, so they cannot score their own drawing.
5. **Lost phone.** If the drawer's phone dies, another phone can take over, with confirmation. Taking over gives a **new** prompt and clears the canvas, because the new drawer may already have seen the old drawing.
6. **Double tap safe.** `got_it` carries `promptId`. A stale one is ignored without an error. Taps after time is up are judged by the server clock.
7. **Pause or leaving the game** stops the clock. The drawing stays on screen, because that reveals nothing new, and the drawer cannot add to it while paused. Implement `onLeave()` to pause a live turn.
8. **No repeats** in a game, across all teams. If the deck runs out, reuse skipped prompts first, then the rest.

## Turn flow

Phases: `ready` (waiting for someone to tap "I'll draw"), `claimed` (drawer reads the prompt), `drawing` (60 s), `paused`, `steal` (10 s), `summary`.

- Each turn is one prompt. Own team shouts guesses during `drawing`.
- **Correct in the first 20 s:** 3 points. **Correct after that:** 2 points. Hard mode adds 1.
- **One free skip** per turn: a new prompt and a cleared canvas, with the clock running on. A second skip is refused.
- **Time up:** 10 second `steal`. All other teams may shout. The referee taps which team got it, for 1 point, or No one. The prompt stays hidden until the steal ends.
- **summary:** reveals the prompt and the final drawing, points, scores, and next team. `next_turn` then goes to `ready`. Teams rotate, and `round` rises when every team has played.

## Server: `PictionaryGame` (pure Java, implements AppBackend)

Thread-safe, injectable clock, phase changes worked out from the clock on read, as in the other games.

| Route | Contents |
|---|---|
| `GET state` | Phase, `promptId`, `secondsLeft`, teams and scores, `currentTeamId`, `round`, `drawerName`, `strokeVersion`, skip left. No prompt, except in `summary`. `drawerToken` is never included |
| `GET strokes?since=<n>` | `{"version":n, "clearedAt":m, "strokes":[...]}`, with only what the TV lacks. If the canvas was cleared after `since`, it says so, so the TV starts over |
| `POST drawer` body `{"token":"..."}` | The prompt text and hardness, only for the current drawer token |
| `POST action` | See below |

The AppBackend path may include a query string, or extend the interface in the smallest compatible way if it does not already pass parameters. Coordinate carefully: another agent is editing `ApiServer.java`, so re-read it before editing and make small edits.

**Stroke format.** Points are integers from 0 to 1000, in x then y, on a fixed 16:9 canvas. A stroke has `id`, `color` (one of 6 fixed colours), `size` (1 thin, 2 medium, 3 thick), and `points`. The drawer posts batches, which may extend a stroke already in progress.

**Limits.** At most 800 strokes and 40,000 points per drawing, with further points ignored and the phone told so. Reject bad coordinates. Prompts and strokes are cleared after the summary, except the final drawing until `next_turn`.

| action | Fields | Effect |
|---|---|---|
| `claim` | `name` optional | From `ready`: returns state plus `drawerToken`. Refused if already claimed |
| `start_drawing` | `token` | From `claimed`: to `drawing` |
| `draw` | `token`, `strokes` as a JSON string | Add or extend strokes, only during `drawing` |
| `undo_stroke` | `token` | Removes the drawer's last stroke |
| `clear` | `token` | Clears the canvas |
| `skip` | `token` | The free skip, as above |
| `got_it` | `promptId` | Scores. To `summary` |
| `steal` | `teamId` or `none` | During `steal` only |
| `pause` / `resume` | | Exact clock |
| `take_over` | `name` optional | Rule 5. Old token stops working |
| `next_turn` | | From `summary` |
| `set_team_name`, `add_team`, `remove_team`, `reset_game` | | Same rules as Reverse Charades, default teams the same |
| `add_prompt` | `text` | Max 40 chars, category Custom |

Body fields are strings today. If `strokes` as a JSON string needs more, keep it simple.

## Deck

Round 1 uses only Easy. Round 2 onward uses everything.

- Easy: Diya, Rangoli, Rocket, Phuljhadi, Laddoo, Samosa, Chai, Mango, Kite, Elephant, Peacock, Cricket bat, Auto rickshaw, Taj Mahal, Pressure cooker, Ceiling fan, Umbrella, Sunglasses
- Medium: Gulab jamun, Tiffin box, Mehndi, Wedding baraat, Traffic signal, Sabziwala, Metro train, Titanic, Spider-Man, Jurassic Park, Selfie stick, School uniform, Remote control, Bursting a cracker
- Hard (+1): Power cut, Wi-Fi buffering, Traffic jam, Monday morning, Global warming, Getting scolded by Mummy, Relatives asking about marks, Missing the last metro

## TV page: `apps/pictionary/tv.html`

Uses only `state` and `strokes`, polling state every 500 ms and strokes every 300 ms during drawing. Redraw only what changed, using a `<canvas>` kept in an internal 16:9 size that is light on a 1 GB TV.

- **ready:** team, "Pick someone to draw, and scan to draw on your phone", QR, scores.
- **claimed:** "[Name] is reading the prompt", and no prompt.
- **drawing:** the drawing takes most of the screen on a light paper-coloured canvas so colours read well, with timer, team, scores along the edge. "Your team guesses out loud" and the OK-for-Got-it hint. Timer red for the last 10 seconds.
- **steal:** "Other teams: shout your guess!" with 10 s countdown and the drawing still up.
- **summary:** large prompt reveal, the drawing, points, scores.
- **Sound:** chime, last 5 seconds beeps, buzzer. Web Audio, safe if it fails.

**Remote, no phone needed for judging:** OK does Got it during drawing, and next during summary. Up pauses or resumes. During steal, left and right pick a team or No one, OK confirms. Drawing itself needs a phone, so with none, the page says so plainly.

## Phone page: `apps/pictionary/phone.html`

Has two roles, chosen by the page, so a guest cannot get confused.

**Drawer**
- Prompt shown clearly in a bar that can be hidden with one tap, in case others are looking.
- Large canvas fitted to 16:9 in landscape or portrait, kept at the same ratio as the TV. Colours, 3 sizes, eraser optional, Undo, Clear, Skip (once). Timer visible.
- Touch must draw and must never scroll or zoom the page. Handle touch and mouse. Batch points and send about every 200 ms in order, and keep unsent points on failure so a WiFi hiccup loses nothing. Keep only one request in flight at a time.
- Token stored in memory and `sessionStorage`, so a page refresh keeps the role.
- A big warning: "Don't show this screen to your team."

**Everyone else**
- "I'll draw" when nobody has claimed, big Got it during drawing, steal buttons, Next team, Pause, Take over (with confirmation), teams and rename, add your own prompt, Reset game (with confirmation), and a short How to play.
- Never shows the prompt before the summary.
- Polling must not lose typed text or scroll position. HTML-escape everything. Include hub-client and the Home button with confirmation.

## Later, not tonight

Drawing from the TV remote, everyone-guesses-on-phone mode, and saving drawings.
