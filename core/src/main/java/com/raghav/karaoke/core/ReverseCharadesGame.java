package com.raghav.karaoke.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Reverse Charades. A team acts while one guesser shouts answers. The prompt being secret from the guesser is the whole
 * game, so state and action replies never carry it, nor a category, during a live turn. Actors' phones fetch it from
 * the prompt route, and the TV shows it only if that setting is on. Every prompt is a movie, and every correct guess is 1 point. There is no timer thread: phase changes are worked
 * out from the clock when state is read or an action arrives.
 */
public final class ReverseCharadesGame implements AppBackend {
    public static final String ID = "reverse-charades";

    static final long COUNTDOWN_MS = 3000;
    static final long TURN_MS = 60000;
    /** Undo brings the previous prompt back only if the one now showing has been up for less than this. */
    static final long UNDO_BRING_BACK_MS = 5000;
    static final int FREE_SKIPS = 2;
    static final int BONUS_EVERY = 5;
    static final int BONUS_POINTS = 3;
    static final int MIN_TEAMS = 2;
    static final int MAX_TEAMS = 3;
    static final int MAX_TEAM_NAME = 20;
    static final int MAX_MEMBER_NAME = 30;
    static final int MAX_MEMBERS = 30;
    static final int MAX_PROMPT = 60;

    static final String NOTE_REUSE_SKIPPED = "Movies ran out. Bringing back skipped ones.";
    static final String NOTE_REUSE_ALL = "Movies ran out. Some may repeat now.";

    private static final String READY = "ready";
    private static final String COUNTDOWN = "countdown";
    private static final String PERFORMING = "performing";
    private static final String PAUSED = "paused";
    private static final String SUMMARY = "summary";

    private static final String CORRECT = "correct";
    private static final String SKIPPED = "skipped";
    private static final String UNGUESSED = "unguessed";

    private static final String[] TEAM_NAMES = {"Spades", "Hearts", "Diamonds"};

    /** Movies people across generations know and can act out. Added movies join these under Settings. */
    private static final String[] DECK = {
        // Classics
        "Sholay", "Mughal-e-Azam", "Mother India", "Anand", "Deewaar", "Don", "Amar Akbar Anthony", "Padosan",
        "Chupke Chupke", "Golmaal", "Guide", "Coolie", "Disco Dancer", "Karz", "Mr. India",
        // Late 80s and 90s
        "Ram Lakhan", "Qayamat Se Qayamat Tak", "Maine Pyar Kiya", "Chandni", "Hum Aapke Hain Koun",
        "Dilwale Dulhania Le Jayenge", "Kuch Kuch Hota Hai", "Karan Arjun", "Baazigar", "Andaz Apna Apna", "Border",
        "Taal", "Hum Saath Saath Hain", "Rangeela", "Dil To Pagal Hai",
        // 2000s
        "Hera Pheri", "Kabhi Khushi Kabhie Gham", "Lagaan", "Dil Chahta Hai", "Gadar", "Devdas", "Kal Ho Naa Ho",
        "Koi Mil Gaya", "Munna Bhai MBBS", "Main Hoon Na", "Dhoom", "Swades", "Veer-Zaara", "Black", "Rang De Basanti",
        "Krrish", "Chak De India", "Taare Zameen Par", "Om Shanti Om", "Jab We Met", "3 Idiots",
        // Recent
        "Dabangg", "Zindagi Na Milegi Dobara", "Barfi", "Queen", "PK", "Bajrangi Bhaijaan", "Dangal", "Baahubali",
        "RRR", "Pushpa", "Pathaan",
        // Hollywood
               
          
    };

    private enum Seen { NOT_YET, SKIPPED, YES }

    private static final class Prompt {
        final String id, text;
        Seen seen = Seen.NOT_YET;
        /** Order in which prompts were shown, so reuse takes the one the room saw longest ago. */
        long shownSeq;

        Prompt(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private static final class Team {
        final String id;
        String name;
        int score;
        int guesserIndex;
        final List<String> members = new ArrayList<String>();

        Team(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Result {
        final Prompt prompt;
        String outcome;

        Result(Prompt prompt, String outcome) {
            this.prompt = prompt;
            this.outcome = outcome;
        }
    }

    /** Everything needed to reverse one got_it, skip or foul. Undo goes strictly last-first, so restoring is exact. */
    private static final class Undo {
        int correct, skips, fouls, streak, points, score;
        String deckNote;
        /** The prompt that was acted on, and its line in the results. Null for a foul. */
        Prompt acted;
        int resultIndex;
        /** The prompt drawn because of this action, and how the deck held it before that. */
        Prompt next;
        Seen nextSeen;
        long nextShownSeq;
    }

    private final LongSupplier clock;
    private final Random random;

    private final List<Prompt> deck = new ArrayList<Prompt>();
    private final List<Team> teams = new ArrayList<Team>();
    private final List<Result> results = new ArrayList<Result>();
    private final List<Undo> undos = new ArrayList<Undo>();
    private int nextPromptId = 1, nextTeamId = 1;
    private long showCounter;

    private String phase = READY;
    private int currentIndex;
    private int round = 1;

    /** When the countdown or the performance ends. Meaningful only in those phases. */
    private long phaseEndsAt;
    /** Time left on the turn clock whenever it is not running. */
    private long clockMsLeft = TURN_MS;
    private Prompt current;
    /** Turn clock reading when the current prompt appeared. Being on this clock, a pause does not count as seen. */
    private long currentShownAtLeft;

    private int correct, skips, fouls, streak, points;
    private long bonusAt;
    private String deckNote;
    private boolean promptOnTv;

    public ReverseCharadesGame() {
        this(new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    public ReverseCharadesGame(LongSupplier clock) {
        this(clock, new Random());
    }

    ReverseCharadesGame(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
        List<String> titles = new ArrayList<String>(Arrays.asList(DECK));
        // Ids are given out in shuffled order, so an id seen on a phone says nothing about the prompt.
        Collections.shuffle(titles, random);
        for (String title : titles) deck.add(new Prompt("p" + nextPromptId++, title));
        addTeam();
        addTeam();
    }

    @Override
    public synchronized Object handle(String method, String path, Map<String, String> body) {
        if ("GET".equals(method) && "state".equals(path)) return state();
        if ("GET".equals(method) && "tv-state".equals(path)) return tvState();
        if ("GET".equals(method) && "prompt".equals(path)) return promptForActors();
        if ("POST".equals(method) && "action".equals(path)) return action(body);
        throw new IllegalArgumentException("Unknown request: " + method + " " + path);
    }

    /** The room went to another app. A live turn waits, and only a person can resume it. */
    @Override
    public synchronized void onLeave() {
        tick();
        if (isRunning()) pauseClock();
    }

    private boolean isRunning() {
        return COUNTDOWN.equals(phase) || PERFORMING.equals(phase);
    }

    private boolean isBetweenTurns() {
        return READY.equals(phase) || SUMMARY.equals(phase);
    }

    // ----- Time -----

    private void tick() {
        long t = clock.getAsLong();
        if (COUNTDOWN.equals(phase) && t >= phaseEndsAt) {
            long startedAt = phaseEndsAt;
            phase = PERFORMING;
            phaseEndsAt = startedAt + clockMsLeft;
            if (current == null) show(draw());
        }
        if (PERFORMING.equals(phase) && t >= phaseEndsAt) {
            clockMsLeft = 0;
            finishTurn();
        }
    }

    private long clockNow() {
        return PERFORMING.equals(phase) ? Math.max(0, phaseEndsAt - clock.getAsLong()) : clockMsLeft;
    }

    private void beginCountdown() {
        phase = COUNTDOWN;
        phaseEndsAt = clock.getAsLong() + COUNTDOWN_MS;
    }

    private void pauseClock() {
        clockMsLeft = clockNow();
        phase = PAUSED;
    }

    private void finishTurn() {
        if (current != null) results.add(new Result(current, UNGUESSED));
        current = null;
        phase = SUMMARY;
    }

    private static int wholeSecondsUp(long ms) {
        return (int) ((ms + 999) / 1000);
    }

    // ----- State -----

    /** The phone view. Must never hold the prompt or a category while a turn is live. */
    synchronized Map<String, Object> state() {
        tick();
        long t = clock.getAsLong();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("phase", phase);
        out.put("promptId", PERFORMING.equals(phase) && current != null ? current.id : null);
        out.put("secondsLeft", wholeSecondsUp(clockNow()));
        out.put("countdownLeft", COUNTDOWN.equals(phase) ? wholeSecondsUp(Math.max(0, phaseEndsAt - t)) : 0);
        out.put("teams", teamsJson());
        Team team = currentTeam();
        out.put("currentTeamId", team.id);
        out.put("guesser", team.members.isEmpty() ? null : team.members.get(team.guesserIndex));
        out.put("refereeTeamName", teams.get((currentIndex + 1) % teams.size()).name);
        out.put("round", round);
        out.put("turn", turnJson());
        out.put("bonusAt", bonusAt);
        out.put("deckNote", deckNote);
        out.put("promptOnTv", promptOnTv);
        out.put("serverNow", t);
        if (SUMMARY.equals(phase)) out.put("results", resultsJson());
        return out;
    }

    /** The TV view. Holds the prompt only if the setting is on and the clock is running. */
    synchronized Map<String, Object> tvState() {
        Map<String, Object> out = state();
        if (promptOnTv && PERFORMING.equals(phase) && current != null) {
            Map<String, Object> prompt = new LinkedHashMap<String, Object>();
            prompt.put("id", current.id);
            prompt.put("text", current.text);
            out.put("prompt", prompt);
        }
        return out;
    }

    /** For actors' and referees' phones. An empty reply unless the clock is running. */
    synchronized Map<String, Object> promptForActors() {
        tick();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (PERFORMING.equals(phase) && current != null) {
            out.put("promptId", current.id);
            out.put("text", current.text);
        }
        return out;
    }

    private List<Map<String, Object>> teamsJson() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Team team : teams) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", team.id);
            m.put("name", team.name);
            m.put("score", team.score);
            m.put("members", new ArrayList<String>(team.members));
            m.put("guesserIndex", team.guesserIndex);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> turnJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("correct", correct);
        m.put("skips", skips);
        m.put("freeSkipsLeft", Math.max(0, FREE_SKIPS - skips));
        m.put("fouls", fouls);
        m.put("streak", streak);
        m.put("points", points);
        return m;
    }

    private List<Map<String, Object>> resultsJson() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Result r : results) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("text", r.prompt.text);
            m.put("outcome", r.outcome);
            out.add(m);
        }
        return out;
    }

    private Team currentTeam() {
        return teams.get(currentIndex);
    }

    // ----- Actions -----

    private Map<String, Object> action(Map<String, String> body) {
        if (body == null) throw new IllegalArgumentException("Body must be JSON");
        String action = body.get("action");
        if (action == null) throw new IllegalArgumentException("Missing action");
        tick();
        switch (action) {
            case "start":
                requirePhase(READY);
                startTurn();
                break;
            case "got_it":
                gotIt(body.get("promptId"));
                break;
            case "skip":
                skip(body.get("promptId"));
                break;
            case "foul":
                foul();
                break;
            case "undo":
                undo();
                break;
            case "pause":
                if (!isRunning()) throw new IllegalArgumentException("Not allowed during " + phase);
                pauseClock();
                break;
            case "resume":
                requirePhase(PAUSED);
                resume();
                break;
            case "end_turn":
                if (!PERFORMING.equals(phase) && !PAUSED.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
                clockMsLeft = clockNow();
                finishTurn();
                break;
            case "next_turn":
                requirePhase(SUMMARY);
                nextTurn();
                break;
            case "set_team_name":
                setTeamName(body.get("id"), body.get("name"));
                break;
            case "add_team":
                requireBetweenTurns();
                if (teams.size() >= MAX_TEAMS) throw new IllegalArgumentException("At most " + MAX_TEAMS + " teams");
                addTeam();
                break;
            case "remove_team":
                removeTeam(body.get("id"));
                break;
            case "add_member":
                addMember(body.get("teamId"), body.get("name"));
                break;
            case "remove_member":
                removeMember(body.get("teamId"), body.get("name"), body.get("index"));
                break;
            case "add_prompt":
                addPrompt(body.get("text"));
                break;
            case "set_prompt_on_tv":
                requireBetweenTurns();
                promptOnTv = parseBoolean(body.get("on"), "on");
                break;
            case "reset_game":
                resetGame();
                break;
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
        return state();
    }

    private void requirePhase(String wanted) {
        if (!wanted.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
    }

    private void requireBetweenTurns() {
        if (!isBetweenTurns()) throw new IllegalArgumentException("Only between turns");
    }

    private static boolean parseBoolean(String value, String field) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException(field + " must be true or false");
    }

    // ----- A turn -----

    private void startTurn() {
        clearTurn();
        clockMsLeft = TURN_MS;
        beginCountdown();
    }

    /**
     * The clock carries on exactly where it stopped. Only if the prompt will appear on the TV, where a guesser who
     * turned around during the pause would see it, do they first get the countdown to sit down again. A pause during
     * the first countdown also runs it again, since no prompt has appeared yet.
     */
    private void resume() {
        if (promptOnTv || current == null) {
            beginCountdown();
        } else {
            phase = PERFORMING;
            phaseEndsAt = clock.getAsLong() + clockMsLeft;
        }
    }

    private void clearTurn() {
        correct = skips = fouls = streak = points = 0;
        current = null;
        results.clear();
        undos.clear();
        clockMsLeft = TURN_MS;
    }

    private void nextTurn() {
        Team played = currentTeam();
        if (!played.members.isEmpty()) played.guesserIndex = (played.guesserIndex + 1) % played.members.size();
        currentIndex++;
        if (currentIndex >= teams.size()) {
            currentIndex = 0;
            round++;
        }
        clearTurn();
        deckNote = null;
        phase = READY;
    }

    /**
     * Two phones can tap at once, and a tap can arrive after the clock stops. Both must do nothing, so these answer
     * with the state rather than an error. Before a turn starts there is nothing to tap on, which is a real mistake.
     */
    private boolean tapCounts(String promptId) {
        if (promptId == null || promptId.isEmpty()) throw new IllegalArgumentException("Missing promptId");
        if (READY.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
        return PERFORMING.equals(phase) && current != null && current.id.equals(promptId);
    }

    private void gotIt(String promptId) {
        if (!tapCounts(promptId)) return;
        Undo u = remember();
        correct++;
        streak++;
        int gained = 1;
        if (streak % BONUS_EVERY == 0) {
            gained += BONUS_POINTS;
            // Only ever rises, so an undo or a reset can never look like a new bonus to the TV.
            bonusAt = Math.max(clock.getAsLong(), bonusAt + 1);
        }
        addPoints(gained);
        moveOn(u, CORRECT);
    }

    private void skip(String promptId) {
        if (!tapCounts(promptId)) return;
        Undo u = remember();
        skips++;
        streak = 0;
        if (skips > FREE_SKIPS) addPoints(-1);
        current.seen = Seen.SKIPPED;
        moveOn(u, SKIPPED);
    }

    private void foul() {
        if (!PERFORMING.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
        remember();
        fouls++;
        addPoints(-1);
    }

    /** Team scores stop at 0, and the turn's points count only what really changed the score. */
    private void addPoints(int change) {
        Team team = currentTeam();
        int after = Math.max(0, team.score + change);
        points += after - team.score;
        team.score = after;
    }

    private Undo remember() {
        Undo u = new Undo();
        u.correct = correct;
        u.skips = skips;
        u.fouls = fouls;
        u.streak = streak;
        u.points = points;
        u.score = currentTeam().score;
        u.deckNote = deckNote;
        undos.add(u);
        return u;
    }

    private void moveOn(Undo u, String outcome) {
        u.acted = current;
        u.resultIndex = results.size();
        results.add(new Result(current, outcome));
        Prompt next = draw();
        u.next = next;
        u.nextSeen = next.seen;
        u.nextShownSeq = next.shownSeq;
        show(next);
    }

    private void undo() {
        if (READY.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
        if (undos.isEmpty()) throw new IllegalArgumentException("Nothing to undo");
        Undo u = undos.remove(undos.size() - 1);
        correct = u.correct;
        skips = u.skips;
        fouls = u.fouls;
        streak = u.streak;
        points = u.points;
        currentTeam().score = u.score;
        deckNote = u.deckNote;
        if (u.acted == null) return;

        u.acted.seen = Seen.YES;
        boolean recent = current != null && current == u.next && currentShownAtLeft - clockNow() < UNDO_BRING_BACK_MS;
        if (!recent) {
            // The room has moved on to another prompt, or the turn is over. The points still come back.
            results.get(u.resultIndex).outcome = UNGUESSED;
            return;
        }
        current.seen = u.nextSeen;
        current.shownSeq = u.nextShownSeq;
        results.remove(u.resultIndex);
        current = u.acted;
        currentShownAtLeft = clockNow();
    }

    // ----- The deck -----

    private void show(Prompt p) {
        current = p;
        p.seen = Seen.YES;
        p.shownSeq = ++showCounter;
        currentShownAtLeft = clockNow();
    }

    /** Unseen movies first, across all teams, then skipped ones, then the rest. The one on screen is left out unless it is the only one. */
    private Prompt draw() {
        List<Prompt> unseen = matching(Seen.NOT_YET);
        if (!unseen.isEmpty()) {
            deckNote = null;
            return unseen.get(random.nextInt(unseen.size()));
        }
        List<Prompt> skipped = matching(Seen.SKIPPED);
        if (!skipped.isEmpty()) {
            deckNote = NOTE_REUSE_SKIPPED;
            return longestAgo(skipped);
        }
        deckNote = NOTE_REUSE_ALL;
        return longestAgo(matching(Seen.YES));
    }

    private List<Prompt> matching(Seen seen) {
        List<Prompt> out = new ArrayList<Prompt>();
        for (Prompt p : deck) {
            if (p.seen == seen && p != current) out.add(p);
        }
        return out;
    }

    private Prompt longestAgo(List<Prompt> prompts) {
        if (prompts.isEmpty()) return current;
        Prompt best = prompts.get(0);
        for (Prompt p : prompts) {
            if (p.shownSeq < best.shownSeq) best = p;
        }
        return best;
    }

    private void addPrompt(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Text is empty");
        if (text.length() > MAX_PROMPT) throw new IllegalArgumentException("Text is longer than " + MAX_PROMPT + " characters");
        for (Prompt p : deck) {
            if (same(p.text, text)) throw new IllegalArgumentException("That is already in the deck");
        }
        deck.add(new Prompt("p" + nextPromptId++, text));
    }

    private void resetGame() {
        clearTurn();
        for (Team team : teams) {
            team.score = 0;
            team.guesserIndex = 0;
        }
        for (Prompt p : deck) {
            p.seen = Seen.NOT_YET;
            p.shownSeq = 0;
        }
        deckNote = null;
        currentIndex = 0;
        round = 1;
        phase = READY;
    }

    // ----- Teams -----

    private static boolean same(String a, String b) {
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    private void addTeam() {
        teams.add(new Team("t" + nextTeamId++, unusedTeamName()));
    }

    /** There are as many default names as the most teams allowed, so one is always free. */
    private String unusedTeamName() {
        for (String name : TEAM_NAMES) {
            boolean taken = false;
            for (Team team : teams) {
                if (same(team.name, name)) taken = true;
            }
            if (!taken) return name;
        }
        return TEAM_NAMES[0];
    }

    private Team requireTeam(String id) {
        for (Team team : teams) {
            if (team.id.equals(id)) return team;
        }
        throw new IllegalArgumentException("No such team");
    }

    private void setTeamName(String id, String raw) {
        Team team = requireTeam(id);
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Name is empty");
        if (name.length() > MAX_TEAM_NAME) throw new IllegalArgumentException("Name is longer than " + MAX_TEAM_NAME + " characters");
        for (Team other : teams) {
            if (other != team && same(other.name, name)) throw new IllegalArgumentException("Another team has that name");
        }
        team.name = name;
    }

    private void removeTeam(String id) {
        requireBetweenTurns();
        Team team = requireTeam(id);
        if (teams.size() <= MIN_TEAMS) throw new IllegalArgumentException("At least " + MIN_TEAMS + " teams");
        int index = teams.indexOf(team);
        if (SUMMARY.equals(phase) && index == currentIndex) {
            throw new IllegalArgumentException("That team's turn is still showing. Go to the next team first");
        }
        teams.remove(index);
        if (index < currentIndex) {
            currentIndex--;
        } else if (currentIndex >= teams.size()) {
            // The team whose turn was next is gone and everyone left has played.
            currentIndex = 0;
            round++;
        }
    }

    private void addMember(String teamId, String raw) {
        Team team = requireTeam(teamId);
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Name is empty");
        if (name.length() > MAX_MEMBER_NAME) throw new IllegalArgumentException("Name is longer than " + MAX_MEMBER_NAME + " characters");
        if (team.members.size() >= MAX_MEMBERS) throw new IllegalArgumentException("That team is full");
        for (String member : team.members) {
            if (same(member, name)) throw new IllegalArgumentException("That name is already on the team");
        }
        team.members.add(name);
    }

    private void removeMember(String teamId, String name, String rawIndex) {
        Team team = requireTeam(teamId);
        int index = -1;
        if (rawIndex != null) {
            if (!rawIndex.matches("\\d{1,3}")) throw new IllegalArgumentException("Bad index");
            index = Integer.parseInt(rawIndex);
        } else if (name != null) {
            for (int i = 0; i < team.members.size(); i++) {
                if (same(team.members.get(i), name.trim())) index = i;
            }
        }
        if (index < 0 || index >= team.members.size()) throw new IllegalArgumentException("No such member");
        team.members.remove(index);
        if (index < team.guesserIndex) team.guesserIndex--;
        if (team.guesserIndex >= team.members.size()) team.guesserIndex = 0;
    }
}
