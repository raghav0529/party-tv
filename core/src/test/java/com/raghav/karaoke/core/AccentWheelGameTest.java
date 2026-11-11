package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

public class AccentWheelGameTest {
    private final AtomicLong clock = new AtomicLong(1000000);
    private AccentWheelGame game;

    @Before
    public void setUp() {
        game = new AccentWheelGame(clock::get, new Random(42));
    }

    private static Map<String, String> fields(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> act(String action, String... kv) {
        Map<String, String> body = fields(kv);
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

    private Map<String, Object> state() {
        return game.state();
    }

    private String phase() {
        return (String) state().get("phase");
    }

    private void pass(long ms) {
        clock.addAndGet(ms);
    }

    private void land() {
        pass(AccentWheelGame.SPIN_MS);
    }

    private void toScoring() {
        act("spin");
        land();
        act("start_timer");
        act("stop_timer");
    }

    private void playRound() {
        toScoring();
        act("score", "points", "1");
    }

    @SuppressWarnings("unchecked")
    private static String field(Object slice, String key) {
        return (String) ((Map<String, Object>) slice).get(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> player(String id) {
        for (Map<String, Object> p : (List<Map<String, Object>>) state().get("players")) {
            if (id.equals(p.get("id"))) return p;
        }
        return null;
    }

    private int score(String id) {
        return (Integer) player(id).get("score");
    }

    private String current() {
        return (String) state().get("currentPlayerId");
    }

    // ----- Phases and time -----

    @Test
    public void startsReadyWithNothingChosen() {
        Map<String, Object> s = state();
        assertEquals("ready", s.get("phase"));
        assertEquals(0, s.get("spinId"));
        assertEquals(1, s.get("round"));
        assertNull(s.get("voice"));
        assertNull(s.get("task"));
        assertNull(s.get("currentPlayerId"));
        assertEquals(45, s.get("timerSeconds"));
        assertEquals(45, s.get("secondsLeft"));
        assertEquals(false, s.get("speedRound"));
        assertNull(s.get("lastAward"));
        assertEquals(clock.get(), s.get("serverNow"));
        assertEquals(21, ((List<?>) s.get("voices")).size());
        assertEquals(12, ((List<?>) s.get("tasks")).size());
    }

    @Test
    public void spinningBecomesLandedExactlyAfterSpinMs() {
        Map<String, Object> s = act("spin");
        assertEquals("spinning", s.get("phase"));
        assertEquals(1, s.get("spinId"));
        assertEquals(AccentWheelGame.SPIN_MS, s.get("spinMs"));
        assertEquals(AccentWheelGame.SPIN_MS, s.get("phaseMsLeft"));
        assertNotNull(s.get("voice"));
        assertNotNull(s.get("task"));
        pass(AccentWheelGame.SPIN_MS - 1);
        assertEquals("spinning", phase());
        assertEquals(1L, state().get("phaseMsLeft"));
        pass(1);
        assertEquals("landed", phase());
        assertEquals(0L, state().get("phaseMsLeft"));
    }

    @Test
    public void timerRunsThenScoringWhenItRunsOut() {
        act("spin");
        land();
        Map<String, Object> s = act("start_timer");
        assertEquals("performing", s.get("phase"));
        assertEquals(45, s.get("timerSeconds"));
        assertEquals(45, s.get("secondsLeft"));
        assertEquals(45000L, s.get("phaseMsLeft"));
        pass(1500);
        assertEquals(44, state().get("secondsLeft"));
        pass(43499);
        assertEquals("performing", phase());
        assertEquals(1, state().get("secondsLeft"));
        pass(1);
        s = state();
        assertEquals("scoring", s.get("phase"));
        assertEquals(0, s.get("secondsLeft"));
    }

    @Test
    public void stopTimerGoesToScoringAndFreezesTheClock() {
        act("spin");
        land();
        act("start_timer");
        pass(10000);
        Map<String, Object> s = act("stop_timer");
        assertEquals("scoring", s.get("phase"));
        assertEquals(35, s.get("secondsLeft"));
        pass(100000);
        assertEquals(35, state().get("secondsLeft"));
        assertEquals("scoring", phase());
    }

    @Test
    public void spinAgainFromLandedIsAllowedAndRaisesSpinId() {
        act("spin");
        land();
        Map<String, Object> s = act("spin");
        assertEquals("spinning", s.get("phase"));
        assertEquals(2, s.get("spinId"));
    }

    @Test
    public void actionsInTheWrongPhaseAreRejected() {
        rejected("start_timer");
        rejected("stop_timer");
        rejected("score", "points", "3");
        act("spin");
        rejected("spin");
        rejected("start_timer");
        land();
        rejected("stop_timer");
        act("start_timer");
        rejected("spin");
        rejected("start_timer");
        rejected("score", "points", "3");
        act("stop_timer");
        rejected("spin");
        rejected("stop_timer");
    }

    @Test
    public void anActionSeesTheTimeBasedPhaseWithoutAStateRead() {
        act("spin");
        land();
        assertEquals("performing", act("start_timer").get("phase"));
        pass(45000);
        assertEquals("ready", act("score", "points", "2").get("phase"));
    }

    // ----- Speed Round -----

    @Test
    public void speedRoundStartsByItselfWith15SecondTimer() {
        act("set_speed", "on", "true");
        Map<String, Object> s = state();
        assertEquals(true, s.get("speedRound"));
        assertEquals(15, s.get("timerSeconds"));
        act("spin");
        land();
        s = state();
        assertEquals("performing", s.get("phase"));
        assertEquals(15, s.get("timerSeconds"));
        assertEquals(15, s.get("secondsLeft"));
        pass(14999);
        assertEquals("performing", phase());
        pass(1);
        assertEquals("scoring", phase());
    }

    @Test
    public void speedTimerCountsFromTheLandingNotFromTheNextRead() {
        act("set_speed", "on", "true");
        act("spin");
        pass(AccentWheelGame.SPIN_MS + 5000);
        assertEquals(10, state().get("secondsLeft"));
        act("stop_timer");
        act("score", "points", "1");
        act("spin");
        pass(AccentWheelGame.SPIN_MS + 60000); // Nobody polled for a whole minute.
        assertEquals("scoring", phase());
    }

    @Test
    public void speedRoundOffGivesNormalTimerAgainAndBadValueIsRejected() {
        act("set_speed", "on", "true");
        act("set_speed", "on", "false");
        act("spin");
        land();
        assertEquals("landed", phase());
        assertEquals(45, act("start_timer").get("timerSeconds"));
        rejected("set_speed", "on", "maybe");
        rejected("set_speed");
    }

    // ----- Spin rules -----

    @Test
    public void firstThreeRoundsAreAbsurdOnlyWhateverTheRandomSeed() {
        for (int seed = 0; seed < 50; seed++) {
            game = new AccentWheelGame(clock::get, new Random(seed));
            for (int round = 1; round <= 3; round++) {
                Map<String, Object> s = act("spin");
                assertEquals(round, s.get("round"));
                assertEquals("Absurd", field(s.get("voice"), "group"));
                land();
                act("start_timer");
                act("stop_timer");
                act("score", "points", "1");
            }
        }
    }

    @Test
    public void fourthRoundOnwardUsesAnyVoice() {
        Set<String> groups = new HashSet<String>();
        for (int i = 0; i < 3; i++) playRound();
        assertEquals(4, state().get("round"));
        for (int i = 0; i < 14; i++) {
            groups.add(field(act("spin").get("voice"), "group"));
            land();
        }
        assertTrue(groups.contains("Characters"));
        assertTrue(groups.contains("Professions"));
        assertFalse("All 7 Absurd voices can only have been left over", groups.contains("Absurd") && groups.size() == 1);
    }

    @Test
    public void respinningManyTimesInEarlyRoundsStaysAbsurdWithoutRepeatingBackToBack() {
        String last = null;
        for (int i = 0; i < 40; i++) {
            Map<String, Object> s = act("spin");
            assertEquals("Absurd", field(s.get("voice"), "group"));
            assertNotEquals(last, field(s.get("voice"), "id"));
            last = field(s.get("voice"), "id");
            land();
        }
    }

    @Test
    public void noRepeatOnEitherWheelUntilEverySliceIsUsed() {
        for (int i = 0; i < 3; i++) playRound();
        // Rounds 1 to 3 used 3 voices and 3 tasks. Start a clean game past the Absurd rounds instead.
        game = new AccentWheelGame(clock::get, new Random(7));
        for (int i = 0; i < 3; i++) playRound();
        Set<String> seenVoices = new HashSet<String>();
        Set<String> seenTasks = new HashSet<String>();
        // 12 tasks, and 3 were used in the opening rounds.
        for (int i = 0; i < 9; i++) {
            Map<String, Object> s = act("spin");
            assertTrue(seenVoices.add(field(s.get("voice"), "id")));
            assertTrue(seenTasks.add(field(s.get("task"), "id")));
            land();
        }
        // 21 voices, 3 used in the opening rounds and 9 just now: 9 more before the wheel may repeat.
        for (int i = 0; i < 9; i++) {
            assertTrue(seenVoices.add(field(act("spin").get("voice"), "id")));
            land();
        }
        assertEquals(18, seenVoices.size());
    }

    @Test
    public void afterExhaustionTheWheelStartsOverWithoutTheSameSliceTwiceInARow() {
        for (int i = 0; i < 3; i++) playRound();
        String last = null;
        for (int i = 0; i < 60; i++) {
            String id = field(act("spin").get("task"), "id");
            assertNotEquals(last, id);
            last = id;
            land();
        }
    }

    @Test
    public void customSliceEntersTheRotationAsUnused() {
        for (int i = 0; i < 3; i++) playRound();
        // Use up all but the slices still left, then add one: it must arrive before anything repeats.
        for (int i = 0; i < 9 - 1; i++) {
            act("spin");
            land();
        }
        // 3 + 8 tasks used, 1 unused. Adding a custom one makes 2 unused.
        Map<String, Object> s = act("add_slice", "wheel", "task", "text", "  Sing the national anthem  ");
        List<?> tasks = (List<?>) s.get("tasks");
        assertEquals(13, tasks.size());
        assertEquals("Sing the national anthem", field(tasks.get(12), "text"));
        Set<String> nextTwo = new HashSet<String>();
        for (int i = 0; i < 2; i++) {
            nextTwo.add(field(act("spin").get("task"), "text"));
            land();
        }
        assertEquals(2, nextTwo.size());
        assertTrue(nextTwo.contains("Sing the national anthem"));
    }

    @Test
    public void customVoiceHasCustomGroupAndWaitsForRoundFour() {
        Map<String, Object> s = act("add_slice", "wheel", "voice", "text", "Mamaji on the phone");
        List<?> voices = (List<?>) s.get("voices");
        Object added = voices.get(voices.size() - 1);
        assertEquals("Custom", field(added, "group"));
        for (int i = 0; i < 12; i++) {
            assertEquals("Absurd", field(act("spin").get("voice"), "group"));
            land();
        }
        for (int i = 0; i < 3; i++) {
            act("spin");
            land();
            act("start_timer");
            act("stop_timer");
            act("score", "points", "1");
        }
        boolean seen = false;
        for (int i = 0; i < 25 && !seen; i++) {
            seen = "Mamaji on the phone".equals(field(act("spin").get("voice"), "text"));
            land();
        }
        assertTrue(seen);
    }

    @Test
    public void addSliceRejectsBadValues() {
        rejected("add_slice", "wheel", "voice", "text", "   ");
        rejected("add_slice", "wheel", "voice");
        rejected("add_slice", "wheel", "colour", "text", "Blue");
        rejected("add_slice", "text", "Blue");
        rejected("add_slice", "wheel", "task", "text", new String(new char[61]).replace('\0', 'x'));
        act("add_slice", "wheel", "task", "text", new String(new char[60]).replace('\0', 'x'));
        rejected("add_slice", "wheel", "task", "text", "propose MARRIAGE");
        act("add_slice", "wheel", "voice", "text", "Propose marriage"); // Duplicates count per wheel.
    }

    @Test
    public void removeSliceKeepsAtLeastTwoOnEachWheel() {
        rejected("remove_slice", "wheel", "task", "id", "nope");
        rejected("remove_slice", "wheel", "colour", "id", "t1");
        for (int i = 1; i <= 10; i++) act("remove_slice", "wheel", "task", "id", "t" + i);
        assertEquals(2, ((List<?>) state().get("tasks")).size());
        rejected("remove_slice", "wheel", "task", "id", "t11");
        assertEquals(2, ((List<?>) state().get("tasks")).size());
        // Two slices left still spins, alternating.
        String a = field(act("spin").get("task"), "id");
        land();
        String b = field(act("spin").get("task"), "id");
        assertNotEquals(a, b);
    }

    @Test
    public void removingAllAbsurdVoicesStillSpinsInEarlyRounds() {
        for (int i = 15; i <= 21; i++) act("remove_slice", "wheel", "voice", "id", "v" + i);
        assertNotNull(act("spin").get("voice"));
    }

    // ----- Scoring -----

    private String[] twoPlayers() {
        act("add_player", "name", "Raghav");
        act("add_player", "name", "Papa");
        return new String[] {"p1", "p2"};
    }

    @Test
    public void plainPointsGoToThePerformer() {
        twoPlayers();
        toScoring();
        Map<String, Object> s = act("score", "points", "4", "commitment", "false", "crack", "false");
        assertEquals(4, score("p1"));
        assertEquals(0, score("p2"));
        assertEquals("ready", s.get("phase"));
        assertEquals(2, s.get("round"));
        assertEquals("p2", s.get("currentPlayerId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> award = (Map<String, Object>) s.get("lastAward");
        assertEquals("p1", award.get("playerId"));
        assertEquals("Raghav", award.get("name"));
        assertEquals(4, award.get("points"));
        assertEquals(false, award.get("stolen"));
    }

    @Test
    public void commitmentAddsThree() {
        twoPlayers();
        toScoring();
        act("score", "points", "5", "commitment", "true");
        assertEquals(8, score("p1"));
    }

    @Test
    public void crackTakesTwo() {
        twoPlayers();
        toScoring();
        act("score", "points", "5", "crack", "true");
        assertEquals(3, score("p1"));
    }

    @Test
    public void commitmentAndCrackTogether() {
        twoPlayers();
        toScoring();
        act("score", "points", "2", "commitment", "true", "crack", "true");
        assertEquals(3, score("p1"));
    }

    @Test
    public void totalNeverGoesBelowZeroAndDoesNotEatEarlierScore() {
        twoPlayers();
        toScoring();
        act("score", "points", "3");
        act("skip_turn"); // Back to p1.
        toScoring();
        act("score", "points", "1", "crack", "true");
        assertEquals(3, score("p1"));
        act("skip_turn");
        act("set_current", "id", "p2");
        toScoring();
        Map<String, Object> s = act("score", "points", "1", "crack", "true");
        assertEquals(0, score("p2"));
        @SuppressWarnings("unchecked")
        Map<String, Object> award = (Map<String, Object>) s.get("lastAward");
        assertEquals(0, award.get("points"));
    }

    @Test
    public void stealGivesHalfRoundedUpAndNoBonusAndPerformerNothing() {
        twoPlayers();
        toScoring();
        Map<String, Object> s = act("score", "points", "5", "commitment", "true", "crack", "true", "stealerId", "p2");
        assertEquals(0, score("p1"));
        assertEquals(3, score("p2"));
        @SuppressWarnings("unchecked")
        Map<String, Object> award = (Map<String, Object>) s.get("lastAward");
        assertEquals("p2", award.get("playerId"));
        assertEquals(3, award.get("points"));
        assertEquals(true, award.get("stolen"));
        assertEquals("Raghav", award.get("fromName"));
    }

    @Test
    public void stealRoundsEachValueUp() {
        int[][] cases = {{1, 1}, {2, 1}, {3, 2}, {4, 2}, {5, 3}};
        for (int[] c : cases) {
            game = new AccentWheelGame(clock::get, new Random(1));
            twoPlayers();
            toScoring();
            act("score", "points", String.valueOf(c[0]), "stealerId", "p2");
            assertEquals(c[1], score("p2"));
        }
    }

    @Test
    public void emptyStealerMeansNoSteal() {
        twoPlayers();
        toScoring();
        act("score", "points", "3", "stealerId", "");
        assertEquals(3, score("p1"));
    }

    @Test
    public void badScoresAreRejectedAndChangeNothing() {
        twoPlayers();
        toScoring();
        rejected("score");
        rejected("score", "points", "0");
        rejected("score", "points", "6");
        rejected("score", "points", "3.5");
        rejected("score", "points", "abc");
        rejected("score", "points", "3", "commitment", "yes");
        rejected("score", "points", "3", "crack", "1");
        rejected("score", "points", "3", "stealerId", "nobody");
        rejected("score", "points", "3", "stealerId", "p1");
        assertEquals("scoring", phase());
        assertEquals(0, score("p1"));
        assertEquals(1, state().get("round"));
    }

    @Test
    public void scoresAccumulateAndResetKeepsPlayersAndCustomSlices() {
        twoPlayers();
        act("add_slice", "wheel", "voice", "text", "Mamaji");
        toScoring();
        act("score", "points", "3");
        toScoring();
        act("score", "points", "2");
        toScoring();
        act("score", "points", "1");
        assertEquals(4, score("p1"));
        assertEquals(2, score("p2"));
        Map<String, Object> s = act("reset_scores");
        assertEquals(0, score("p1"));
        assertEquals(0, score("p2"));
        assertEquals(1, s.get("round"));
        assertNull(s.get("lastAward"));
        assertEquals(2, ((List<?>) s.get("players")).size());
        assertEquals(22, ((List<?>) s.get("voices")).size());
    }

    // ----- Players -----

    @Test
    public void addPlayerTrimsAndFirstOneBecomesCurrent() {
        Map<String, Object> s = act("add_player", "name", "  Team Marigold ");
        assertEquals("p1", s.get("currentPlayerId"));
        assertEquals("Team Marigold", player("p1").get("name"));
        assertEquals(0, player("p1").get("score"));
        assertEquals("p1", act("add_player", "name", "Papa").get("currentPlayerId"));
    }

    @Test
    public void addPlayerRejectsBadNames() {
        rejected("add_player");
        rejected("add_player", "name", "    ");
        rejected("add_player", "name", new String(new char[31]).replace('\0', 'x'));
        act("add_player", "name", new String(new char[30]).replace('\0', 'x'));
        act("add_player", "name", "Raghav");
        rejected("add_player", "name", "Raghav");
        rejected("add_player", "name", " RAGHAV ");
    }

    @Test
    public void atMost30Players() {
        for (int i = 0; i < 30; i++) act("add_player", "name", "P" + i);
        rejected("add_player", "name", "One too many");
        assertEquals(30, ((List<?>) state().get("players")).size());
    }

    @Test
    public void setCurrentChoosesPerformerAndRejectsUnknown() {
        twoPlayers();
        assertEquals("p2", act("set_current", "id", "p2").get("currentPlayerId"));
        rejected("set_current", "id", "nope");
        rejected("set_current");
        assertEquals("p2", current());
    }

    @Test
    public void nextPlayerRotatesAndWraps() {
        twoPlayers();
        act("add_player", "name", "Mummy");
        List<String> order = new ArrayList<String>();
        for (int i = 0; i < 4; i++) {
            order.add(current());
            playRound();
        }
        assertEquals(java.util.Arrays.asList("p1", "p2", "p3", "p1"), order);
    }

    @Test
    public void skipTurnFromAnyPhaseGoesToReadyWithNextPlayerAndNoPoints() {
        twoPlayers();
        assertEquals("p2", act("skip_turn").get("currentPlayerId")); // ready
        act("spin");
        Map<String, Object> s = act("skip_turn"); // spinning
        assertEquals("ready", s.get("phase"));
        assertEquals("p1", s.get("currentPlayerId"));
        act("spin");
        land();
        assertEquals("ready", act("skip_turn").get("phase")); // landed
        act("spin");
        land();
        act("start_timer");
        assertEquals("ready", act("skip_turn").get("phase")); // performing
        act("spin");
        land();
        act("start_timer");
        act("stop_timer");
        assertEquals("ready", act("skip_turn").get("phase")); // scoring
        assertEquals(0, score("p1"));
        assertEquals(0, score("p2"));
        assertEquals(1, state().get("round"));
        pass(60000);
        assertEquals("ready", phase());
    }

    @Test
    public void removingTheCurrentPlayerPassesTheTurnOn() {
        twoPlayers();
        act("add_player", "name", "Mummy");
        assertEquals("p2", act("remove_player", "id", "p1").get("currentPlayerId"));
        act("set_current", "id", "p3");
        assertEquals("p2", act("remove_player", "id", "p3").get("currentPlayerId")); // Last in the list wraps round.
        assertNull(act("remove_player", "id", "p2").get("currentPlayerId"));
        rejected("remove_player", "id", "p2");
        rejected("remove_player");
    }

    @Test
    public void removingAnotherPlayerLeavesTheTurnAlone() {
        twoPlayers();
        assertEquals("p1", act("remove_player", "id", "p2").get("currentPlayerId"));
    }

    @Test
    public void removingTheCurrentPlayerMidRoundScoresTheNewCurrent() {
        twoPlayers();
        toScoring();
        act("remove_player", "id", "p1");
        act("score", "points", "2");
        assertEquals(2, score("p2"));
    }

    @Test
    public void newPlayerIdIsNeverReused() {
        twoPlayers();
        act("remove_player", "id", "p2");
        act("add_player", "name", "Papa");
        assertNull(player("p2"));
        assertNotNull(player("p3"));
    }

    // ----- No players -----

    @Test
    public void wholeRoundWorksWithNoPlayersAndScoresEveryone() {
        toScoring();
        Map<String, Object> s = act("score", "points", "4", "commitment", "true");
        assertEquals("ready", s.get("phase"));
        assertEquals(2, s.get("round"));
        assertNull(s.get("currentPlayerId"));
        assertTrue(((List<?>) s.get("players")).isEmpty());
        assertEquals(7, s.get("everyoneScore"));
        @SuppressWarnings("unchecked")
        Map<String, Object> award = (Map<String, Object>) s.get("lastAward");
        assertNull(award.get("playerId"));
        assertEquals("Everyone", award.get("name"));
        assertEquals(7, award.get("points"));
        assertEquals("ready", act("skip_turn").get("phase"));
        assertEquals(0, act("reset_scores").get("everyoneScore"));
    }

    @Test
    public void stealIsRejectedWithNoPlayers() {
        toScoring();
        rejected("score", "points", "3", "stealerId", "p1");
    }

    // ----- Requests -----

    @Test
    public void unknownActionsAndRequestsAreRejected() {
        rejected("dance");
        try {
            game.handle("POST", "action", new HashMap<String, String>());
            fail();
        } catch (IllegalArgumentException expected) {
            // Missing action.
        }
        try {
            game.handle("POST", "action", null);
            fail();
        } catch (IllegalArgumentException expected) {
            // No body.
        }
        try {
            game.handle("GET", "action", null);
            fail();
        } catch (IllegalArgumentException expected) {
            // Wrong method.
        }
        try {
            game.handle("GET", "other", null);
            fail();
        } catch (IllegalArgumentException expected) {
            // Wrong path.
        }
    }

    @Test
    public void stateRequestReturnsEveryContractField() {
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) game.handle("GET", "state", null);
        for (String k : new String[] {"phase", "spinId", "spinMs", "phaseMsLeft", "round", "voice", "task", "players",
                "currentPlayerId", "timerSeconds", "secondsLeft", "speedRound", "voices", "tasks", "lastAward", "serverNow"}) {
            assertTrue(k, s.containsKey(k));
        }
    }
}
