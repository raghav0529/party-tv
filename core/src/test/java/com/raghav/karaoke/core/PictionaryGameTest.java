package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

public class PictionaryGameTest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final List<String> EASY = Arrays.asList("Candle", "Rainbow", "Rocket", "Guitar", "Laddoo", "Samosa",
            "Chai", "Mango", "Kite", "Elephant", "Peacock", "Cricket bat", "Auto rickshaw", "Taj Mahal", "Pressure cooker",
            "Ceiling fan", "Umbrella", "Sunglasses");
    private static final List<String> HARD = Arrays.asList("Power cut", "Wi-Fi buffering", "Traffic jam", "Monday morning",
            "Global warming", "Getting scolded by Mummy", "Relatives asking about marks", "Missing the last metro");

    private final AtomicLong clock = new AtomicLong(1000000);
    private PictionaryGame game;

    @Before
    public void setUp() {
        game = new PictionaryGame(clock::get, new Random(7));
    }

    private static Map<String, String> fields(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private JsonObject act(String... kv) {
        return JsonParser.parseString(GSON.toJson(game.handle("POST", "action", fields(kv)))).getAsJsonObject();
    }

    private void refused(String... kv) {
        try {
            act(kv);
            fail("Should be refused: " + Arrays.toString(kv));
        } catch (IllegalArgumentException expected) {
            // 400
        }
    }

    private String stateText() {
        return GSON.toJson(game.handle("GET", "state", null));
    }

    private JsonObject state() {
        return JsonParser.parseString(stateText()).getAsJsonObject();
    }

    private String strokesText(int since) {
        return GSON.toJson(game.handle("GET", "strokes?since=" + since, null));
    }

    private JsonObject strokes(int since) {
        return JsonParser.parseString(strokesText(since)).getAsJsonObject();
    }

    private String phase() {
        return state().get("phase").getAsString();
    }

    private String promptId() {
        return state().get("promptId").getAsString();
    }

    private int secondsLeft() {
        return state().get("secondsLeft").getAsInt();
    }

    private int score(int index) {
        return state().getAsJsonArray("teams").get(index).getAsJsonObject().get("score").getAsInt();
    }

    private void pass(long ms) {
        clock.addAndGet(ms);
    }

    private String claim() {
        return act("action", "claim", "name", "Raghav").get("drawerToken").getAsString();
    }

    private String startDrawing() {
        String token = claim();
        act("action", "start_drawing", "token", token);
        return token;
    }

    private String promptFor(String token) {
        Object reply = game.handle("POST", "drawer", fields("token", token));
        return JsonParser.parseString(GSON.toJson(reply)).getAsJsonObject().get("prompt").getAsString();
    }

    private static String stroke(String id, int from, int... points) {
        return "{\"id\":\"" + id + "\",\"color\":\"red\",\"size\":2,\"from\":" + from + ",\"points\":" + Arrays.toString(points) + "}";
    }

    private JsonObject draw(String token, String... strokes) {
        return act("action", "draw", "token", token, "strokes", "[" + String.join(",", strokes) + "]");
    }

    /** Plays the current team's turn to the summary with the free points, and moves to the next one. */
    private void playTurnAndNext() {
        startDrawing();
        act("action", "got_it", "promptId", promptId());
        act("action", "next_turn");
    }

    // ----- Secrecy -----

    @Test
    public void promptAndTokenNeverAppearInStateOrStrokesUntilSummary() {
        for (int turn = 0; turn < 6; turn++) {
            assertEquals("ready", phase());
            JsonObject claimed = act("action", "claim", "name", "A");
            String token = claimed.get("drawerToken").getAsString();
            String prompt = promptFor(token);
            assertFalse(claimed.toString().contains(prompt));
            checkHidden(prompt, token, "claimed");

            act("action", "start_drawing", "token", token);
            JsonObject drew = draw(token, stroke("a", 0, 1, 2, 3, 4));
            assertFalse(drew.toString().contains(prompt));
            assertFalse(drew.has("drawerToken"));
            checkHidden(prompt, token, "drawing");

            JsonObject paused = act("action", "pause");
            assertFalse(paused.toString().contains(prompt));
            checkHidden(prompt, token, "paused");
            act("action", "resume");

            if (turn == 0) {
                // A skip must not leak either the old prompt or the new one.
                JsonObject skipped = act("action", "skip", "token", token);
                assertFalse(skipped.toString().contains(prompt));
                checkHidden(prompt, token, "drawing");
                prompt = promptFor(token);
                checkHidden(prompt, token, "drawing");
            }
            if (turn == 1) {
                JsonObject taken = act("action", "take_over", "name", "B");
                assertFalse(taken.toString().contains(prompt));
                checkHidden(prompt, taken.get("drawerToken").getAsString(), "claimed");
                token = taken.get("drawerToken").getAsString();
                prompt = promptFor(token);
                checkHidden(prompt, token, "claimed");
                act("action", "start_drawing", "token", token);
            }

            pass(PictionaryGame.DRAW_MS);
            checkHidden(prompt, token, "steal");
            act("action", "pause");
            checkHidden(prompt, token, "paused");
            act("action", "resume");
            // A valid token gets no prompt once the drawing is over.
            String drawerReply = GSON.toJson(game.handle("POST", "drawer", fields("token", token)));
            assertFalse(drawerReply.contains(prompt));

            JsonObject summary = act("action", "steal", "teamId", "none");
            assertEquals("summary", summary.get("phase").getAsString());
            assertEquals(prompt, summary.get("prompt").getAsString());
            assertFalse(stateText().contains(token));
            act("action", "next_turn");
            assertFalse(stateText().contains(prompt));
            assertFalse(stateText().contains("\"prompt\""));
        }
    }

    private void checkHidden(String prompt, String token, String wantedPhase) {
        assertEquals(wantedPhase, phase());
        String state = stateText();
        String strokes = strokesText(0);
        assertFalse(state.contains(prompt));
        assertFalse(state.contains("\"prompt\""));
        assertFalse(state.contains("hard"));
        assertFalse(state.contains(token));
        assertFalse(state.contains("drawerToken"));
        assertFalse(strokes.contains(prompt));
        assertFalse(strokes.contains(token));
    }

    @Test
    public void customPromptIsNotListedInState() {
        act("action", "add_prompt", "text", "Secret thing");
        assertFalse(stateText().contains("Secret thing"));
        assertEquals(1, state().get("customPrompts").getAsInt());
    }

    @Test
    public void drawerRouteNeedsTheToken() {
        String token = claim();
        assertNotNull(promptFor(token));
        for (Map<String, String> bad : Arrays.asList(fields(), fields("token", ""), fields("token", "nope"))) {
            try {
                game.handle("POST", "drawer", bad);
                fail("Should be refused");
            } catch (IllegalArgumentException expected) {
                // 400
            }
        }
        try {
            game.handle("GET", "drawer", null);
            fail("GET must not work");
        } catch (IllegalArgumentException expected) {
            // 400
        }
    }

    @Test
    public void everyDrawerActionNeedsTheToken() {
        String token = claim();
        refused("action", "start_drawing", "token", "nope");
        refused("action", "start_drawing");
        act("action", "start_drawing", "token", token);
        refused("action", "draw", "token", "nope", "strokes", "[]");
        refused("action", "draw", "strokes", "[]");
        refused("action", "undo_stroke", "token", "nope");
        refused("action", "clear", "token", "nope");
        refused("action", "skip", "token", "nope");
    }

    @Test
    public void takeOverKillsTheOldTokenGivesNewPromptAndClearsCanvas() {
        String oldToken = startDrawing();
        draw(oldToken, stroke("a", 0, 10, 10, 20, 20));
        String oldPrompt = promptFor(oldToken);
        String oldId = promptId();

        JsonObject taken = act("action", "take_over", "name", "Asha");
        String newToken = taken.get("drawerToken").getAsString();
        assertNotEquals(oldToken, newToken);
        assertEquals("claimed", taken.get("phase").getAsString());
        assertEquals("Asha", taken.get("drawerName").getAsString());
        assertNotEquals(oldId, taken.get("promptId").getAsString());
        assertNotEquals(oldPrompt, promptFor(newToken));
        assertEquals(0, strokes(0).getAsJsonArray("strokes").size());
        assertTrue(strokes(0).get("reset").getAsBoolean());

        try {
            game.handle("POST", "drawer", fields("token", oldToken));
            fail("Old token must stop working");
        } catch (IllegalArgumentException expected) {
            // 400
        }
        refused("action", "start_drawing", "token", oldToken);
        act("action", "start_drawing", "token", newToken);
        refused("action", "draw", "token", oldToken, "strokes", "[" + stroke("b", 0, 1, 1) + "]");
        refused("action", "clear", "token", oldToken);
        refused("action", "undo_stroke", "token", oldToken);
        refused("action", "skip", "token", oldToken);
        assertEquals(60, secondsLeft());
    }

    @Test
    public void takeOverIsRefusedWithNothingToTakeOver() {
        refused("action", "take_over");
        startDrawing();
        pass(PictionaryGame.DRAW_MS);
        assertEquals("steal", phase());
        refused("action", "take_over");
        act("action", "steal", "teamId", "none");
        refused("action", "take_over");
    }

    @Test
    public void tokenDiesAtSummary() {
        String token = startDrawing();
        act("action", "got_it", "promptId", promptId());
        refused("action", "draw", "token", token, "strokes", "[]");
        try {
            game.handle("POST", "drawer", fields("token", token));
            fail("Token must be dead");
        } catch (IllegalArgumentException expected) {
            // 400
        }
    }

    // ----- Turn flow and clock -----

    @Test
    public void claimRefusedTwiceAndStartsTheReadingClock() {
        claim();
        assertEquals("claimed", phase());
        assertEquals(15, secondsLeft());
        refused("action", "claim");
        pass(14999);
        assertEquals("claimed", phase());
    }

    @Test
    public void clockStartsAnywayAfterFifteenSeconds() {
        String token = claim();
        pass(15000);
        assertEquals("drawing", phase());
        assertEquals(60, secondsLeft());
        refused("action", "start_drawing", "token", token);
        draw(token, stroke("a", 0, 1, 1));
    }

    @Test
    public void startDrawingEarlyGivesTheFullClock() {
        String token = claim();
        pass(3000);
        act("action", "start_drawing", "token", token);
        assertEquals("drawing", phase());
        assertEquals(60, secondsLeft());
    }

    @Test
    public void timeUpGoesToStealThenSummaryWithNoPoints() {
        startDrawing();
        pass(59999);
        assertEquals("drawing", phase());
        pass(1);
        assertEquals("steal", phase());
        assertEquals(10, secondsLeft());
        pass(9999);
        assertEquals("steal", phase());
        pass(1);
        JsonObject s = state();
        assertEquals("summary", s.get("phase").getAsString());
        assertEquals(0, s.get("points").getAsInt());
        assertEquals("none", s.get("outcome").getAsString());
    }

    @Test
    public void aLongGapPassesThroughEveryPhase() {
        claim();
        pass(100000);
        assertEquals("summary", phase());
    }

    @Test
    public void scoringThreeInFirstTwentySeconds() {
        startDrawing();
        pass(20000);
        JsonObject s = act("action", "got_it", "promptId", promptId());
        assertEquals("summary", s.get("phase").getAsString());
        assertEquals(3, s.get("points").getAsInt());
        assertEquals(3, score(0));
        assertEquals(0, score(1));
    }

    @Test
    public void scoringTwoAfterTwentySeconds() {
        startDrawing();
        pass(20001);
        act("action", "got_it", "promptId", promptId());
        assertEquals(2, score(0));
    }

    @Test
    public void hardPromptAddsOne() {
        // Round 2 onward can draw hard prompts. Keep playing until each case has been seen.
        boolean fast = false, slow = false;
        for (int i = 0; i < 20; i++) act("action", "add_prompt", "text", "filler " + i);
        playTurnAndNext();
        playTurnAndNext();
        for (int turn = 0; turn < 40 && !(fast && slow); turn++) {
            String token = startDrawing();
            boolean hard = HARD.contains(promptFor(token));
            boolean late = fast && hard;
            pass(late ? 30000 : 1000);
            int points = act("action", "got_it", "promptId", promptId()).get("points").getAsInt();
            if (hard && late) {
                assertEquals(3, points);
                slow = true;
            } else if (hard) {
                assertEquals(4, points);
                fast = true;
            } else {
                assertEquals(3, points);
            }
            assertEquals(hard, state().get("hard").getAsBoolean());
            act("action", "next_turn");
        }
        assertTrue(fast && slow);
    }

    @Test
    public void stealIsOnePointEvenOnAHardPromptAndHidesUntilItEnds() {
        for (int i = 0; i < 40 ; i++) {
            String token = startDrawing();
            boolean hard = HARD.contains(promptFor(token));
            pass(PictionaryGame.DRAW_MS);
            int before = score(0) + score(1);
            String thief = state().getAsJsonArray("teams").get(i % 2 == 0 ? 1 : 0).getAsJsonObject().get("id").getAsString();
            JsonObject s = act("action", "steal", "teamId", thief);
            assertEquals(1, s.get("points").getAsInt());
            assertEquals("stolen", s.get("outcome").getAsString());
            assertEquals(before + 1, score(0) + score(1));
            act("action", "next_turn");
            if (hard) return;
        }
        fail("Never drew a hard prompt");
    }

    @Test
    public void stealRules() {
        refused("action", "steal", "teamId", "t2");
        startDrawing();
        refused("action", "steal", "teamId", "t2");
        pass(PictionaryGame.DRAW_MS);
        refused("action", "steal", "teamId", "t1"); // The drawing team itself.
        refused("action", "steal", "teamId", "t99");
        refused("action", "steal");
        act("action", "steal", "teamId", "t2");
        assertEquals(0, score(0));
        assertEquals(1, score(1));
        refused("action", "steal", "teamId", "t2");
    }

    @Test
    public void stealNoneGivesNothing() {
        startDrawing();
        pass(PictionaryGame.DRAW_MS);
        act("action", "steal", "teamId", "none");
        assertEquals("summary", phase());
        assertEquals(0, score(0) + score(1));
    }

    @Test
    public void staleGotItIsIgnoredWithoutError() {
        startDrawing();
        String id = promptId();
        JsonObject first = act("action", "got_it", "promptId", id);
        assertEquals(3, first.get("points").getAsInt());
        JsonObject again = act("action", "got_it", "promptId", id);
        assertTrue(again.get("ignored").getAsBoolean());
        assertEquals(3, score(0));
        act("action", "next_turn");
        assertTrue(act("action", "got_it", "promptId", id).get("ignored").getAsBoolean());
        startDrawing();
        assertTrue(act("action", "got_it", "promptId", id).get("ignored").getAsBoolean());
        assertEquals("drawing", phase());
        assertEquals(3, score(0));
    }

    @Test
    public void gotItAfterSkipWithOldIdDoesNotScore() {
        String token = startDrawing();
        String id = promptId();
        act("action", "skip", "token", token);
        assertTrue(act("action", "got_it", "promptId", id).get("ignored").getAsBoolean());
        assertEquals("drawing", phase());
    }

    @Test
    public void gotItAfterTimeUpIsJudgedByTheServerClock() {
        startDrawing();
        String id = promptId();
        pass(PictionaryGame.DRAW_MS);
        JsonObject s = act("action", "got_it", "promptId", id);
        assertTrue(s.get("ignored").getAsBoolean());
        assertEquals("steal", s.get("phase").getAsString());
        assertEquals(0, score(0));
    }

    @Test
    public void gotItRules() {
        refused("action", "got_it");
        claim();
        refused("action", "got_it", "promptId", promptId()); // Still reading.
    }

    @Test
    public void pauseIsExactAndCountsTowardNeitherClockNorScoring() {
        startDrawing();
        pass(10500);
        long left = state().get("msLeft").getAsLong();
        act("action", "pause");
        assertEquals("paused", phase());
        assertEquals("drawing", state().get("pausedFrom").getAsString());
        pass(500000);
        assertEquals(left, state().get("msLeft").getAsLong());
        assertEquals("paused", phase());
        act("action", "resume");
        assertEquals(left, state().get("msLeft").getAsLong());
        assertEquals("drawing", phase());
        pass(9500); // 20 s of real drawing so far, whatever the pause took.
        act("action", "got_it", "promptId", promptId());
        assertEquals(3, score(0));
    }

    @Test
    public void pauseDuringReadingAndStealIsExact() {
        claim();
        pass(5000);
        act("action", "pause");
        pass(90000);
        act("action", "resume");
        assertEquals("claimed", phase());
        assertEquals(10, secondsLeft());
        pass(10000);
        assertEquals("drawing", phase());
        pass(PictionaryGame.DRAW_MS + 4000);
        assertEquals(6, secondsLeft());
        act("action", "pause");
        assertEquals("steal", state().get("pausedFrom").getAsString());
        pass(90000);
        act("action", "resume");
        assertEquals("steal", phase());
        assertEquals(6, secondsLeft());
    }

    @Test
    public void pauseRules() {
        refused("action", "pause");
        refused("action", "resume");
        String token = startDrawing();
        refused("action", "resume");
        act("action", "pause");
        refused("action", "pause");
        refused("action", "draw", "token", token, "strokes", "[" + stroke("a", 0, 1, 1) + "]");
        refused("action", "undo_stroke", "token", token);
        refused("action", "clear", "token", token);
        refused("action", "skip", "token", token);
        assertEquals(0, strokes(0).getAsJsonArray("strokes").size());
    }

    @Test
    public void pausedDrawingStaysAndGotItStillScores() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1));
        act("action", "pause");
        assertEquals(1, strokes(0).getAsJsonArray("strokes").size());
        JsonObject s = act("action", "got_it", "promptId", promptId());
        assertEquals("summary", s.get("phase").getAsString());
        assertEquals(3, s.get("points").getAsInt());
    }

    @Test
    public void onLeavePausesALiveTurnOnly() {
        game.onLeave();
        assertEquals("ready", phase());
        String token = claim();
        game.onLeave();
        assertEquals("paused", phase());
        assertEquals("claimed", state().get("pausedFrom").getAsString());
        game.onLeave(); // Twice is harmless.
        pass(1000000);
        act("action", "resume");
        act("action", "start_drawing", "token", token);
        pass(1000);
        int left = secondsLeft();
        game.onLeave();
        pass(1000000);
        assertEquals("paused", phase());
        assertEquals(left, secondsLeft());
        act("action", "resume");
        pass(PictionaryGame.DRAW_MS);
        assertEquals("steal", phase());
        game.onLeave();
        assertEquals("steal", state().get("pausedFrom").getAsString());
        act("action", "resume");
        act("action", "steal", "teamId", "none");
        game.onLeave();
        assertEquals("summary", phase());
    }

    @Test
    public void freeSkipOnceKeepsClockAndClearsCanvas() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1));
        pass(5000);
        String oldPrompt = promptFor(token);
        String oldId = promptId();
        assertEquals(1, state().get("skipLeft").getAsInt());
        JsonObject s = act("action", "skip", "token", token);
        assertEquals(0, s.get("skipLeft").getAsInt());
        assertEquals(55, s.get("secondsLeft").getAsInt());
        assertEquals("drawing", s.get("phase").getAsString());
        assertNotEquals(oldId, s.get("promptId").getAsString());
        assertNotEquals(oldPrompt, promptFor(token));
        assertEquals(0, strokes(0).getAsJsonArray("strokes").size());
        refused("action", "skip", "token", token);
        // The next turn has its own skip.
        act("action", "got_it", "promptId", promptId());
        act("action", "next_turn");
        token = startDrawing();
        assertEquals(1, state().get("skipLeft").getAsInt());
        act("action", "skip", "token", token);
    }

    @Test
    public void teamsRotateAndRoundRises() {
        assertEquals("t1", state().get("currentTeamId").getAsString());
        assertEquals(1, state().get("round").getAsInt());
        startDrawing();
        JsonObject s = act("action", "got_it", "promptId", promptId());
        assertEquals("t2", s.get("nextTeamId").getAsString());
        assertEquals("Hearts", s.get("nextTeamName").getAsString());
        act("action", "next_turn");
        assertEquals("t2", state().get("currentTeamId").getAsString());
        assertEquals(1, state().get("round").getAsInt());
        playTurnAndNext();
        assertEquals("t1", state().get("currentTeamId").getAsString());
        assertEquals(2, state().get("round").getAsInt());
    }

    @Test
    public void nextTurnOnlyFromSummaryAndClearsEverything() {
        refused("action", "next_turn");
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1));
        refused("action", "next_turn");
        act("action", "got_it", "promptId", promptId());
        assertEquals(1, strokes(0).getAsJsonArray("strokes").size()); // Final drawing stays for the summary.
        JsonObject ready = act("action", "next_turn");
        assertTrue(ready.get("promptId").isJsonNull());
        assertTrue(ready.get("drawerName").isJsonNull());
        assertEquals(0, strokes(0).getAsJsonArray("strokes").size());
    }

    // ----- Deck -----

    private Set<String> playAndCollect(int turns) {
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < turns; i++) {
            String token = startDrawing();
            assertTrue("Repeat at turn " + i, seen.add(promptFor(token)));
            act("action", "got_it", "promptId", promptId());
            act("action", "next_turn");
        }
        return seen;
    }

    @Test
    public void roundOneIsEasyOnly() {
        for (int t = 0; t < 2; t++) {
            String token = startDrawing();
            assertTrue(EASY.contains(promptFor(token)));
            act("action", "skip", "token", token);
            assertTrue(EASY.contains(promptFor(token)));
            act("action", "got_it", "promptId", promptId());
            act("action", "next_turn");
        }
    }

    @Test
    public void noRepeatsAcrossAllTeamsForTheWholeDeck() {
        assertEquals(40, playAndCollect(40).size());
    }

    @Test
    public void skippedPromptsComeBackFirstWhenTheDeckRunsOut() {
        String token = startDrawing();
        String skippedPrompt = promptFor(token);
        act("action", "skip", "token", token);
        Set<String> seen = new HashSet<String>();
        seen.add(promptFor(token));
        act("action", "got_it", "promptId", promptId());
        act("action", "next_turn");
        for (int i = 0; i < 38; i++) {
            token = startDrawing();
            assertTrue(seen.add(promptFor(token)));
            act("action", "got_it", "promptId", promptId());
            act("action", "next_turn");
        }
        assertFalse(seen.contains(skippedPrompt));
        token = startDrawing();
        assertEquals(skippedPrompt, promptFor(token));
    }

    @Test
    public void deckStartsOverWhenEverythingIsUsedAndNeverGetsStuck() {
        playAndCollect(40);
        Set<String> again = playAndCollect(40);
        assertEquals(40, again.size());
    }

    @Test
    public void customPromptJoinsFromRoundTwo() {
        act("action", "add_prompt", "text", "  Raghav dancing  ");
        Set<String> seen = playAndCollect(41);
        assertTrue(seen.contains("Raghav dancing"));
    }

    @Test
    public void addPromptLimits() {
        act("action", "add_prompt", "text", new String(new char[40]).replace('\0', 'a'));
        refused("action", "add_prompt", "text", new String(new char[41]).replace('\0', 'b'));
        refused("action", "add_prompt", "text", "   ");
        refused("action", "add_prompt");
        refused("action", "add_prompt", "text", "candle");
    }

    // ----- Strokes -----

    @Test
    public void strokesExtendAndPollsGetOnlyTheNewPoints() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 2, 3, 4));
        int v1 = state().get("strokeVersion").getAsInt();
        draw(token, stroke("a", 2, 5, 6));
        JsonObject delta = strokes(v1);
        assertFalse(delta.get("reset").getAsBoolean());
        JsonArray list = delta.getAsJsonArray("strokes");
        assertEquals(1, list.size());
        JsonObject s = list.get(0).getAsJsonObject();
        assertEquals("a", s.get("id").getAsString());
        assertEquals(2, s.get("from").getAsInt());
        assertEquals("[5,6]", s.get("points").toString());

        JsonObject full = strokes(0).getAsJsonArray("strokes").get(0).getAsJsonObject();
        assertEquals(0, full.get("from").getAsInt());
        assertEquals("[1,2,3,4,5,6]", full.get("points").toString());
        assertEquals("red", full.get("color").getAsString());
        assertEquals(2, full.get("size").getAsInt());

        int v2 = state().get("strokeVersion").getAsInt();
        JsonObject none = strokes(v2);
        assertEquals(0, none.getAsJsonArray("strokes").size());
        assertEquals(v2, none.get("version").getAsInt());
    }

    @Test
    public void batchWithSeveralStrokesKeepsTheirOrder() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1), stroke("b", 0, 2, 2), stroke("c", 0, 3, 3));
        JsonArray list = strokes(0).getAsJsonArray("strokes");
        assertEquals("a", list.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("c", list.get(2).getAsJsonObject().get("id").getAsString());
        int v = state().get("strokeVersion").getAsInt();
        draw(token, stroke("c", 1, 4, 4), stroke("d", 0, 5, 5));
        list = strokes(v).getAsJsonArray("strokes");
        assertEquals(2, list.size());
        assertEquals("c", list.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("d", list.get(1).getAsJsonObject().get("id").getAsString());
    }

    @Test
    public void retryAfterALostReplyDoesNotDoublePoints() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1, 2, 2));
        JsonObject retry = draw(token, stroke("a", 0, 1, 1, 2, 2));
        assertEquals(0, retry.get("accepted").getAsInt());
        assertEquals(0, retry.get("ignoredPoints").getAsInt());
        // A retry that partly overlaps keeps only the new points.
        JsonObject partly = draw(token, stroke("a", 1, 2, 2, 3, 3));
        assertEquals(1, partly.get("accepted").getAsInt());
        assertEquals("[1,1,2,2,3,3]", strokes(0).getAsJsonArray("strokes").get(0).getAsJsonObject().get("points").toString());
    }

    @Test
    public void strokeWithoutFromSimplyFollowsOn() {
        String token = startDrawing();
        act("action", "draw", "token", token, "strokes", "[{\"id\":\"a\",\"color\":\"blue\",\"size\":3,\"points\":[1,1]}]");
        act("action", "draw", "token", token, "strokes", "[{\"id\":\"a\",\"color\":\"blue\",\"size\":3,\"points\":[2,2]}]");
        assertEquals("[1,1,2,2]", strokes(0).getAsJsonArray("strokes").get(0).getAsJsonObject().get("points").toString());
    }

    @Test
    public void undoRemovesLastAndScreensStartOver() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1), stroke("b", 0, 2, 2));
        int v = state().get("strokeVersion").getAsInt();
        act("action", "undo_stroke", "token", token);
        JsonObject after = strokes(v);
        assertTrue(after.get("reset").getAsBoolean());
        assertEquals(1, after.getAsJsonArray("strokes").size());
        assertEquals("a", after.getAsJsonArray("strokes").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    public void retriedUndoAndRetriedBatchCannotDoDamage() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1), stroke("b", 0, 2, 2));
        act("action", "undo_stroke", "token", token, "strokeId", "b");
        act("action", "undo_stroke", "token", token, "strokeId", "b");
        assertEquals(1, strokes(0).getAsJsonArray("strokes").size());
        draw(token, stroke("b", 0, 2, 2)); // A late retry of a removed stroke.
        assertEquals(1, strokes(0).getAsJsonArray("strokes").size());
        act("action", "clear", "token", token);
        draw(token, stroke("a", 1, 9, 9));
        assertEquals(0, strokes(0).getAsJsonArray("strokes").size());
    }

    @Test
    public void undoOnEmptyCanvasIsHarmless() {
        String token = startDrawing();
        act("action", "undo_stroke", "token", token);
        assertEquals(0, strokes(0).getAsJsonArray("strokes").size());
    }

    @Test
    public void clearTellsAStaleScreenToStartOver() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1));
        int v = state().get("strokeVersion").getAsInt();
        act("action", "clear", "token", token);
        draw(token, stroke("b", 0, 2, 2));
        JsonObject after = strokes(v);
        assertTrue(after.get("reset").getAsBoolean());
        assertTrue(after.get("clearedAt").getAsInt() > v);
        assertEquals(1, after.getAsJsonArray("strokes").size());
        assertEquals("b", after.getAsJsonArray("strokes").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    public void aReloadedScreenGetsTheWholeDrawing() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1));
        draw(token, stroke("a", 1, 2, 2), stroke("b", 0, 3, 3));
        draw(token, stroke("c", 0, 4, 4));
        JsonObject all = strokes(0);
        assertEquals(3, all.getAsJsonArray("strokes").size());
        assertEquals("[1,1,2,2]", all.getAsJsonArray("strokes").get(0).getAsJsonObject().get("points").toString());
        assertEquals(state().get("strokeVersion").getAsInt(), all.get("version").getAsInt());
        // A number the server never gave out, as after an app restart, also starts over.
        assertTrue(strokes(99999).get("reset").getAsBoolean());
    }

    @Test
    public void versionNeverGoesBackAcrossTurns() {
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1));
        int v = state().get("strokeVersion").getAsInt();
        act("action", "got_it", "promptId", promptId());
        act("action", "next_turn");
        assertTrue(state().get("strokeVersion").getAsInt() > v);
        assertTrue(strokes(v).get("reset").getAsBoolean());
    }

    @Test
    public void badStrokesAreRefusedAndChangeNothing() {
        String token = startDrawing();
        String[] bad = {
            null, "", "nonsense", "{}", "[1]", "[{}]",
            "[{\"id\":\"a\",\"color\":\"red\",\"size\":2,\"points\":[1]}]",
            "[{\"id\":\"a\",\"color\":\"red\",\"size\":2,\"points\":[1001,1]}]",
            "[{\"id\":\"a\",\"color\":\"red\",\"size\":2,\"points\":[-1,1]}]",
            "[{\"id\":\"a\",\"color\":\"red\",\"size\":2,\"points\":[1.5,1]}]",
            "[{\"id\":\"a\",\"color\":\"red\",\"size\":2,\"points\":[\"1\",1]}]",
            "[{\"id\":\"a\",\"color\":\"red\",\"size\":2,\"points\":[null,1]}]",
            "[{\"id\":\"a\",\"color\":\"pink\",\"size\":2,\"points\":[1,1]}]",
            "[{\"id\":\"a\",\"color\":\"red\",\"size\":4,\"points\":[1,1]}]",
            "[{\"id\":\"a\",\"color\":\"red\",\"size\":0,\"points\":[1,1]}]",
            "[{\"id\":\"<b>\",\"color\":\"red\",\"size\":1,\"points\":[1,1]}]",
            "[{\"color\":\"red\",\"size\":1,\"points\":[1,1]}]",
            "[{\"id\":\"a\",\"color\":\"red\",\"size\":1}]",
            // The good first stroke must not get in when a later one is bad.
            "[{\"id\":\"g\",\"color\":\"red\",\"size\":1,\"points\":[1,1]},{\"id\":\"h\",\"color\":\"red\",\"size\":1,\"points\":[2000,1]}]",
        };
        int v = state().get("strokeVersion").getAsInt();
        for (String b : bad) {
            Map<String, String> f = fields("action", "draw", "token", token);
            if (b != null) f.put("strokes", b);
            try {
                game.handle("POST", "action", f);
                fail("Should be refused: " + b);
            } catch (IllegalArgumentException expected) {
                // 400
            }
        }
        assertEquals(v, state().get("strokeVersion").getAsInt());
        assertEquals(0, strokes(0).getAsJsonArray("strokes").size());
    }

    @Test
    public void edgeCoordinatesAreFine() {
        String token = startDrawing();
        assertEquals(2, draw(token, stroke("a", 0, 0, 0, 1000, 1000)).get("accepted").getAsInt());
    }

    @Test
    public void drawOnlyDuringDrawingWithASmallGraceAtTimeUp() {
        String token = claim();
        refused("action", "draw", "token", token, "strokes", "[" + stroke("a", 0, 1, 1) + "]");
        act("action", "start_drawing", "token", token);
        pass(PictionaryGame.DRAW_MS + 500);
        assertEquals("steal", phase());
        draw(token, stroke("late", 0, 1, 1)); // Was on its way when time ran out.
        assertEquals(1, strokes(0).getAsJsonArray("strokes").size());
        pass(600);
        refused("action", "draw", "token", token, "strokes", "[" + stroke("later", 0, 1, 1) + "]");
        assertEquals(1, strokes(0).getAsJsonArray("strokes").size());
    }

    @Test
    public void pointLimitIgnoresTheRestAndSaysSo() {
        String token = startDrawing();
        int[] many = new int[2000];
        int sent = 0;
        JsonObject last = null;
        int n = 0;
        while (sent + 1000 <= PictionaryGame.MAX_POINTS) {
            last = draw(token, stroke("s" + n++, 0, many));
            sent += 1000;
        }
        assertTrue(last.get("limited").getAsBoolean());
        assertEquals(0, last.get("ignoredPoints").getAsInt());
        JsonObject over = draw(token, stroke("x", 0, 1, 1, 2, 2));
        assertEquals(2, over.get("ignoredPoints").getAsInt());
        assertEquals(0, over.get("accepted").getAsInt());
        assertEquals(40, strokes(0).getAsJsonArray("strokes").size());
        // Undo makes room again.
        act("action", "undo_stroke", "token", token);
        assertEquals(1000, draw(token, stroke("y", 0, many)).get("accepted").getAsInt());
    }

    @Test
    public void strokeLimitIgnoresTheRestAndSaysSo() {
        String token = startDrawing();
        List<String> batch = new ArrayList<String>();
        for (int i = 0; i < PictionaryGame.MAX_STROKES; i++) batch.add(stroke("s" + i, 0, 1, 1));
        JsonObject full = draw(token, batch.toArray(new String[0]));
        assertTrue(full.get("limited").getAsBoolean());
        JsonObject over = draw(token, stroke("extra", 0, 1, 1));
        assertEquals(1, over.get("ignoredPoints").getAsInt());
        assertEquals(PictionaryGame.MAX_STROKES, strokes(0).getAsJsonArray("strokes").size());
        // A stroke already started may still finish.
        assertEquals(1, draw(token, stroke("s0", 1, 2, 2)).get("accepted").getAsInt());
    }

    // ----- Teams and bad requests -----

    @Test
    public void defaultTeamsAndRules() {
        JsonArray teams = state().getAsJsonArray("teams");
        assertEquals(2, teams.size());
        assertEquals("Spades", teams.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Hearts", teams.get(1).getAsJsonObject().get("name").getAsString());
        JsonObject added = act("action", "add_team");
        assertEquals("Diamonds", added.getAsJsonArray("teams").get(2).getAsJsonObject().get("name").getAsString());
        refused("action", "add_team");
        act("action", "remove_team", "id", "t3");
        refused("action", "remove_team", "id", "t1");
        refused("action", "remove_team", "id", "nope");
        act("action", "set_team_name", "id", "t1", "name", "  Chakras ");
        assertEquals("Chakras", state().getAsJsonArray("teams").get(0).getAsJsonObject().get("name").getAsString());
        refused("action", "set_team_name", "id", "t1", "name", "hearts");
        refused("action", "set_team_name", "id", "t1", "name", "");
        refused("action", "set_team_name", "id", "t1", "name", "123456789012345678901");
        refused("action", "set_team_name", "id", "t9", "name", "X");
        claim();
        refused("action", "add_team");
        refused("action", "remove_team", "id", "t2");
    }

    @Test
    public void resetGameKeepsTeamsAndCustomPrompts() {
        act("action", "set_team_name", "id", "t1", "name", "Chakras");
        act("action", "add_prompt", "text", "Mine");
        String token = startDrawing();
        draw(token, stroke("a", 0, 1, 1));
        act("action", "got_it", "promptId", promptId());
        act("action", "next_turn");
        startDrawing();
        JsonObject s = act("action", "reset_game");
        assertEquals("ready", s.get("phase").getAsString());
        assertEquals(1, s.get("round").getAsInt());
        assertEquals("t1", s.get("currentTeamId").getAsString());
        assertEquals(0, score(0));
        assertEquals("Chakras", s.getAsJsonArray("teams").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(1, s.get("customPrompts").getAsInt());
        assertEquals(0, strokes(0).getAsJsonArray("strokes").size());
    }

    @Test
    public void resetKillsTheToken() {
        String token = startDrawing();
        act("action", "reset_game");
        refused("action", "draw", "token", token, "strokes", "[]");
    }

    @Test
    public void badRequests() {
        refused("action", "nonsense");
        refused();
        try {
            game.handle("POST", "action", null);
            fail();
        } catch (IllegalArgumentException expected) {
            // 400
        }
        try {
            game.handle("GET", "strokes?since=abc", null);
            fail();
        } catch (IllegalArgumentException expected) {
            // 400
        }
        try {
            game.handle("GET", "nope", null);
            fail();
        } catch (IllegalArgumentException expected) {
            // 400
        }
    }

    @Test
    public void claimWithoutNameAndLongName() {
        JsonObject s = act("action", "claim");
        assertTrue(s.get("drawerName").isJsonNull());
        act("action", "reset_game");
        s = act("action", "claim", "name", "123456789012345678901234567890");
        assertEquals(PictionaryGame.MAX_DRAWER_NAME, s.get("drawerName").getAsString().length());
    }
}
