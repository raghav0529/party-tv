package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

public class GuessGameTest {
    private static final String TITLE = "Zebra Crossing Song";
    private final AtomicLong clock = new AtomicLong(1000000);
    private final Gson gson = new Gson();
    private GuessGame game;
    private final List<String> songCalls = new ArrayList<String>();
    private GuessGame.SongListener lastListener;

    private final GuessGame.SongHost fakeHost = new GuessGame.SongHost() {
        @Override
        public void play(String owner, String query, GuessGame.SongListener listener) {
            songCalls.add("play " + owner + " " + query);
            lastListener = listener;
        }

        @Override
        public void stop(String owner) {
            songCalls.add("stop " + owner);
        }
    };

    private static GuessGame.Question q(String id, String title, String difficulty) {
        GuessGame.Question x = new GuessGame.Question();
        x.id = id;
        x.title = title;
        x.difficulty = difficulty;
        x.year = 1999;
        x.alternates.add(title + " Alternate");
        x.movie = title + " Movie";
        x.singer = "Some Singer";
        x.lines.add("first plain line");
        x.lines.add("second plain line");
        x.original = title + " original";
        x.emojis = "🔥🐎🚂";
        x.lead = "Some Actor";
        return x;
    }

    private static List<GuessGame.Question> deck(int count) {
        List<GuessGame.Question> out = new ArrayList<GuessGame.Question>();
        for (int i = 0; i < count; i++) out.add(q("secret-id-" + i, TITLE + " " + i, i < count / 2 ? "easy" : "hard"));
        return out;
    }

    @Before
    public void setUp() {
        game = new GuessGame(GuessGame.ENGLISH_SONG, deck(20), clock::get, new Random(7), fakeHost);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> act(String action, String... kv) {
        Map<String, String> body = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) body.put(kv[i], kv[i + 1]);
        body.put("action", action);
        return (Map<String, Object>) game.handle("POST", "action", body);
    }

    private void rejected(String action, String... kv) {
        try {
            act(action, kv);
            fail(action + " should have been refused");
        } catch (IllegalArgumentException expected) {
            // 400 at the HTTP layer.
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> state() {
        return (Map<String, Object>) game.handle("GET", "state", null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hostView(String token) {
        Map<String, String> body = new HashMap<String, String>();
        body.put("token", token);
        return (Map<String, Object>) game.handle("POST", "host-view", body);
    }

    private String host() {
        return (String) act("become_host").get("hostToken");
    }

    @SuppressWarnings("unchecked")
    private int score(String teamId) {
        for (Map<String, Object> t : (List<Map<String, Object>>) state().get("teams")) {
            if (teamId.equals(t.get("id"))) return ((Number) t.get("score")).intValue();
        }
        throw new AssertionError(teamId);
    }

    private String qid() {
        return (String) state().get("questionId");
    }

    private void assertSecret(Map<String, Object> reply, String phase) {
        String json = gson.toJson(reply).toLowerCase(Locale.ROOT);
        assertFalse(phase + " leaked the title: " + json, json.contains("zebra"));
        assertFalse(phase + " leaked the deck id: " + json, json.contains("secret-id"));
        assertFalse(phase + " leaked the movie or alternate: " + json, json.contains("alternate") || json.contains(" movie"));
        assertFalse(phase + " leaked the original line", json.contains("original"));
        assertFalse(phase + " should not have a reveal", json.contains("\"reveal\""));
    }

    // ----- Secrecy -----

    @Test
    public void answerStaysSecretInEveryPhaseBeforeReveal() {
        String token = host();
        assertFalse(gson.toJson(state()).contains(token));
        assertSecret(state(), "ready");
        assertSecret(act("start_question"), "question");
        assertSecret(act("next_clue"), "clue 2");
        assertSecret(act("next_clue"), "clue 3");
        // Clue 4 is first letters only: Z C S 0 style, never the words.
        assertSecret(act("next_clue"), "clue 4");
        assertSecret(act("buzz", "teamId", "t1"), "buzzed");
        assertSecret(act("judge", "token", token, "correct", "false"), "after wrong");
        assertSecret(state(), "steal");
        game.onLeave();
        assertSecret(state(), "paused");
    }

    @Test
    public void endingBeforeRevealNeverShowsTheAnswer() {
        act("start_question");
        assertSecret(act("end_game"), "final");
        assertEquals("final", state().get("phase"));
    }

    @Test
    public void emojiModeIsSecretToo() {
        game = new GuessGame(GuessGame.EMOJI_MOVIE, deck(4), clock::get, new Random(1), null);
        assertSecret(act("start_question"), "emoji");
        assertEquals(false, state().get("canPlaySong"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void revealCarriesTheAnswer() {
        act("start_question");
        Map<String, Object> s = act("reveal");
        assertEquals("reveal", s.get("phase"));
        Map<String, Object> r = (Map<String, Object>) s.get("reveal");
        assertTrue(((String) r.get("answer")).startsWith(TITLE));
        assertNotNull(r.get("original"));
    }

    @Test
    public void onlyTheHostTokenGetsTheAnswer() {
        String token = host();
        act("start_question");
        assertTrue(gson.toJson(hostView(token)).contains("Zebra"));
        assertTrue(gson.toJson(hostView(token)).contains("Alternate"));
        for (String bad : new String[] {"nope", ""}) {
            try {
                hostView(bad);
                fail();
            } catch (IllegalArgumentException expected) {
                // Refused.
            }
        }
        try {
            game.handle("POST", "host-view", new HashMap<String, String>());
            fail();
        } catch (IllegalArgumentException expected) {
            // No token.
        }
    }

    @Test
    public void takeOverKillsTheOldToken() {
        String old = host();
        rejected("become_host");
        String fresh = (String) act("take_over_host").get("hostToken");
        assertNotEquals(old, fresh);
        act("start_question");
        act("buzz", "teamId", "t1");
        rejected("judge", "token", old, "correct", "true");
        act("judge", "token", fresh, "correct", "true");
        assertEquals(4, score("t1"));
    }

    @Test
    public void judgeNeedsAToken() {
        host();
        act("start_question");
        act("buzz", "teamId", "t1");
        rejected("judge", "correct", "true");
        assertEquals(0, score("t1"));
    }

    // ----- Buzzing -----

    @Test
    public void firstBuzzWinsAndLaterOnesAreOrdered() {
        act("start_question");
        Map<String, Object> first = act("buzz", "teamId", "t2");
        assertEquals("buzzed", first.get("phase"));
        assertEquals("t2", first.get("buzzTeamId"));
        Map<String, Object> second = act("buzz", "teamId", "t1");
        assertEquals(true, second.get("ignored"));
        assertEquals("t2", second.get("buzzTeamId"));
        assertEquals(java.util.Arrays.asList("Hearts", "Spades"), second.get("buzzOrder"));
    }

    @Test
    public void wrongLocksOutAndReopensForOthers() {
        String token = host();
        act("start_question");
        act("buzz", "teamId", "t1");
        Map<String, Object> s = act("judge", "token", token, "correct", "false");
        assertEquals("question", s.get("phase"));
        assertEquals(true, act("buzz", "teamId", "t1").get("ignored"));
        assertEquals("question", state().get("phase"));
        assertEquals("buzzed", act("buzz", "teamId", "t2").get("phase"));
    }

    @Test
    public void everyTeamWrongGoesToReveal() {
        String token = host();
        act("start_question");
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "false");
        act("buzz", "teamId", "t2");
        assertEquals("reveal", act("judge", "token", token, "correct", "false").get("phase"));
        assertEquals(0, score("t1"));
        assertEquals(0, score("t2"));
    }

    @Test
    public void aBuzzStopsTheClockAndCluesWhileJudging() {
        host();
        act("start_question");
        clock.addAndGet(5000);
        act("buzz", "teamId", "t1");
        clock.addAndGet(120000);
        Map<String, Object> s = state();
        assertEquals("buzzed", s.get("phase"));
        assertEquals(1, s.get("clue"));
        assertEquals(35000L, s.get("msLeft"));
    }

    @Test
    public void wrongNearTheEndLeavesTimeToSteal() {
        String token = host();
        act("start_question");
        clock.addAndGet(39000);
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "false");
        assertEquals(GuessGame.MIN_STEAL_MS, state().get("msLeft"));
    }

    // ----- Scoring -----

    @Test
    public void fewerCluesScoreMore() {
        String token = host();
        int[] expected = {4, 3, 2, 1};
        int total = 0;
        for (int clues = 0; clues < 4; clues++) {
            if (clues == 0) act("start_question");
            else act("next_question");
            for (int i = 0; i < clues; i++) act("next_clue");
            act("buzz", "teamId", "t1");
            act("judge", "token", token, "correct", "true");
            total += expected[clues];
            // Three in a row brings the streak bonus on the third.
            if (clues == 2) total += GuessGame.STREAK_BONUS;
            assertEquals(total, score("t1"));
        }
    }

    @Test
    public void stealIsHalfRoundedUp() {
        String token = host();
        int[][] cases = {{0, 2}, {1, 2}, {2, 1}, {3, 1}};
        for (int i = 0; i < cases.length; i++) {
            if (i == 0) act("start_question");
            else act("next_question");
            for (int c = 0; c < cases[i][0]; c++) act("next_clue");
            int before = score("t2");
            act("buzz", "teamId", "t1");
            act("judge", "token", token, "correct", "false");
            act("buzz", "teamId", "t2");
            act("judge", "token", token, "correct", "true");
            int gained = score("t2") - before;
            // t2's third in a row carries the bonus.
            assertEquals(cases[i][1] + (i == 2 ? GuessGame.STREAK_BONUS : 0), gained);
        }
    }

    @Test
    public void streakNeedsThreeInARowByOneTeam() {
        String token = host();
        act("start_question");
        for (int i = 0; i < 2; i++) {
            act("buzz", "teamId", "t1");
            act("judge", "token", token, "correct", "true");
            act("next_question");
        }
        assertEquals(8, score("t1"));
        assertEquals(0, ((Number) state().get("bonusSeq")).intValue());
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "true");
        assertEquals(14, score("t1"));
        assertEquals(1, ((Number) state().get("bonusSeq")).intValue());
    }

    @Test
    public void anotherTeamOrAWrongAnswerBreaksTheStreak() {
        String token = host();
        act("start_question");
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "true");
        act("next_question");
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "true");
        act("next_question");
        act("buzz", "teamId", "t2");
        act("judge", "token", token, "correct", "true");
        act("next_question");
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "true");
        assertEquals(12, score("t1"));
        act("next_question");
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "true");
        act("next_question");
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "false");
        act("reveal");
        act("next_question");
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "true");
        assertEquals(20, score("t1"));
        assertEquals(0, ((Number) state().get("bonusSeq")).intValue());
    }

    // ----- Stale taps -----

    @Test
    public void doubleJudgeCannotDoubleScore() {
        String token = host();
        act("start_question");
        String id = qid();
        act("buzz", "teamId", "t1");
        String seq = String.valueOf(state().get("buzzSeq"));
        act("judge", "token", token, "correct", "true", "questionId", id, "buzzSeq", seq);
        Map<String, Object> again = act("judge", "token", token, "correct", "true", "questionId", id, "buzzSeq", seq);
        assertEquals(true, again.get("ignored"));
        assertEquals(4, score("t1"));
    }

    @Test
    public void lateWrongTapCannotHitTheNextBuzzer() {
        String token = host();
        act("start_question");
        String id = qid();
        act("buzz", "teamId", "t1");
        String seq = String.valueOf(state().get("buzzSeq"));
        act("judge", "token", token, "correct", "false", "questionId", id, "buzzSeq", seq);
        act("buzz", "teamId", "t2");
        Map<String, Object> late = act("judge", "token", token, "correct", "false", "questionId", id, "buzzSeq", seq);
        assertEquals(true, late.get("ignored"));
        assertEquals("buzzed", late.get("phase"));
    }

    @Test
    public void staleQuestionIdIsIgnoredWithoutError() {
        String token = host();
        act("start_question");
        String old = qid();
        act("reveal");
        act("next_question");
        assertEquals(true, act("buzz", "teamId", "t1", "questionId", old).get("ignored"));
        assertEquals("question", state().get("phase"));
        assertEquals(true, act("next_clue", "questionId", old).get("ignored"));
        assertEquals(1, state().get("clue"));
        assertEquals(true, act("reveal", "questionId", old).get("ignored"));
        assertEquals(true, act("next_question", "questionId", old).get("ignored"));
        assertEquals(true, act("judge", "token", token, "correct", "true", "questionId", old).get("ignored"));
        assertEquals(0, score("t1"));
    }

    @Test
    public void twoPhonesCannotSkipTwoClues() {
        act("start_question");
        act("next_clue", "fromClue", "1");
        assertEquals(true, act("next_clue", "fromClue", "1").get("ignored"));
        assertEquals(2, state().get("clue"));
    }

    @Test
    public void buzzAfterTimeUpIsJudgedByServerClock() {
        act("start_question");
        String id = qid();
        clock.addAndGet(GuessGame.QUESTION_MS);
        Map<String, Object> s = act("buzz", "teamId", "t1", "questionId", id);
        assertEquals(true, s.get("ignored"));
        assertEquals("reveal", state().get("phase"));
        assertEquals(0, score("t1"));
    }

    // ----- Time -----

    @Test
    public void cluesArriveEveryTwelveSeconds() {
        act("start_question");
        assertEquals(1, state().get("clue"));
        clock.addAndGet(11999);
        assertEquals(1, state().get("clue"));
        clock.addAndGet(1);
        assertEquals(2, state().get("clue"));
        clock.addAndGet(12000);
        assertEquals(3, state().get("clue"));
        clock.addAndGet(12000);
        assertEquals(4, state().get("clue"));
        clock.addAndGet(4000);
        Map<String, Object> s = state();
        assertEquals("reveal", s.get("phase"));
        assertEquals("time", s.get("revealReason"));
        assertEquals(0, score("t1"));
    }

    @Test
    public void tappingNextClueRestartsTheClueWait() {
        act("start_question");
        clock.addAndGet(10000);
        act("next_clue");
        clock.addAndGet(11000);
        assertEquals(2, state().get("clue"));
        clock.addAndGet(1000);
        assertEquals(3, state().get("clue"));
    }

    @Test
    public void onLeavePausesAndResumeContinues() {
        act("start_question");
        clock.addAndGet(5000);
        game.onLeave();
        clock.addAndGet(600000);
        Map<String, Object> s = state();
        assertEquals(true, s.get("paused"));
        assertEquals("question", s.get("phase"));
        assertEquals(35000L, s.get("msLeft"));
        assertEquals(true, act("buzz", "teamId", "t1").get("ignored"));
        act("resume");
        assertEquals(false, state().get("paused"));
        assertEquals(35000L, state().get("msLeft"));
    }

    // ----- Deck order -----

    @Test
    @SuppressWarnings("unchecked")
    public void noRepeatsAndEasyFirst() {
        Set<String> seen = new HashSet<String>();
        act("start_question");
        for (int i = 0; i < 20; i++) {
            String title = (String) ((Map<String, Object>) act("reveal").get("reveal")).get("answer");
            assertTrue("repeat: " + title, seen.add(title));
            int n = Integer.parseInt(title.substring(title.lastIndexOf(' ') + 1));
            if (i < GuessGame.ROUND_SIZE) assertTrue("round 1 is easy", n < 10);
            act("next_question");
        }
        assertEquals("final", state().get("phase"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void easySettingKeepsToEasyWhileItLasts() {
        act("set_difficulty", "difficulty", "easy");
        rejected("set_difficulty", "difficulty", "silly");
        act("start_question");
        for (int i = 0; i < 10; i++) {
            String title = (String) ((Map<String, Object>) act("reveal").get("reveal")).get("answer");
            assertTrue(Integer.parseInt(title.substring(title.lastIndexOf(' ') + 1)) < 10);
            act("next_question");
        }
        assertEquals("question", state().get("phase"));
    }

    @Test
    public void resetAllowsTheDeckAgainAndClearsScores() {
        String token = host();
        act("start_question");
        act("buzz", "teamId", "t1");
        act("judge", "token", token, "correct", "true");
        act("reset_game");
        Map<String, Object> s = state();
        assertEquals("ready", s.get("phase"));
        assertEquals(0, score("t1"));
        assertEquals(0, ((Number) s.get("questionNumber")).intValue());
        assertEquals(true, s.get("hasHost"));
    }

    @Test
    public void emptyDeckGivesAnErrorNotACrash() {
        game = new GuessGame(GuessGame.EMOJI_MOVIE, GuessGame.parseDeck("not json"), clock::get, new Random(1), null);
        rejected("start_question");
        assertEquals("ready", state().get("phase"));
    }

    // ----- Without phones -----

    @Test
    public void remoteCanAwardOnTheResultScreenOnce() {
        act("start_question");
        act("next_clue");
        Map<String, Object> s = act("reveal");
        assertEquals(true, s.get("canAward"));
        assertEquals(3, s.get("awardPoints"));
        act("award", "teamId", "t2", "questionId", (String) s.get("questionId"));
        assertEquals(3, score("t2"));
        assertEquals(true, act("award", "teamId", "t1").get("ignored"));
        assertEquals(0, score("t1"));
    }

    @Test
    public void unjudgedBuzzKeepsItsClueOnReveal() {
        act("start_question");
        act("buzz", "teamId", "t1");
        Map<String, Object> s = act("reveal");
        assertEquals("t1", s.get("suggestedTeamId"));
        assertEquals(4, s.get("awardPoints"));
    }

    @Test
    public void hostCanAwardAShoutedAnswerWithoutABuzz() {
        String token = host();
        act("start_question");
        rejected("award", "teamId", "t1");
        Map<String, Object> s = act("award", "teamId", "t1", "token", token);
        assertEquals("reveal", s.get("phase"));
        assertEquals(4, score("t1"));
        assertEquals(false, s.get("canAward"));
    }

    // ----- Wrong phase and teams -----

    @Test
    public void wrongPhaseAndUnknownAreRefused() {
        rejected("nonsense");
        rejected("buzz", "teamId", "t1");
        rejected("next_question");
        rejected("reveal");
        rejected("next_clue");
        act("start_question");
        rejected("start_question");
        rejected("next_question");
        rejected("add_team");
        rejected("buzz", "teamId", "t9");
        try {
            game.handle("GET", "other", null);
            fail();
        } catch (IllegalArgumentException expected) {
            // 400.
        }
    }

    @Test
    public void defaultTeamsAndLimits() {
        Map<String, Object> s = act("add_team");
        assertTrue(gson.toJson(s).contains("Diamonds"));
        rejected("add_team");
        act("set_team_name", "id", "t1", "name", "Sparklers");
        rejected("set_team_name", "id", "t2", "name", "sparklers");
        rejected("set_team_name", "id", "t2", "name", " ");
        act("remove_team", "id", "t3");
        rejected("remove_team", "id", "t2");
    }

    @Test
    public void finalNamesTheWinners() {
        String token = host();
        act("start_question");
        act("buzz", "teamId", "t2");
        act("judge", "token", token, "correct", "true");
        assertEquals(java.util.Arrays.asList("Hearts"), act("end_game").get("winners"));
    }

    // ----- The song -----

    @Test
    public void songPlaysOnlyOnRevealAndStopsOnNextQuestion() {
        rejected("play_song");
        act("start_question");
        rejected("play_song");
        act("reveal");
        assertEquals("loading", act("play_song").get("song"));
        assertEquals(1, songCalls.size());
        assertTrue(songCalls.get(0).startsWith("play english-song Zebra"));
        act("play_song");
        assertEquals("A second tap does not start it again", 1, songCalls.size());
        lastListener.onSongState("playing");
        assertEquals("playing", state().get("song"));
        act("next_question");
        assertEquals("off", state().get("song"));
        assertEquals("stop english-song", songCalls.get(1));
    }

    @Test
    public void lateSongNewsIsIgnoredAndFailureIsQuiet() {
        act("start_question");
        act("reveal");
        act("play_song");
        GuessGame.SongListener old = lastListener;
        act("stop_song");
        old.onSongState("playing");
        assertEquals("off", state().get("song"));
        act("play_song");
        lastListener.onSongState("failed");
        assertEquals("failed", state().get("song"));
        assertEquals("reveal", state().get("phase"));
    }

    @Test
    public void leavingStopsTheSong() {
        act("start_question");
        act("reveal");
        act("play_song");
        game.onLeave();
        assertEquals("stop english-song", songCalls.get(songCalls.size() - 1));
        assertEquals("off", state().get("song"));
    }

    @Test
    public void movieGameHasNoSong() {
        game = new GuessGame(GuessGame.EMOJI_MOVIE, deck(4), clock::get, new Random(1), fakeHost);
        act("start_question");
        act("reveal");
        rejected("play_song");
        assertTrue(songCalls.isEmpty());
    }

    @Test
    public void aHostThatThrowsNeverBreaksTheGame() {
        game = new GuessGame(GuessGame.ENGLISH_SONG, deck(4), clock::get, new Random(1), new GuessGame.SongHost() {
            @Override
            public void play(String owner, String query, GuessGame.SongListener listener) {
                throw new RuntimeException("no internet");
            }

            @Override
            public void stop(String owner) {
                throw new RuntimeException("no player");
            }
        });
        act("start_question");
        act("reveal");
        act("play_song");
        assertEquals("reveal", state().get("phase"));
        act("next_question");
        assertEquals("question", state().get("phase"));
    }

    // ----- The player, through the real server -----

    private static final class FakePlayer implements PlayerControl {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());

        @Override public void play(Song s) { calls.add("play " + s.title); }
        @Override public void stop() { calls.add("stop"); }
        @Override public void setPaused(boolean p) { calls.add("paused " + p); }
        @Override public void setVocalCut(boolean on) {}
        @Override public long positionMs() { return 0; }
        @Override public boolean isPlaying() { return false; }
    }

    private static Song song(String id, String title) {
        Song s = new Song();
        s.id = id;
        s.title = title;
        s.source = "saavn";
        s.streamUrl = "https://x/" + id;
        return s;
    }

    private ApiServer server(FakePlayer player) {
        ApiServer.Catalog catalog = new ApiServer.Catalog() {
            @Override
            public List<Song> search(String q) {
                return java.util.Arrays.asList(song("a", "Found"));
            }

            @Override
            public String resolveStream(Song s) {
                return s.streamUrl;
            }
        };
        ApiServer.LyricFetcher lyrics = new ApiServer.LyricFetcher() {
            @Override
            public LyricsClient.Result fetch(Song s) {
                return LyricsClient.NONE;
            }
        };
        return new ApiServer(0, player, null, "http://x/", catalog, lyrics, clock::get);
    }

    private static String waitFor(final String[] box, CountDownLatch latch) throws Exception {
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return box[0];
    }

    @Test
    public void realServerPlaysWithoutTouchingKaraokeAndStopsOnLeaving() throws Exception {
        FakePlayer player = new FakePlayer();
        ApiServer api = server(player);
        try {
            api.openApp(GuessGame.ENGLISH_SONG);
            final String[] box = new String[1];
            final CountDownLatch done = new CountDownLatch(1);
            api.gameSongs().play(GuessGame.ENGLISH_SONG, "anything", new GuessGame.SongListener() {
                @Override
                public void onSongState(String state) {
                    box[0] = state;
                    done.countDown();
                }
            });
            assertEquals("playing", waitFor(box, done));
            api.flushPlayerCommands();
            assertEquals(java.util.Arrays.asList("play Found"), player.calls);
            Map<String, Object> karaoke = api.state();
            assertNull("Karaoke must not see a game's song", karaoke.get("now"));
            assertEquals(0, ((List<?>) karaoke.get("queue")).size());
            api.goHome();
            api.flushPlayerCommands();
            api.gameSongs().stop(GuessGame.ENGLISH_SONG);
            api.flushPlayerCommands();
            assertEquals("stop", player.calls.get(player.calls.size() - 1));
            // A late end event from the game's song must do nothing to Karaoke.
            api.onSongEnded();
            assertNull(api.state().get("now"));
        } finally {
            api.stop();
        }
    }

    @Test
    public void realServerHoldsBackWhileKaraokeHasASongOrAnotherAppIsOpen() throws Exception {
        FakePlayer player = new FakePlayer();
        ApiServer api = server(player);
        try {
            // Not the active app.
            api.openApp("accent-wheel");
            final String[] box = new String[1];
            CountDownLatch first = new CountDownLatch(1);
            api.gameSongs().play(GuessGame.ENGLISH_SONG, "x", listener(box, first));
            assertEquals("failed", waitFor(box, first));

            // Karaoke holds a song, paused by the hub. Playing over it would lose its place.
            api.openApp("karaoke");
            api.search("x");
            assertNotNull(api.enqueue("a"));
            api.flushPlayerCommands();
            api.openApp(GuessGame.ENGLISH_SONG);
            api.flushPlayerCommands();
            player.calls.clear();
            CountDownLatch second = new CountDownLatch(1);
            api.gameSongs().play(GuessGame.ENGLISH_SONG, "x", listener(box, second));
            assertEquals("failed", waitFor(box, second));
            api.gameSongs().stop(GuessGame.ENGLISH_SONG);
            api.flushPlayerCommands();
            assertTrue("The player was left alone: " + player.calls, player.calls.isEmpty());
        } finally {
            api.stop();
        }
    }

    @Test
    public void realServerDropsASongFoundAfterTheRoomLeft() throws Exception {
        FakePlayer player = new FakePlayer();
        final CountDownLatch searching = new CountDownLatch(1), release = new CountDownLatch(1);
        ApiServer api = new ApiServer(0, player, null, "http://x/", new ApiServer.Catalog() {
            @Override
            public List<Song> search(String q) {
                searching.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return java.util.Arrays.asList(song("a", "Late"));
            }

            @Override
            public String resolveStream(Song s) {
                return s.streamUrl;
            }
        }, new ApiServer.LyricFetcher() {
            @Override
            public LyricsClient.Result fetch(Song s) {
                return LyricsClient.NONE;
            }
        }, clock::get);
        try {
            api.openApp(GuessGame.ENGLISH_SONG);
            final String[] box = new String[1];
            CountDownLatch done = new CountDownLatch(1);
            api.gameSongs().play(GuessGame.ENGLISH_SONG, "x", listener(box, done));
            assertTrue(searching.await(5, TimeUnit.SECONDS));
            api.gameSongs().stop(GuessGame.ENGLISH_SONG);
            release.countDown();
            assertEquals("failed", waitFor(box, done));
            api.flushPlayerCommands();
            assertFalse(player.calls.contains("play Late"));
        } finally {
            api.stop();
        }
    }

    private static GuessGame.SongListener listener(final String[] box, final CountDownLatch latch) {
        return new GuessGame.SongListener() {
            @Override
            public void onSongState(String state) {
                box[0] = state;
                latch.countDown();
            }
        };
    }

    // ----- The shipped decks -----

    private static String resource(String path) throws Exception {
        try (InputStream in = GuessGameTest.class.getResourceAsStream(path)) {
            assertNotNull(path, in);
            return new String(Http.readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    private static List<GuessGame.Question> shipped(String id) throws Exception {
        return GuessGame.parseDeck(resource("/web/decks/" + id + ".json"));
    }

    private static void commonChecks(List<GuessGame.Question> deck) {
        assertTrue("At least 60: " + deck.size(), deck.size() >= 60);
        Set<String> ids = new HashSet<String>(), titles = new HashSet<String>();
        int easy = 0;
        for (GuessGame.Question q : deck) {
            assertTrue("id repeats: " + q.id, ids.add(q.id));
            assertTrue("title repeats: " + q.title, titles.add(q.title.toLowerCase(Locale.ROOT)));
            assertFalse(q.title.trim().isEmpty());
            assertTrue(q.title + " year " + q.year, q.year >= 1940 && q.year <= 2026);
            assertTrue(q.title, Set.of("easy", "medium", "hard").contains(q.difficulty));
            if ("easy".equals(q.difficulty)) easy++;
        }
        assertTrue("Enough easy ones for round 1 and for easy mode", easy >= 20);
    }

    private static Set<String> titleWords(String title) {
        Set<String> out = new HashSet<String>();
        for (String w : title.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 4) out.add(w);
        }
        return out;
    }

    private static boolean hasWord(String text, String word) {
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.equals(word)) return true;
        }
        return false;
    }

    private static void noBannedText(String json) {
        assertFalse("em dash", json.contains("—"));
        assertFalse("en dash", json.contains("–"));
    }

    @Test
    public void songDeckIsValid() throws Exception {
        String raw = resource("/web/decks/english-song.json");
        noBannedText(raw);
        List<GuessGame.Question> deck = shipped(GuessGame.ENGLISH_SONG);
        commonChecks(deck);
        for (GuessGame.Question q : deck) {
            assertNotNull(q.title, q.movie);
            assertNotNull(q.title, q.singer);
            assertNotNull(q.title, q.original);
            assertEquals(q.title, 2, q.lines.size());
            assertTrue("Short excerpt only: " + q.title, q.original.split("\\s+").length <= 14);
            for (String line : q.lines) {
                assertFalse(q.title, line.trim().isEmpty());
                assertTrue("Too long: " + line, line.trim().split("\\s+").length <= 14);
                for (String w : titleWords(q.title)) {
                    assertFalse(q.title + " leaks '" + w + "' in: " + line, hasWord(line, w));
                }
            }
            for (String w : titleWords(q.title)) {
                assertFalse(q.title + " leaks through the singer", hasWord(q.singer, w));
            }
            assertEquals("No emoji in song clues: " + q.title, 0, countEmoji(q.lines.get(0) + q.lines.get(1)));
        }
    }

    @Test
    public void movieDeckIsValid() throws Exception {
        String raw = resource("/web/decks/emoji-movie.json");
        noBannedText(raw);
        List<GuessGame.Question> deck = shipped(GuessGame.EMOJI_MOVIE);
        commonChecks(deck);
        for (GuessGame.Question q : deck) {
            assertNotNull(q.title, q.lead);
            assertNotNull(q.title, q.emojis);
            int n = countEmoji(q.emojis);
            assertTrue(q.title + " has " + n + " emojis", n >= 3 && n <= 6);
            checkEmojiAge(q.title, q.emojis);
            for (String w : titleWords(q.title)) {
                assertFalse(q.title + " leaks through the lead", hasWord(q.lead, w));
            }
        }
    }

    @Test
    public void realDecksStaySecretWhilePlayed() throws Exception {
        for (String id : new String[] {GuessGame.ENGLISH_SONG, GuessGame.EMOJI_MOVIE}) {
            List<GuessGame.Question> d = shipped(id);
            game = new GuessGame(id, d, clock::get, new Random(3), null);
            act("start_question");
            for (int i = 0; i < d.size(); i++) {
                Map<String, Object> s = state();
                for (int c = 0; c < 3; c++) s = act("next_clue");
                String json = gson.toJson(s).toLowerCase(Locale.ROOT);
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) act("reveal").get("reveal");
                String title = ((String) r.get("answer")).toLowerCase(Locale.ROOT);
                if (title.length() >= 4) assertFalse(title + " was on screen: " + json, json.contains(title));
                if (i < d.size() - 1) act("next_question");
            }
        }
    }

    @Test
    public void realGamesCarryOurDecks() {
        assertTrue(((Number) GuessGame.fromResource(GuessGame.ENGLISH_SONG, clock::get, null).state().get("deckSize")).intValue() >= 60);
        assertTrue(((Number) GuessGame.fromResource(GuessGame.EMOJI_MOVIE, clock::get, null).state().get("deckSize")).intValue() >= 60);
    }

    // ----- Emoji rules -----

    /** One visible emoji per item, with the Indian flag as one. Everything joined or toned fails checkEmojiAge. */
    private static int countEmoji(String s) {
        int n = 0;
        int[] cps = s.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int c = cps[i];
            if (c == 0xFE0F) continue;
            if (c >= 0x1F1E6 && c <= 0x1F1FF) {
                i++;
                n++;
            } else if (c >= 0x1F000 || (c >= 0x2190 && c <= 0x2BFF)) {
                n++;
            }
        }
        return n;
    }

    /** Emoji added after Unicode 11, which Android 9 cannot draw. Written out by hand from the Unicode emoji lists. */
    private static boolean afterUnicode11(int c) {
        int[][] ranges = {
            {0x1F6D5, 0x1F6DF}, {0x1F6FA, 0x1F6FF}, {0x1F7E0, 0x1F7FF}, {0x1F90C, 0x1F90F}, {0x1F93F, 0x1F93F},
            {0x1F971, 0x1F972}, {0x1F977, 0x1F979}, {0x1F97B, 0x1F97B}, {0x1F9A3, 0x1F9AF}, {0x1F9BA, 0x1F9BF},
            {0x1F9C3, 0x1F9CF}, {0x1FA70, 0x1FAFF}, {0x1F6D3, 0x1F6D4},
        };
        for (int[] r : ranges) {
            if (c >= r[0] && c <= r[1]) return true;
        }
        return false;
    }

    private static void checkEmojiAge(String title, String emojis) {
        int[] cps = emojis.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int c = cps[i];
            String where = title + " has U+" + Integer.toHexString(c).toUpperCase(Locale.ROOT);
            assertFalse(where + ": newer than Unicode 11", afterUnicode11(c));
            assertNotEquals(where + ": a joined sequence", 0x200D, c);
            assertNotEquals(where + ": a keycap sequence", 0x20E3, c);
            assertFalse(where + ": a skin tone", c >= 0x1F3FB && c <= 0x1F3FF);
            assertFalse(where + ": a gender sign", c == 0x2640 || c == 0x2642);
            if (c >= 0x1F1E6 && c <= 0x1F1FF) {
                assertEquals(where + ": only India's flag", 0x1F1EE, c);
                assertEquals(where + ": only India's flag", 0x1F1F3, cps[++i]);
            }
        }
    }

    @Test
    public void theEmojiCheckerCatchesBadOnes() {
        for (String bad : new String[] {"🪔", "👩‍💻", "👍🏽", "🇺🇸", "🥱"}) {
            try {
                checkEmojiAge("test", bad);
            } catch (AssertionError expected) {
                continue;
            }
            fail("Should catch " + bad);
        }
    }
}
