# Reverse Charades: game contract

Third app in Party TV, id `reverse-charades`. Read `PARTY-CONTRACT.md` first: all its rules apply (never touch the real TV, ES5, no emojis or em dashes in UI, no stuck screens, 960x540 to 1920x1080). Use `apps/accent-wheel/` and `AccentWheelGame.java` as the pattern to follow.

The team acts together while one guesser, with their back to the TV, shouts answers. The prompt being secret from the guesser is the whole game, so these rules come before anything else.

## Change from Raghav: the prompt goes on phones, not the TV

This section overrides anything below that conflicts with it.

A TV is too big and too visible, so by default **the TV never shows the prompt or category during a turn**. It shows the team, guesser, timer, correct count, free skips, scores, and "Actors: check your phones". The summary may reveal prompts, as before.

**Phone roles.** Each phone picks one at the start of the turn, or carries it over from the last one:
- **"I'm acting or refereeing":** sees the prompt in very large text, with its category, and also has Got it, Skip, Foul, Undo and Pause. Actors need it to know what to act, and the referee needs it to judge a guess.
- **"I'm guessing":** never receives the prompt. Shows only the timer and "Shout your guess!", with a warning that switching role reveals the answer. Switching needs confirmation.

Default role is unset, and the phone shows only the two big choices, so a guesser never sees a prompt by accident. The role lives in the phone's memory and `sessionStorage`.

**API.** `state` and the action replies still never contain the prompt. New `GET /api/app/reverse-charades/prompt` returns `{promptId, text, category, hard}` only during `performing`, otherwise an empty reply. Only the acting or refereeing role calls it, polling about every 500 ms, so a correct guess flips the next prompt onto every actor's phone straight away. Secrecy against a determined cheat is on the honour system, which is fine for a family.

**TV display setting.** New action `set_prompt_on_tv` with `on`, default off, and a switch on the phone between turns, labelled "Also show the prompt on TV (only if the guesser's back is turned)". `tv-state` includes `prompt` only when this is on and the phase is `performing`. If no phone is available at all, the TV page offers to turn this on with the remote from the ready screen, because the prompt must appear somewhere.

**Who judges.** Any acting or refereeing phone can tap Got it, and `promptId` stops double scoring. Still suggest a referee from the other team. Dynamics 1 and 9 below now mean: the prompt never reaches the TV by default, nor a guessing phone.

## Dynamics that must hold

1. **The prompt appears only on the TV, and only while the clock is running.** It is never sent to phones. `GET /api/app/reverse-charades/state` must not contain the prompt or its category at any phase while a turn is live. A guesser opening a phone page or calling that URL learns nothing. Only `GET /api/app/reverse-charades/tv-state` includes them, and only the TV page uses it.
2. **Time to sit down first.** Start gives a 3 second countdown with the prompt hidden, so the guesser has their back turned before it appears.
3. **Pause hides the prompt.** During a pause or an argument the guesser may turn around.
4. **Leaving the game pauses it.** If the room goes home mid-turn, the turn is paused and resumes only when someone chooses to. Add `default void onLeave() {}` to `AppBackend` and have ApiServer call it when active moves away from that app. Accent Wheel stays unchanged.
5. **The referee scores, not the actors' team.** The phone page tells whoever holds it to hand it to someone from the other team, who is also the referee for talking. The TV names the referee's team.
6. **Two phones cannot double-score.** `got_it` and `skip` carry the `promptId` from state. If it is not the current prompt, ignore it and return state with no error. Taps after time is up are ignored too, judged by the server clock.
7. **No repeats.** Everyone in the room sees each prompt, so no prompt repeats in a game, across all teams. If the deck runs out, reuse skipped prompts first, then the rest, and show a short note on the TV.
8. **A mistaken tap can be undone.** `undo` reverses the last got_it, skip or foul in the turn, including its points and streak, and brings that prompt back if the current one has not been seen for long. Keep it simple and correct.
9. **Phones show no hints.** Phone pages show the timer, team, scores, streak and free skips left, and never the prompt or the category. Sounds are only neutral: a chime for correct, a soft tone for skip, beeps in the last 5 seconds, a buzzer at the end. Nothing is spoken.
10. **After time is up, showing the prompts is fine.** The summary reveals the list, including the last unguessed prompt, because the turn is over.

## Server: `ReverseCharadesGame` (pure Java, implements AppBackend)

State is on the server, time comes from an injectable clock, thread-safe, with time-based phase changes worked out when state is read.

Phases: `ready`, `countdown` (3 s), `performing` (60 s), `paused`, `summary`.

`GET state` returns:

```json
{"phase":"performing", "promptId":"p12", "secondsLeft":42, "countdownLeft":0,
 "teams":[{"id":"t1","name":"Phuljhadis","score":7,"members":["Raghav"],"guesserIndex":0}],
 "currentTeamId":"t1", "guesser":"Raghav", "refereeTeamName":"Rockets", "round":1,
 "turn":{"correct":3,"skips":1,"freeSkipsLeft":1,"fouls":0,"streak":2,"points":3},
 "bonusAt":0, "deckNote":null, "enabledCategories":["Diwali stuff"], "serverNow":0}
```

`tv-state` adds `"prompt":{"id":"p12","text":"Titanic","category":"Movies","hard":false}` only while `performing`. In `summary`, both state endpoints add `results`, a list of `{text, category, outcome}`, where outcome is `correct`, `skipped` or `unguessed`.

`POST .../action` with `{"action":..., ...}` returns state (phone view) and gives 400 for unknown actions or bad values. A wrong-phase action returns 400 and frontends treat that as harmless.

| action | Fields | Effect |
|---|---|---|
| `start` | | From `ready`: `countdown`, then `performing` with the first prompt |
| `got_it` | `promptId` | +1, or +2 for hard mode. Streak +1. When streak reaches 5, 10, and so on, +3 and set `bonusAt` so the TV plays its fire animation. Next prompt at once |
| `skip` | `promptId` | The first 2 per turn are free, then -1 each. Streak resets to 0. Next prompt at once |
| `foul` | | -1 on the acting team. Streak unchanged. Referee's call |
| `undo` | | See dynamic 8 |
| `pause` / `resume` | | Stops and restarts the clock exactly, keeping seconds left |
| `end_turn` | | From `performing` or `paused`: to `summary` early |
| `next_turn` | | From `summary`: next team and that team's next guesser. `round` rises when every team has played. To `ready` |
| `set_team_name` | `id`, `name` | Max 20 chars, no duplicates |
| `add_team` / `remove_team` | `id` | 2 to 3 teams, only between turns |
| `add_member` / `remove_member` | `teamId`, `name`, or `index` | Optional names, so the TV can name the guesser and rotate fairly |
| `set_categories` | `categories`, comma separated | At least one. Applies from the next turn |
| `add_prompt` | `text` | Max 60 chars, no duplicates. Category `Custom`, goes into the deck |
| `reset_game` | | Scores and deck reset. Keeps teams, members and custom prompts |

Team scores never go below 0. Default teams are Phuljhadis and Rockets, and the third is Anars. With no member names, the TV says "Phuljhadis: pick a guesser".

**Prompt order.** Round 1 uses only Diwali stuff and Desi life, because those get everyone laughing quickly. From round 2 on, all categories, unless the phone has changed the selection.

**Update (Diwali night): movies only.** The deck below is replaced by about 70 famous movies, mostly Bollywood, plus some Hollywood. No categories, no hard mode, no round 1 limit, no `set_categories`. Every correct guess is +1, plus the streak bonus. Custom movies still go in with `add_prompt`. The prompt route and `tv-state` give only the id and text, and the phone and TV show a fixed "Movie" label. Anything below about categories or +2 no longer applies.

**Old deck, no longer used:**
- Movies: Titanic, Sholay, Lagaan, Jurassic Park, Dangal, 3 Idiots, Spider-Man, Kal Ho Naa Ho, Gadar, Jaws
- Diwali stuff: Bursting a rocket, Drawing a rangoli, Lakshmi puja, Getting a gift you hate, Cleaning the house before guests come, Burnt fingers from a phuljhadi, Eating too much mithai
- Actions: Parallel parking, Catching a mosquito, Escaping a wasp, Slipping on a banana, Missing the last metro, Being caught sleeping in class
- Animals and things: Octopus, Washing machine, Elephant giving a bath, Helicopter, Ceiling fan, Traffic signal
- Desi life: Mummy finding your phone at 2 AM, Wedding baraat dance, Bargaining with a sabziwala, Auto driver refusing to go, Power cut during a cricket match, Relatives asking about your marks
- Hard mode (+2): Global warming, Wi-Fi buffering, Monday morning, Inflation, Deja vu, Getting placed

## TV page: `apps/reverse-charades/tv.html`

Uses `tv-state` only, polling every 500 ms, with hub-client and the recovery patterns.

- **ready:** team name, "Guesser: [name]" and "Sit with your back to the TV", the referee's team, scores, how to start.
- **countdown:** huge 3, 2, 1, with no prompt.
- **performing:** the prompt very large and readable from the far end of a room, spaced or wrapped so long prompts fit, timer, team, category, correct count, free skips left, scores. "Got it" and "Skip" hints, and a short rule line: no words, no mouthing, sound effects allowed. A hard-mode prompt is visibly marked +2.
- **paused:** prompt hidden, "Paused".
- **summary:** points for the turn, prompts with their outcome, scores, next team.
- **Bonus:** when `bonusAt` changes, a short fire animation drawn with CSS or SVG, with no images. Must be light on a 1 GB TV.
- **Timer:** red for the last 10 seconds.

**Remote, working with no phone at all:** OK is the next sensible step (ready: start; performing: got it; summary: next team). Right is skip. Down is foul. Up is pause or resume. Left is undo.

## Phone page: `apps/reverse-charades/phone.html`

For the referee. Never shows prompts during a turn (dynamic 9), and tells the holder to hand it to someone from the other team.

- **During a turn:** two huge buttons, Got it and Skip, with their counts, plus smaller Foul, Undo and Pause. Double-tap safe, using `promptId`.
- **Between turns:** teams (rename, add or remove a third, add or remove members), categories, add your own prompt, Next team, Reset game (with confirmation), and a short How to play with the rules.
- Polling must not lose typed text or scroll position. HTML-escape everything. Include hub-client and the Home button with confirmation, like Accent Wheel.

## Later, not tonight

Silent Steal, Statue Mode, One-Arm Round, Relay, Lightning Final.
