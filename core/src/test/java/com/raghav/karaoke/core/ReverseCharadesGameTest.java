package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import com.google.gson.Gson;
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

public class ReverseCharadesGameTest {
    private static final int DECK_SIZE = 62;

    private final AtomicLong clock = new AtomicLong(1000000);
    private ReverseCharadesGame game;

    @Before
    public void setUp() {
        game = new ReverseCharadesGame(clock::get, new Random(42));
    }

    // ----- Helpers -----

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
            fail(action + " should have been rejected");
        } catch (IllegalArgumentException expected) {
            // 400 at the HTTP layer.
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        return (Map<String, Object>) game.handle("GET", path, null);
    }

    private Map<String, Object> state() {
        return get("state");
    }

    private Map<String, Object> tv() {
        return get("tv-state");
    }

    private Map<String, Object> promptRoute() {
        return get("prompt");
    }

    private String phase() {
        return (String) state().get("phase");
    }

    private void pass(long ms) {
        clock.addAndGet(ms);
    }

    private void startPerforming() {
        act("start");
        pass(ReverseCharadesGame.COUNTDOWN_MS);
        assertEquals("performing", phase());
    }

    private void nextPerforming() {
        act("end_turn");
        act("next_turn");
        startPerforming();
    }

    private String id() {
        return (String) state().get("promptId");
    }

    private String text() {
        return (String) promptRoute().get("text");
    }

    private void gotIt() {
        act("got_it", "promptId", id());
    }

    private void skip() {
        act("skip", "promptId", id());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> turn() {
        return (Map<String, Object>) state().get("turn");
    }

    private int turnValue(String key) {
        return (Integer) turn().get(key);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> teams() {
        return (List<Map<String, Object>>) state().get("teams");
    }

    private int score(int index) {
        return (Integer) teams().get(index).get("score");
    }

    private String teamId(int index) {
        return (String) teams().get(index).get("id");
    }

    private String guesser() {
        return (String) state().get("guesser");
    }

    private void assertNoSecret(Map<String, Object> view, String promptText) {
        String json = new Gson().toJson(view);
        assertFalse(json, json.contains(promptText));
        assertFalse(view.containsKey("prompt"));
        assertFalse(view.containsKey("text"));
    }

    // ----- The prompt stays secret -----

    @Test
    public void stateAndActionRepliesNeverHoldThePromptOrCategoryDuringALiveTurn() {
        for (boolean onTv : new boolean[] {false, true}) {
            act("reset_game");
            act("set_prompt_on_tv", "on", String.valueOf(onTv));

            assertNoSecret(act("start"), "no prompt yet");
            assertEquals("countdown", phase());
            assertNoSecret(state(), "no prompt yet");

            pass(ReverseCharadesGame.COUNTDOWN_MS);
            assertEquals("performing", phase());
            String text = text();
            assertNoSecret(state(), text);

            Map<String, Object> reply = act("got_it", "promptId", id());
            String nextText = text();
            assertNoSecret(reply, nextText);
            assertNoSecret(reply, text);
            assertNoSecret(act("foul"), nextText);

            Map<String, Object> paused = act("pause");
            assertEquals("paused", paused.get("phase"));
            assertNoSecret(paused, nextText);
            assertNoSecret(state(), nextText);

            assertNoSecret(act("resume"), nextText);
            assertNoSecret(state(), nextText);
            pass(ReverseCharadesGame.COUNTDOWN_MS);
            assertEquals("performing", phase());
            assertNoSecret(state(), nextText);
            act("end_turn");
        }
    }

    @Test
    public void promptIdIsHiddenUnlessTheClockIsRunning() {
        assertNull(state().get("promptId"));
        act("start");
        assertNull(state().get("promptId"));
        pass(ReverseCharadesGame.COUNTDOWN_MS);
        assertNotNull(id());
        act("pause");
        assertNull(state().get("promptId"));
        act("end_turn");
        assertNull(state().get("promptId"));
    }

    @Test
    public void tvStateHasNoPromptByDefaultInAnyPhase() {
        assertEquals(false, state().get("promptOnTv"));
        assertFalse(tv().containsKey("prompt"));
        act("start");
        assertNoSecret(tv(), "no prompt yet");
        pass(ReverseCharadesGame.COUNTDOWN_MS);
        String text = text();
        assertEquals("performing", tv().get("phase"));
        assertNoSecret(tv(), text);
        act("pause");
        assertNoSecret(tv(), text);
        act("resume");
        assertNoSecret(tv(), text);
        act("end_turn");
        assertEquals("summary", tv().get("phase"));
        assertFalse(tv().containsKey("prompt"));
    }

    @Test
    public void promptRouteHasThePromptOnlyWhilePerforming() {
        assertTrue(promptRoute().isEmpty());
        act("start");
        assertTrue(promptRoute().isEmpty());
        pass(ReverseCharadesGame.COUNTDOWN_MS - 1);
        assertTrue(promptRoute().isEmpty());
        pass(1);

        Map<String, Object> p = promptRoute();
        assertEquals(id(), p.get("promptId"));
        assertFalse(((String) p.get("text")).isEmpty());
        assertEquals("Only the id and the movie", 2, p.size());

        act("pause");
        assertTrue(promptRoute().isEmpty());
        act("resume");
        assertEquals(p.get("promptId"), promptRoute().get("promptId"));
        act("end_turn");
        assertEquals("summary", phase());
        assertTrue(promptRoute().isEmpty());
        act("next_turn");
        assertTrue(promptRoute().isEmpty());
    }

    @Test
    public void promptRouteFlipsToTheNextPromptOnACorrectGuess() {
        startPerforming();
        Object first = promptRoute().get("promptId");
        gotIt();
        assertNotEquals(first, promptRoute().get("promptId"));
        assertEquals(id(), promptRoute().get("promptId"));
    }

    @Test
    public void promptRouteGoesEmptyTheMomentTimeIsUp() {
        startPerforming();
        pass(ReverseCharadesGame.TURN_MS);
        assertTrue(promptRoute().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void settingOnShowsThePromptOnTvOnlyWhilePerforming() {
        assertEquals(true, act("set_prompt_on_tv", "on", "true").get("promptOnTv"));
        assertFalse(tv().containsKey("prompt"));
        act("start");
        assertFalse(tv().containsKey("prompt"));
        pass(ReverseCharadesGame.COUNTDOWN_MS);

        Map<String, Object> prompt = (Map<String, Object>) tv().get("prompt");
        assertEquals(id(), prompt.get("id"));
        assertEquals(text(), prompt.get("text"));
        assertEquals("Only the id and the movie", 2, prompt.size());
        assertNoSecret(state(), text());

        act("pause");
        assertFalse(tv().containsKey("prompt"));
        act("resume");
        assertFalse("A guesser who turned around gets time to sit down again", tv().containsKey("prompt"));
        pass(ReverseCharadesGame.COUNTDOWN_MS);
        assertTrue(tv().containsKey("prompt"));
        act("end_turn");
        assertFalse(tv().containsKey("prompt"));
    }

    @Test
    public void promptOnTvIsSetOnlyBetweenTurnsAndSurvivesReset() {
        rejected("set_prompt_on_tv", "on", "maybe");
        rejected("set_prompt_on_tv");
        act("set_prompt_on_tv", "on", "true");
        act("start");
        rejected("set_prompt_on_tv", "on", "false");
        pass(ReverseCharadesGame.COUNTDOWN_MS);
        rejected("set_prompt_on_tv", "on", "false");
        act("pause");
        rejected("set_prompt_on_tv", "on", "false");
        act("end_turn");
        assertEquals(false, act("set_prompt_on_tv", "on", "false").get("promptOnTv"));
        act("set_prompt_on_tv", "on", "true");
        act("next_turn");
        assertEquals(true, act("reset_game").get("promptOnTv"));
        assertEquals(true, state().get("promptOnTv"));
    }

    // ----- Countdown and clock -----

    @Test
    public void startGivesAThreeSecondCountdownBeforeTheFullTurn() {
        assertEquals(3000, ReverseCharadesGame.COUNTDOWN_MS);
        assertEquals("ready", phase());
        assertEquals(60, state().get("secondsLeft"));
        act("start");
        assertEquals("countdown", phase());
        assertEquals(3, state().get("countdownLeft"));
        assertEquals(60, state().get("secondsLeft"));
        pass(1000);
        assertEquals(2, state().get("countdownLeft"));
        pass(1999);
        assertEquals("countdown", phase());
        assertEquals(1, state().get("countdownLeft"));
        pass(1);
        assertEquals("performing", phase());
        assertEquals(0, state().get("countdownLeft"));
        assertEquals(60, state().get("secondsLeft"));
        pass(59999);
        assertEquals("performing", phase());
        pass(1);
        assertEquals("summary", phase());
        assertEquals(0, state().get("secondsLeft"));
    }

    @Test
    public void aLongGapPassesThroughEveryPhaseDue() {
        act("start");
        pass(1000000);
        assertEquals("summary", phase());
    }

    @Test
    public void pauseKeepsTheExactTimeAndHidesThePrompt() {
        startPerforming();
        String id = id();
        pass(17500);
        act("pause");
        assertEquals("paused", phase());
        assertEquals(43, state().get("secondsLeft"));
        assertTrue(promptRoute().isEmpty());
        pass(1000000);
        assertEquals("paused", phase());
        assertEquals(43, state().get("secondsLeft"));

        act("resume");
        assertEquals("performing", phase());
        assertEquals(id, id());
        pass(42499);
        assertEquals("performing", phase());
        assertEquals(1, state().get("secondsLeft"));
        pass(1);
        assertEquals("summary", phase());
    }

    @Test
    public void resumeWithThePromptOnTvCountsDownThenKeepsTheExactTime() {
        act("set_prompt_on_tv", "on", "true");
        startPerforming();
        String id = id();
        pass(17500);
        act("pause");
        act("resume");
        assertEquals("countdown", phase());
        assertEquals(43, state().get("secondsLeft"));
        pass(ReverseCharadesGame.COUNTDOWN_MS);
        assertEquals("performing", phase());
        assertEquals(id, id());
        pass(42499);
        assertEquals("performing", phase());
        pass(1);
        assertEquals("summary", phase());
    }

    @Test
    public void pauseDuringTheFirstCountdownRunsTheCountdownAgain() {
        act("start");
        pass(1000);
        act("pause");
        assertEquals("paused", phase());
        assertEquals(60, state().get("secondsLeft"));
        act("resume");
        assertEquals("countdown", phase());
        assertEquals(3, state().get("countdownLeft"));
        pass(ReverseCharadesGame.COUNTDOWN_MS);
        assertEquals("performing", phase());
        assertEquals(60, state().get("secondsLeft"));
        assertNotNull(id());
    }

    @Test
    public void pauseAndResumeOnlyInTheRightPhase() {
        rejected("pause");
        rejected("resume");
        startPerforming();
        rejected("resume");
        act("pause");
        rejected("pause");
    }

    // ----- Leaving -----

    @Test
    public void leavingPausesAPerformanceAndOnlyAPersonResumesIt() {
        startPerforming();
        String id = id();
        pass(10000);
        game.onLeave();
        assertEquals("paused", phase());
        pass(1000000);
        assertEquals("paused", phase());
        assertEquals(50, state().get("secondsLeft"));
        game.onLeave();
        assertEquals("paused", phase());
        act("resume");
        assertEquals("performing", phase());
        assertEquals(id, id());
        assertEquals(50, state().get("secondsLeft"));
    }

    @Test
    public void leavingPausesACountdown() {
        act("start");
        game.onLeave();
        pass(1000000);
        assertEquals("paused", phase());
        assertTrue(promptRoute().isEmpty());
    }

    @Test
    public void leavingBetweenTurnsChangesNothing() {
        game.onLeave();
        assertEquals("ready", phase());
        startPerforming();
        act("end_turn");
        game.onLeave();
        assertEquals("summary", phase());
    }

    @Test
    public void leavingAfterTimeRanOutEndsInSummaryNotPause() {
        startPerforming();
        pass(ReverseCharadesGame.TURN_MS);
        game.onLeave();
        assertEquals("summary", phase());
    }

    // ----- Taps -----

    @Test
    public void duplicateOrStalePromptIdScoresOnce() {
        startPerforming();
        String first = id();
        act("got_it", "promptId", first);
        Map<String, Object> reply = act("got_it", "promptId", first);
        assertEquals("performing", reply.get("phase"));
        act("skip", "promptId", first);
        act("got_it", "promptId", "p9999");
        assertEquals(1, turnValue("correct"));
        assertEquals(0, turnValue("skips"));
        assertEquals(1, score(0));
    }

    @Test
    public void tapsAfterTimeUpAreIgnoredByTheServerClock() {
        startPerforming();
        String id = id();
        pass(ReverseCharadesGame.TURN_MS);
        Map<String, Object> reply = act("got_it", "promptId", id);
        assertEquals("summary", reply.get("phase"));
        act("skip", "promptId", id);
        assertEquals(0, turnValue("correct"));
        assertEquals(0, turnValue("skips"));
        assertEquals(0, score(0));
    }

    @Test
    public void tapsDuringAPauseAreIgnored() {
        startPerforming();
        String id = id();
        act("pause");
        act("got_it", "promptId", id);
        act("skip", "promptId", id);
        assertEquals(0, turnValue("correct"));
        assertEquals(0, turnValue("skips"));
    }

    @Test
    public void tapsNeedAPromptIdAndATurn() {
        rejected("got_it", "promptId", "p1");
        startPerforming();
        rejected("got_it");
        rejected("skip");
        rejected("got_it", "promptId", "");
    }

    // ----- Scoring -----

    @Test
    public void twoFreeSkipsThenMinusOne() {
        startPerforming();
        gotIt();
        gotIt();
        gotIt();
        assertEquals(3, score(0));
        assertEquals(2, turnValue("freeSkipsLeft"));
        skip();
        assertEquals(1, turnValue("freeSkipsLeft"));
        skip();
        assertEquals(0, turnValue("freeSkipsLeft"));
        assertEquals(3, score(0));
        skip();
        assertEquals(2, score(0));
        skip();
        assertEquals(1, score(0));
        assertEquals(4, turnValue("skips"));
        assertEquals(0, turnValue("freeSkipsLeft"));
    }

    @Test
    public void everyCorrectGuessIsOnePointForTheWholeDeck() {
        startPerforming();
        for (int i = 0; i < DECK_SIZE; i++) {
            int before = score(0);
            int streak = turnValue("streak");
            gotIt();
            int bonus = (streak + 1) % ReverseCharadesGame.BONUS_EVERY == 0 ? ReverseCharadesGame.BONUS_POINTS : 0;
            assertEquals(before + 1 + bonus, score(0));
        }
    }

    @Test
    public void categoriesAreGone() {
        rejected("set_categories", "categories", "Movies");
        assertFalse(state().containsKey("enabledCategories"));
        assertFalse(state().containsKey("allCategories"));
        startPerforming();
        assertFalse(promptRoute().containsKey("category"));
        assertFalse(promptRoute().containsKey("hard"));
    }

    @Test
    public void theDeckHoldsTheFamousMoviesWithNoDuplicates() {
        startPerforming();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < DECK_SIZE; i++) {
            assertTrue(text() + " is in the deck twice", seen.add(text().toLowerCase()));
            gotIt();
        }
        for (String movie : new String[] {"sholay", "dilwale dulhania le jayenge", "gadar", "dangal", "rrr", "lagaan", "3 idiots"}) {
        for (String hollywood : new String[] {"titanic", "jurassic park", "spider-man", "jaws"}) {
            assertFalse("Bollywood only: " + hollywood, seen.contains(hollywood));
        }
            assertTrue(movie, seen.contains(movie));
        }
    }

    @Test
    public void bonusAtFiveAndTenInARow() {
        startPerforming();
        for (int i = 0; i < 4; i++) gotIt();
        assertEquals(4, score(0));
        assertEquals(0L, state().get("bonusAt"));
        gotIt();
        assertEquals(8, score(0));
        long first = (Long) state().get("bonusAt");
        assertTrue(first > 0);
        for (int i = 0; i < 4; i++) gotIt();
        assertEquals(12, score(0));
        assertEquals(first, state().get("bonusAt"));
        gotIt();
        assertEquals(16, score(0));
        assertEquals(10, turnValue("streak"));
        assertTrue((Long) state().get("bonusAt") > first);
    }

    @Test
    public void bonusChangesEvenWithinTheSameClockTick() {
        startPerforming();
        for (int i = 0; i < 5; i++) gotIt();
        long first = (Long) state().get("bonusAt");
        for (int i = 0; i < 5; i++) gotIt();
        assertNotEquals(first, ((Long) state().get("bonusAt")).longValue());
    }

    @Test
    public void aSkipResetsTheStreakAndAFoulDoesNot() {
        startPerforming();
        for (int i = 0; i < 4; i++) gotIt();
        skip();
        assertEquals(0, turnValue("streak"));
        gotIt();
        assertEquals(1, turnValue("streak"));
        assertEquals(0L, state().get("bonusAt"));

        for (int i = 0; i < 3; i++) gotIt();
        assertEquals(4, turnValue("streak"));
        assertEquals(8, score(0));
        act("foul");
        assertEquals(4, turnValue("streak"));
        assertEquals(7, score(0));
        assertEquals(1, turnValue("fouls"));
        gotIt();
        assertEquals(5, turnValue("streak"));
        assertEquals(11, score(0));
    }

    @Test
    public void scoresNeverGoBelowZero() {
        startPerforming();
        act("foul");
        assertEquals(0, score(0));
        assertEquals(0, turnValue("points"));
        skip();
        skip();
        skip();
        assertEquals(0, score(0));
        gotIt();
        assertEquals(1, score(0));
    }

    @Test
    public void foulNeedsARunningClock() {
        rejected("foul");
        startPerforming();
        act("pause");
        rejected("foul");
    }

    @Test
    public void streakDoesNotCarryIntoTheNextTurn() {
        startPerforming();
        for (int i = 0; i < 4; i++) gotIt();
        nextPerforming();
        assertEquals(0, turnValue("streak"));
        gotIt();
        assertEquals(1, score(1));
        assertEquals(0L, state().get("bonusAt"));
    }

    // ----- Undo -----

    @Test
    public void undoAGotItBringsThePromptBackWithPointsAndStreak() {
        startPerforming();
        String first = id();
        gotIt();
        String second = id();
        assertNotEquals(first, second);
        act("undo");
        assertEquals(first, id());
        assertEquals(0, turnValue("correct"));
        assertEquals(0, turnValue("streak"));
        assertEquals(0, turnValue("points"));
        assertEquals(0, score(0));
    }

    @Test
    public void undoAfterTheNextPromptHasBeenUpAWhileKeepsItButTakesThePointsBack() {
        startPerforming();
        String firstText = text();
        gotIt();
        String second = id();
        pass(ReverseCharadesGame.UNDO_BRING_BACK_MS);
        act("undo");
        assertEquals(second, id());
        assertEquals(0, score(0));
        assertEquals(0, turnValue("correct"));
        act("end_turn");
        assertEquals("unguessed", outcomeOf(firstText));
    }

    @Test
    public void undoWindowIgnoresTimeSpentPaused() {
        startPerforming();
        String first = id();
        gotIt();
        act("pause");
        pass(ReverseCharadesGame.UNDO_BRING_BACK_MS * 10);
        act("undo");
        act("resume");
        assertEquals(first, id());
    }

    @Test
    public void undoAFreeSkipGivesItBackAndRestoresTheStreak() {
        startPerforming();
        gotIt();
        gotIt();
        String id = id();
        skip();
        assertEquals(0, turnValue("streak"));
        act("undo");
        assertEquals(id, id());
        assertEquals(2, turnValue("streak"));
        assertEquals(0, turnValue("skips"));
        assertEquals(2, turnValue("freeSkipsLeft"));
    }

    @Test
    public void undoAPaidSkipRefundsThePoint() {
        startPerforming();
        gotIt();
        gotIt();
        skip();
        skip();
        skip();
        assertEquals(1, score(0));
        act("undo");
        assertEquals(2, score(0));
        assertEquals(2, turnValue("skips"));
    }

    @Test
    public void undoAFoulKeepsThePromptAndRestoresThePoint() {
        startPerforming();
        gotIt();
        String id = id();
        act("foul");
        assertEquals(0, score(0));
        act("undo");
        assertEquals(1, score(0));
        assertEquals(0, turnValue("fouls"));
        assertEquals(id, id());
    }

    @Test
    public void undoAFoulThatHitTheFloorGivesNothingExtra() {
        startPerforming();
        act("foul");
        act("undo");
        assertEquals(0, score(0));
        assertEquals(0, turnValue("points"));
    }

    @Test
    public void undoABonusTakesTheBonusBackAndDoesNotFireTheTvAgain() {
        startPerforming();
        for (int i = 0; i < 5; i++) gotIt();
        assertEquals(8, score(0));
        Object bonusAt = state().get("bonusAt");
        act("undo");
        assertEquals(4, score(0));
        assertEquals(4, turnValue("streak"));
        assertEquals(bonusAt, state().get("bonusAt"));
        gotIt();
        assertEquals(8, score(0));
        assertTrue((Long) state().get("bonusAt") > (Long) bonusAt);
    }

    @Test
    public void undoWalksBackSeveralSteps() {
        startPerforming();
        String a = id();
        gotIt();
        String b = id();
        skip();
        act("foul");
        act("undo");
        assertEquals(0, turnValue("fouls"));
        act("undo");
        assertEquals(b, id());
        assertEquals(0, turnValue("skips"));
        act("undo");
        assertEquals(a, id());
        assertEquals(0, score(0));
        rejected("undo");
    }

    @Test
    public void aPromptPutBackByUndoStaysInTheDeck() {
        startPerforming();
        Set<String> seen = new HashSet<String>();
        seen.add(id());
        gotIt();
        act("undo");
        for (int i = 0; i < DECK_SIZE - 1; i++) {
            gotIt();
            seen.add(id());
            assertNull("Still unseen prompts left", state().get("deckNote"));
        }
        assertEquals("All came up, with the undone draw not lost", DECK_SIZE, seen.size());
    }

    @Test
    public void undoWorksInTheSummaryForALastSecondMistake() {
        startPerforming();
        String firstText = text();
        gotIt();
        pass(ReverseCharadesGame.TURN_MS);
        assertEquals("summary", phase());
        assertEquals(1, score(0));
        act("undo");
        assertEquals("summary", phase());
        assertEquals(0, score(0));
        assertEquals("unguessed", outcomeOf(firstText));
    }

    @Test
    public void undoNeedsSomethingToUndo() {
        rejected("undo");
        startPerforming();
        rejected("undo");
        gotIt();
        act("end_turn");
        act("next_turn");
        rejected("undo");
        startPerforming();
        rejected("undo");
        assertEquals("Undo cannot reach the last team's turn", 1, score(0));
    }

    // ----- The deck -----

    @Test
    public void noPromptRepeatsAcrossTeamsUntilTheWholeDeckIsUsed() {
        startPerforming();
        Set<String> seen = new HashSet<String>();
        Set<Integer> teamsPlaying = new HashSet<Integer>();
        int shown = 0;
        while (seen.size() < DECK_SIZE) {
            assertTrue(text() + " repeated", seen.add(text()));
            assertNull(state().get("deckNote"));
            shown++;
            teamsPlaying.add((Integer) state().get("round"));
            if (shown == DECK_SIZE) break;
            if (shown % 7 == 0) nextPerforming();
            else gotIt();
        }
        assertEquals(DECK_SIZE, seen.size());
        assertTrue("Played over several rounds", teamsPlaying.size() > 1);
        gotIt();
        assertEquals(ReverseCharadesGame.NOTE_REUSE_ALL, state().get("deckNote"));
    }

    @Test
    public void whenTheDeckRunsOutSkippedPromptsComeBackFirst() {
        startPerforming();
        String skippedText = text();
        skip();
        for (int i = 0; i < DECK_SIZE - 1; i++) {
            assertNull(state().get("deckNote"));
            assertNotEquals(skippedText, text());
            gotIt();
        }
        assertEquals(skippedText, text());
        assertEquals(ReverseCharadesGame.NOTE_REUSE_SKIPPED, state().get("deckNote"));
        gotIt();
        assertEquals(ReverseCharadesGame.NOTE_REUSE_ALL, state().get("deckNote"));
    }

    @Test
    public void aDeckNoteNeverGivesAwayAPrompt() {
        startPerforming();
        for (int i = 0; i < DECK_SIZE; i++) gotIt();
        assertNoSecret(state(), text());
    }

    @Test
    public void customPromptsJoinTheDeckAndNeverShowOnThePhoneDuringATurn() {
        act("add_prompt", "text", "  Papa dancing  ");
        rejected("add_prompt", "text", "papa DANCING");
        rejected("add_prompt", "text", "sholay");
        rejected("add_prompt", "text", "   ");
        rejected("add_prompt");
        char[] tooLong = new char[ReverseCharadesGame.MAX_PROMPT + 1];
        Arrays.fill(tooLong, 'x');
        rejected("add_prompt", "text", new String(tooLong));
        act("add_prompt", "text", new String(tooLong, 0, ReverseCharadesGame.MAX_PROMPT));

        startPerforming();
        assertNoSecret(state(), "Papa dancing");
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < DECK_SIZE + 2; i++) {
            seen.add(text());
            assertNoSecret(state(), "Papa dancing");
            gotIt();
        }
        assertTrue(seen.contains("Papa dancing"));
        assertEquals(DECK_SIZE + 2, seen.size());
    }

    // ----- Turns, teams and members -----

    @Test
    public void defaultTeamsAndTheShapeOfState() {
        Map<String, Object> s = state();
        assertEquals(2, teams().size());
        assertEquals("Spades", teams().get(0).get("name"));
        assertEquals("Hearts", teams().get(1).get("name"));
        assertEquals(teamId(0), s.get("currentTeamId"));
        assertEquals("Hearts", s.get("refereeTeamName"));
        assertNull(s.get("guesser"));
        assertEquals(1, s.get("round"));
        assertEquals(0, turnValue("correct"));
        assertEquals(2, turnValue("freeSkipsLeft"));
        for (String key : new String[] {"phase", "promptId", "secondsLeft", "countdownLeft", "teams", "bonusAt", "deckNote", "serverNow", "promptOnTv"}) {
            assertTrue(key, s.containsKey(key));
        }
        assertEquals(clock.get(), s.get("serverNow"));
        assertFalse(s.containsKey("results"));
    }

    @Test
    public void nextTurnMovesTeamsAndRaisesTheRoundWhenAllHavePlayed() {
        startPerforming();
        gotIt();
        act("end_turn");
        act("next_turn");
        assertEquals("ready", phase());
        assertEquals(teamId(1), state().get("currentTeamId"));
        assertEquals("Spades", state().get("refereeTeamName"));
        assertEquals(1, state().get("round"));
        assertEquals(0, turnValue("correct"));
        assertEquals(1, score(0));
        startPerforming();
        act("end_turn");
        act("next_turn");
        assertEquals(teamId(0), state().get("currentTeamId"));
        assertEquals(2, state().get("round"));
    }

    @Test
    public void guessersRotateWithinEachTeam() {
        act("add_member", "teamId", teamId(0), "name", "Asha");
        act("add_member", "teamId", teamId(0), "name", "Bela");
        act("add_member", "teamId", teamId(1), "name", "Chirag");
        String[] order = {"Asha", "Chirag", "Bela", "Chirag", "Asha"};
        for (String expected : order) {
            assertEquals(expected, guesser());
            startPerforming();
            act("end_turn");
            act("next_turn");
        }
    }

    @Test
    public void aSkippedTurnChangeDoesNotMoveTheGuesser() {
        act("add_member", "teamId", teamId(0), "name", "Asha");
        act("add_member", "teamId", teamId(0), "name", "Bela");
        startPerforming();
        assertEquals("Asha", guesser());
        act("pause");
        assertEquals("Asha", guesser());
    }

    @Test
    public void membersAreValidatedAndRemovalKeepsTheRotationFair() {
        String t = teamId(0);
        rejected("add_member", "teamId", "nope", "name", "X");
        rejected("add_member", "teamId", t, "name", "  ");
        rejected("add_member", "teamId", t);
        act("add_member", "teamId", t, "name", " Asha ");
        rejected("add_member", "teamId", t, "name", "asha");
        act("add_member", "teamId", t, "name", "Bela");
        act("add_member", "teamId", t, "name", "Chirag");
        assertEquals(Arrays.asList("Asha", "Bela", "Chirag"), teams().get(0).get("members"));

        // Bela is up next, so removing someone before her must not change that.
        startPerforming();
        act("end_turn");
        act("next_turn");
        startPerforming();
        act("end_turn");
        act("next_turn");
        assertEquals("Bela", guesser());
        act("remove_member", "teamId", t, "name", "Asha");
        assertEquals("Bela", guesser());

        // Removing the guesser passes it to whoever is next, by index as well as by name.
        act("remove_member", "teamId", t, "index", "0");
        assertEquals("Chirag", guesser());
        act("remove_member", "teamId", t, "index", "0");
        assertNull(guesser());
        rejected("remove_member", "teamId", t, "index", "0");
        rejected("remove_member", "teamId", t, "index", "x");
        rejected("remove_member", "teamId", t, "name", "Nobody");
        rejected("remove_member", "teamId", t);
    }

    @Test
    public void teamLimitsAreTwoToThreeAndOnlyBetweenTurns() {
        rejected("remove_team", "id", teamId(0));
        act("add_team");
        assertEquals(3, teams().size());
        assertEquals("Diamonds", teams().get(2).get("name"));
        rejected("add_team");
        act("start");
        rejected("remove_team", "id", teamId(2));
        pass(ReverseCharadesGame.COUNTDOWN_MS);
        rejected("remove_team", "id", teamId(2));
        rejected("remove_team", "id", "nope");
        act("end_turn");
        rejected("remove_team", "id", teamId(0));
        act("remove_team", "id", teamId(2));
        assertEquals(2, teams().size());
        act("next_turn");
        act("add_team");
        act("start");
        rejected("add_team");
    }

    @Test
    public void aThirdTeamPlaysItsTurnBeforeTheRoundRises() {
        act("add_team");
        for (int i = 0; i < 3; i++) {
            assertEquals(1, state().get("round"));
            assertEquals(teamId(i), state().get("currentTeamId"));
            startPerforming();
            act("end_turn");
            act("next_turn");
        }
        assertEquals(2, state().get("round"));
        assertEquals(teamId(0), state().get("currentTeamId"));
    }

    @Test
    public void removingTheTeamWhoseTurnIsNextMovesOnCorrectly() {
        act("add_team");
        String first = teamId(0), second = teamId(1), third = teamId(2);
        startPerforming();
        act("end_turn");
        act("next_turn");
        startPerforming();
        act("end_turn");
        act("next_turn");
        assertEquals(third, state().get("currentTeamId"));
        act("remove_team", "id", third);
        assertEquals("Everyone left has played", 2, state().get("round"));
        assertEquals(first, state().get("currentTeamId"));
        assertEquals(second, teamId(1));
    }

    @Test
    public void removingAnEarlierTeamKeepsTheCurrentTeam() {
        act("add_team");
        String second = teamId(1);
        startPerforming();
        act("end_turn");
        act("next_turn");
        assertEquals(second, state().get("currentTeamId"));
        act("remove_team", "id", teamId(0));
        assertEquals(second, state().get("currentTeamId"));
    }

    @Test
    public void teamNamesAreValidated() {
        String t = teamId(0);
        act("set_team_name", "id", t, "name", "  Diyas  ");
        assertEquals("Diyas", teams().get(0).get("name"));
        act("set_team_name", "id", t, "name", "diyas");
        assertEquals("diyas", teams().get(0).get("name"));
        rejected("set_team_name", "id", t, "name", "hearts");
        rejected("set_team_name", "id", t, "name", "   ");
        rejected("set_team_name", "id", t);
        rejected("set_team_name", "id", "nope", "name", "X");
        rejected("set_team_name", "id", t, "name", "123456789012345678901");
        act("set_team_name", "id", t, "name", "12345678901234567890");
        assertEquals("12345678901234567890", teams().get(0).get("name"));
        act("set_team_name", "id", teamId(1), "name", "Hearts 2");
        assertEquals("Hearts 2", state().get("refereeTeamName"));
    }

    @Test
    public void addedTeamNeverClashesWithARename() {
        act("set_team_name", "id", teamId(0), "name", "Diamonds");
        act("add_team");
        assertEquals("Spades", teams().get(2).get("name"));
    }

    // ----- Summary and reset -----

    @Test
    @SuppressWarnings("unchecked")
    public void summaryRevealsEveryPromptWithItsOutcome() {
        startPerforming();
        String correct = text();
        gotIt();
        String skipped = text();
        skip();
        String last = text();
        pass(ReverseCharadesGame.TURN_MS);
        assertEquals("correct", outcomeOf(correct));
        assertEquals("skipped", outcomeOf(skipped));
        assertEquals("unguessed", outcomeOf(last));
        assertEquals(3, ((List<?>) state().get("results")).size());
        assertEquals(3, ((List<?>) tv().get("results")).size());
        Map<String, Object> line = ((List<Map<String, Object>>) tv().get("results")).get(0);
        assertEquals(correct, line.get("text"));
        assertFalse(line.containsKey("category"));
    }

    @Test
    public void endTurnStopsEarlyFromPerformingOrPausedOnly() {
        rejected("end_turn");
        act("start");
        rejected("end_turn");
        pass(ReverseCharadesGame.COUNTDOWN_MS);
        pass(20000);
        act("end_turn");
        assertEquals("summary", phase());
        assertEquals(40, state().get("secondsLeft"));
        rejected("end_turn");
        act("next_turn");
        assertFalse(state().containsKey("results"));
        startPerforming();
        act("pause");
        act("end_turn");
        assertEquals("summary", phase());
    }

    @Test
    public void endingAfterAPauseInTheFirstCountdownLeavesAnEmptySummary() {
        act("start");
        act("pause");
        act("end_turn");
        assertEquals("summary", phase());
        assertTrue(((List<?>) state().get("results")).isEmpty());
    }

    @Test
    public void resetClearsScoresAndDeckButKeepsTeamsMembersAndCustomPrompts() {
        act("set_team_name", "id", teamId(0), "name", "Diyas");
        act("add_member", "teamId", teamId(0), "name", "Asha");
        act("add_member", "teamId", teamId(0), "name", "Bela");
        act("add_team");
        act("add_prompt", "text", "Papa dancing");
        startPerforming();
        Set<String> first = new HashSet<String>();
        for (int i = 0; i < DECK_SIZE + 1; i++) {
            first.add(text());
            gotIt();
        }
        act("end_turn");
        act("next_turn");

        Map<String, Object> reset = act("reset_game");
        assertEquals("ready", reset.get("phase"));
        assertEquals(1, reset.get("round"));
        assertEquals(0, score(0));
        assertEquals(teamId(0), reset.get("currentTeamId"));
        assertEquals("Diyas", teams().get(0).get("name"));
        assertEquals(3, teams().size());
        assertEquals("Asha", guesser());
        assertNull(reset.get("deckNote"));

        rejected("add_prompt", "text", "Papa dancing");
        startPerforming();
        Set<String> second = new HashSet<String>();
        for (int i = 0; i < DECK_SIZE + 1; i++) {
            assertNull("The whole deck is fresh again", state().get("deckNote"));
            second.add(text());
            gotIt();
        }
        assertEquals("The whole deck is fresh again", first, second);
    }

    @Test
    public void resetWorksMidTurn() {
        startPerforming();
        gotIt();
        act("reset_game");
        assertEquals("ready", phase());
        assertTrue(promptRoute().isEmpty());
        assertEquals(0, score(0));
        rejected("undo");
    }

    // ----- Bad requests -----

    @Test
    public void invalidActionsAreRejected() {
        rejected("dance");
        rejected("next_turn");
        startPerforming();
        rejected("start");
        rejected("next_turn");
        try {
            game.handle("POST", "action", new HashMap<String, String>());
            fail("Missing action");
        } catch (IllegalArgumentException expected) {
            // 400.
        }
        try {
            game.handle("POST", "action", null);
            fail("Null body");
        } catch (IllegalArgumentException expected) {
            // 400.
        }
        for (String[] request : new String[][] {{"GET", "nope"}, {"POST", "state"}, {"POST", "prompt"}, {"GET", "action"}, {"DELETE", "state"}}) {
            try {
                game.handle(request[0], request[1], new HashMap<String, String>());
                fail(request[0] + " " + request[1]);
            } catch (IllegalArgumentException expected) {
                // 400.
            }
        }
    }

    @Test
    public void promptIdsSayNothingAboutTheDeckOrder() {
        List<String> firstPrompts = new ArrayList<String>();
        for (long seed = 0; seed < 5; seed++) {
            game = new ReverseCharadesGame(clock::get, new Random(seed));
            startPerforming();
            firstPrompts.add(id() + text());
        }
        assertTrue(new HashSet<String>(firstPrompts).size() > 1);
    }

    @SuppressWarnings("unchecked")
    private String outcomeOf(String promptText) {
        for (Map<String, Object> r : (List<Map<String, Object>>) state().get("results")) {
            if (promptText.equals(r.get("text"))) return (String) r.get("outcome");
        }
        return null;
    }
}
