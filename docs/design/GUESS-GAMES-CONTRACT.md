# Guess the Song and Guess the Movie: game contract

Fifth and sixth apps in Party TV. Read `PARTY-CONTRACT.md` first, and use `PictionaryGame.java`, `ReverseCharadesGame.java` and their pages as patterns. Both games are the same show with a different deck, so they share one engine: `GuessGame` (pure Java, implements AppBackend), created twice with its own deck and id.

- **`english-song`, "Lost in Translation":** subtitle "Guess the Bollywood song from its English lyrics". The TV shows a deliberately literal English translation of a famous lyric.
- **`emoji-movie`, "Emoji Movie":** subtitle "Guess the Bollywood movie from emojis". The TV shows the movie as emojis.

The UI ban on emojis does not apply to the emoji clues themselves, because they are the game. Everything else follows the normal rule.

## Gameplay rules that must hold

1. **The answer is secret until reveal.** It appears in no TV data and no player phone data. Only the host's phone gets it, via a token from `become_host`, and only `tv` data after reveal. Test this on serialised JSON in every phase.
2. **The host needs no quiz knowledge.** Their phone shows the answer and any accepted alternate names, so they can judge what people shout.
3. **Fair buzzing.** Players pick a team on their phone and get a huge BUZZ button. The server orders buzzes by arrival and the first team is locked in, shown on the TV. Other teams keep their phones ready for a steal.
4. **Clues reveal in steps, and fewer clues score more.** See scoring. The TV always shows every clue revealed so far.
5. **It also works without phones.** With no buzzing, people shout and the host judges, and the remote can drive it: OK reveals the next clue or the answer, and left and right award a team on the result screen. Host phone is recommended, not required, and the TV says so.
6. **No repeats** in a game. Decks are shuffled. Easy questions come first in round 1.
7. **A wrong answer passes on.** Wrong locks that team out for this question, and buzzing reopens for the others.
8. **Two phones cannot double-score,** using `questionId`, and a stale tap is ignored with no error. Taps after time is up are judged by the server clock.

## Flow

Phases: `ready`, `question` (clue 1 and timer), `buzzed`, `reveal`, `final` (shows winner when the host ends the game). 40 s per question. A new clue appears automatically every 12 s, or sooner if the host taps. Time up goes to `reveal`, with no points.

**Clues:**
- Song: clue 1 is the English line; clue 2 is a second English line; clue 3 is movie year plus singer; clue 4 is the first letter of each word in the song title.
- Movie: clue 1 is the emojis; clue 2 is the year; clue 3 is the lead actor; clue 4 is the first letter of each word in the title.

**Points:** correct at clue 1 is 4, clue 2 is 3, clue 3 is 2, clue 4 is 1. A steal after another team was wrong is half, rounded up. **Streak bonus:** 3 correct in a row by one team adds +2, with a short CSS celebration.

## Reveal, the fun part

Reveal shows the title and the funny clue side by side. **Lost in Translation also has "Play the song":** it searches JioSaavn with the title and movie and plays it on the TV, so the room can sing along for a bit, with the original line on screen. Stopped by the host or the next question. This must:
- Use the existing `ApiServer` player only through a small interface, like `play(Song)` and `stop()`, without touching the Karaoke queue or `now`.
- Stop when leaving the game, and never fight with Karaoke's pause and resume rules.
- Fail quietly: no internet just means no song, and the game goes on.
- Hold back while another `GuessGame` or Karaoke is using the player. If this cannot be made safe quickly, leave it out and report that, because the rest matters more.

## API

`GET state` for player and TV data, with no answer before reveal. `POST host-view` with `token` returns the answer and alternates. `POST action` covers `become_host`, `take_over_host` (old token stops working), `start_question`, `next_clue`, `buzz` (`teamId`), `judge` (`correct` true/false, `token`), `reveal`, `next_question`, `set_team_name`, `add_team`, `remove_team`, `set_difficulty` (`easy`, `mixed`), `end_game`, `reset_game`, and `play_song` / `stop_song` for song mode. Unknown or wrong-phase gives 400, and frontends treat wrong-phase as harmless. Team scores never go below 0. Default teams match the other games: Phuljhadis and Rockets, with Anars as an optional third. `onLeave()` pauses a live question.

## Pages

`apps/<id>/tv.html` and `phone.html`, sharing as much as possible through `shared/guess-tv.js` and `shared/guess-phone.js`, with thin per-app pages. ES5, hub-client, Home with confirmation, same style and recovery rules. TV uses the same fit. Polling must not lose typed text or scroll.

- **TV:** the clue is very large and is the star of the screen, readable from across a room. Timer, clue dots, whose buzz, team scores. Emojis extra large.
- **Phone:** three roles on first open: team player (huge BUZZ), host (answer, Correct, Wrong, Next clue, Reveal, Next question, Play the song), or just watching. Role lasts until changed, because the host stays the host. Warn the host not to show their screen.

## Content, which matters most

Write decks in `core/src/main/resources/web/decks/<id>.json`, so more questions can be added later without code. Each entry has `id`, `title`, `alternates`, `year`, `difficulty`, and for songs `movie`, `singer`, `lines` (2), or for movies `emojis` and `lead`.

**At least 60 songs and 60 movies**, mostly well-known across generations: classics, 90s, 2000s, recent. Family friendly: no drinking jokes in clues, nothing suggestive, nothing mocking religion or real groups.

**Lost in Translation:**
- Hook lines people actually know, translated **literally and short (at most 14 words)**, so the funny flatness is the joke. "Tomorrow may or may not come" for Kal Ho Naa Ho.
- Only short excerpts of a line or two per song. No full lyrics.
- Do not put the title's key words in English in the line in a way that gives it away. Clue 2 can be easier.
- Accuracy over quantity: only include a song if you are confident of its actual lyric and credits. Check with web search where you can, and drop anything you cannot confirm rather than guess. A wrong clue spoils a question in front of a whole family.

**Emoji Movie:**
- 3 to 6 emojis that describe the plot or title, and can be solved.
- Only common emojis from Unicode 11 or earlier, because the TV is Android 9. Avoid skin tones, gendered and joined sequences, flags beyond India's, and anything needing Android 10+.
- Avoid clues that could be several movies at once.

**Research first, briefly.** Look at how quiz shows and party apps run song and emoji rounds, for anything worth adding that fits these rules, and report one or two ideas you used. Do not spend long on it.
