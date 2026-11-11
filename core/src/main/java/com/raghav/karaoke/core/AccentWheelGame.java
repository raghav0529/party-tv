package com.raghav.karaoke.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The Accent Wheel game. All state lives here so the TV and every phone agree. There is no timer thread: phase
 * changes that depend on time are worked out from the clock whenever state is read or an action arrives.
 */
public final class AccentWheelGame implements AppBackend {
    public static final String ID = "accent-wheel";

    static final long SPIN_MS = 4500;
    static final int NORMAL_SECONDS = 45;
    static final int SPEED_SECONDS = 15;
    static final int ABSURD_ONLY_ROUNDS = 3;
    static final int MAX_NAME = 30;
    static final int MAX_PLAYERS = 30;
    static final int MAX_SLICE = 60;
    static final int MIN_SLICES = 2;
    static final int COMMITMENT_BONUS = 3;
    static final int CRACK_PENALTY = 2;
    static final String EVERYONE = "Everyone";

    private static final String READY = "ready";
    private static final String SPINNING = "spinning";
    private static final String LANDED = "landed";
    private static final String PERFORMING = "performing";
    private static final String SCORING = "scoring";

    private static final String ABSURD = "Absurd";
    private static final String CUSTOM = "Custom";

    private static final String[][] VOICES = {
        {"Characters", "Bollywood villain", "Daily-soap saas", "Motivational guru", "Shakespearean actor", "Wedding DJ",
            "Horror movie whisper", "Royal British butler"},
        {"Professions", "Railway announcer", "Cricket commentator", "News anchor (shouting)", "Airline pilot",
            "Hindi teacher marking answer sheets", "Customer care (\"Your call is important...\")", "Sabziwala bargaining"},
        {ABSURD, "Google Maps / Siri", "Nature documentary narrator", "Robot running out of battery",
            "Your phone's autocorrect", "A sleepy toddler", "A ghost who's very polite", "Drunk uncle at a wedding"},
    };

    private static final String[] TASKS = {
        "Propose marriage", "Explain how a solar cell works", "Apologise for breaking the TV remote",
        "Order momos on the phone", "Break up with your Wi-Fi router", "Give a eulogy for the last gulab jamun",
        "Convince Papa to raise your pocket money", "Teach Nani to use Instagram",
        "Defend yourself in court for stealing kaju katli", "Give a pep talk to a team losing at Ludo",
        "Explain the plot of your favourite movie", "Announce the winner of a beauty contest for pets",
    };

    private static final class Slice {
        final String id, text, group;
        final boolean custom;

        Slice(String id, String text, String group, boolean custom) {
            this.id = id;
            this.text = text;
            this.group = group;
            this.custom = custom;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("text", text);
            if (group != null) m.put("group", group);
            m.put("custom", custom);
            return m;
        }
    }

    private static final class Player {
        final String id, name;
        int score;

        Player(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final LongSupplier clock;
    private final Random random;

    private final List<Slice> voices = new ArrayList<Slice>();
    private final List<Slice> tasks = new ArrayList<Slice>();
    private final Set<String> usedVoices = new HashSet<String>();
    private final Set<String> usedTasks = new HashSet<String>();
    private final List<Player> players = new ArrayList<Player>();
    private int nextVoiceId = 1, nextTaskId = 1, nextPlayerId = 1;

    private String phase = READY;
    /** When the spin or the timer ends. Meaningful only while spinning or performing. */
    private long phaseEndsAt;
    private int spinId;
    private int round = 1;
    private Slice voice, task;
    private String currentPlayerId;
    private boolean speedRound;
    /** Length of the timer now running or just finished. */
    private int runningSeconds = NORMAL_SECONDS;
    /** Seconds on the clock when the timer stopped. */
    private int stoppedSecondsLeft;
    private int everyoneScore;
    private Map<String, Object> lastAward;

    public AccentWheelGame() {
        this(new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    public AccentWheelGame(LongSupplier clock) {
        this(clock, new Random());
    }

    AccentWheelGame(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
        for (String[] group : VOICES) {
            for (int i = 1; i < group.length; i++) voices.add(new Slice("v" + nextVoiceId++, group[i], group[0], false));
        }
        for (String text : TASKS) tasks.add(new Slice("t" + nextTaskId++, text, null, false));
    }

    @Override
    public synchronized Object handle(String method, String path, Map<String, String> body) {
        if ("GET".equals(method) && "state".equals(path)) return state();
        if ("POST".equals(method) && "action".equals(path)) return action(body);
        throw new IllegalArgumentException("Unknown request: " + method + " " + path);
    }

    /** Applies any time-based phase changes that are due. A long gap can pass through several in one go. */
    private void tick() {
        long t = clock.getAsLong();
        if (SPINNING.equals(phase) && t >= phaseEndsAt) {
            if (speedRound) {
                startTimer(phaseEndsAt);
            } else {
                phase = LANDED;
            }
        }
        if (PERFORMING.equals(phase) && t >= phaseEndsAt) {
            phase = SCORING;
            stoppedSecondsLeft = 0;
        }
    }

    private void startTimer(long from) {
        runningSeconds = speedRound ? SPEED_SECONDS : NORMAL_SECONDS;
        phaseEndsAt = from + runningSeconds * 1000L;
        phase = PERFORMING;
    }

    private static int wholeSecondsUp(long ms) {
        return (int) ((ms + 999) / 1000);
    }

    synchronized Map<String, Object> state() {
        tick();
        long t = clock.getAsLong();
        boolean timed = SPINNING.equals(phase) || PERFORMING.equals(phase);
        long msLeft = timed ? Math.max(0, phaseEndsAt - t) : 0;
        int timerSeconds;
        int secondsLeft;
        if (PERFORMING.equals(phase)) {
            timerSeconds = runningSeconds;
            secondsLeft = wholeSecondsUp(msLeft);
        } else if (SCORING.equals(phase)) {
            timerSeconds = runningSeconds;
            secondsLeft = stoppedSecondsLeft;
        } else {
            timerSeconds = speedRound ? SPEED_SECONDS : NORMAL_SECONDS;
            secondsLeft = timerSeconds;
        }
        List<Map<String, Object>> playersOut = new ArrayList<Map<String, Object>>();
        for (Player p : players) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", p.id);
            m.put("name", p.name);
            m.put("score", p.score);
            playersOut.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("phase", phase);
        out.put("spinId", spinId);
        out.put("spinMs", SPIN_MS);
        out.put("phaseMsLeft", msLeft);
        out.put("round", round);
        out.put("voice", voice == null ? null : voice.toJson());
        out.put("task", task == null ? null : task.toJson());
        out.put("players", playersOut);
        out.put("currentPlayerId", currentPlayerId);
        out.put("timerSeconds", timerSeconds);
        out.put("secondsLeft", secondsLeft);
        out.put("speedRound", speedRound);
        out.put("voices", slicesJson(voices));
        out.put("tasks", slicesJson(tasks));
        out.put("lastAward", lastAward == null ? null : new LinkedHashMap<String, Object>(lastAward));
        out.put("everyoneScore", everyoneScore);
        out.put("serverNow", t);
        return out;
    }

    private static List<Map<String, Object>> slicesJson(List<Slice> slices) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Slice s : slices) out.add(s.toJson());
        return out;
    }

    private Map<String, Object> action(Map<String, String> body) {
        if (body == null) throw new IllegalArgumentException("Body must be JSON");
        String action = body.get("action");
        if (action == null) throw new IllegalArgumentException("Missing action");
        tick();
        switch (action) {
            case "add_player":
                addPlayer(body.get("name"));
                break;
            case "remove_player":
                removePlayer(body.get("id"));
                break;
            case "set_current":
                currentPlayerId = requirePlayer(body.get("id")).id;
                break;
            case "spin":
                spin();
                break;
            case "start_timer":
                requirePhase(LANDED);
                startTimer(clock.getAsLong());
                break;
            case "stop_timer":
                requirePhase(PERFORMING);
                stoppedSecondsLeft = wholeSecondsUp(Math.max(0, phaseEndsAt - clock.getAsLong()));
                phase = SCORING;
                break;
            case "score":
                score(body);
                break;
            case "skip_turn":
                phase = READY;
                moveToNextPlayer();
                break;
            case "add_slice":
                addSlice(body.get("wheel"), body.get("text"));
                break;
            case "remove_slice":
                removeSlice(body.get("wheel"), body.get("id"));
                break;
            case "set_speed":
                speedRound = parseBoolean(body.get("on"), "on");
                break;
            case "reset_scores":
                for (Player p : players) p.score = 0;
                everyoneScore = 0;
                round = 1;
                lastAward = null;
                break;
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
        return state();
    }

    private void requirePhase(String wanted) {
        if (!wanted.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
    }

    private static boolean parseBoolean(String value, String field) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException(field + " must be true or false");
    }

    private static boolean optionalBoolean(String value, String field) {
        return value != null && parseBoolean(value, field);
    }

    // ----- Players -----

    private void addPlayer(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Name is empty");
        if (name.length() > MAX_NAME) throw new IllegalArgumentException("Name is longer than " + MAX_NAME + " characters");
        if (players.size() >= MAX_PLAYERS) throw new IllegalArgumentException("Already " + MAX_PLAYERS + " players");
        for (Player p : players) {
            if (same(p.name, name)) throw new IllegalArgumentException("That name is already playing");
        }
        Player p = new Player("p" + nextPlayerId++, name);
        players.add(p);
        if (currentPlayerId == null) currentPlayerId = p.id;
    }

    private static boolean same(String a, String b) {
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    private Player findPlayer(String id) {
        for (Player p : players) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private Player requirePlayer(String id) {
        Player p = findPlayer(id);
        if (p == null) throw new IllegalArgumentException("No such player");
        return p;
    }

    /** If the current player leaves, the turn goes to whoever comes after them. */
    private void removePlayer(String id) {
        Player p = requirePlayer(id);
        int index = players.indexOf(p);
        players.remove(index);
        if (p.id.equals(currentPlayerId)) {
            currentPlayerId = players.isEmpty() ? null : players.get(index % players.size()).id;
        }
    }

    private void moveToNextPlayer() {
        if (players.isEmpty()) {
            currentPlayerId = null;
            return;
        }
        int index = players.indexOf(findPlayer(currentPlayerId));
        currentPlayerId = players.get((index + 1) % players.size()).id;
    }

    // ----- Spinning -----

    private void spin() {
        if (!READY.equals(phase) && !LANDED.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
        voice = pick(voices, usedVoices, voice, round <= ABSURD_ONLY_ROUNDS);
        task = pick(tasks, usedTasks, task, false);
        spinId++;
        phaseEndsAt = clock.getAsLong() + SPIN_MS;
        phase = SPINNING;
    }

    /**
     * Picks an unused slice. Once a pool is used up, only that pool starts over, and the slice just shown is left out
     * so it cannot land twice in a row. If Absurd has been removed entirely, any voice will do.
     */
    private Slice pick(List<Slice> wheel, Set<String> used, Slice last, boolean absurdOnly) {
        List<Slice> pool = new ArrayList<Slice>();
        if (absurdOnly) {
            for (Slice s : wheel) {
                if (ABSURD.equals(s.group)) pool.add(s);
            }
        }
        if (pool.isEmpty()) pool.addAll(wheel);

        List<Slice> choices = new ArrayList<Slice>();
        for (Slice s : pool) {
            if (!used.contains(s.id)) choices.add(s);
        }
        if (choices.isEmpty()) {
            for (Slice s : pool) {
                used.remove(s.id);
                if (s != last) choices.add(s);
            }
            if (choices.isEmpty()) choices.addAll(pool);
        }
        Slice chosen = choices.get(random.nextInt(choices.size()));
        used.add(chosen.id);
        return chosen;
    }

    // ----- Scoring -----

    private void score(Map<String, String> body) {
        requirePhase(SCORING);
        int points = parsePoints(body.get("points"));
        boolean commitment = optionalBoolean(body.get("commitment"), "commitment");
        boolean crack = optionalBoolean(body.get("crack"), "crack");
        String stealerId = body.get("stealerId");
        Player performer = findPlayer(currentPlayerId);
        Player stealer = null;
        if (stealerId != null && !stealerId.isEmpty()) {
            stealer = requirePlayer(stealerId);
            if (stealer == performer) throw new IllegalArgumentException("A player cannot steal from themselves");
        }

        Player winner = stealer != null ? stealer : performer;
        int award;
        if (stealer != null) {
            award = (points + 1) / 2;
        } else {
            award = Math.max(0, points + (commitment ? COMMITMENT_BONUS : 0) - (crack ? CRACK_PENALTY : 0));
        }
        if (winner != null) winner.score += award;
        else everyoneScore += award;

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("playerId", winner == null ? null : winner.id);
        m.put("name", winner == null ? EVERYONE : winner.name);
        m.put("points", award);
        m.put("stolen", stealer != null);
        m.put("fromName", stealer == null || performer == null ? null : performer.name);
        lastAward = m;

        round++;
        moveToNextPlayer();
        phase = READY;
    }

    private static int parsePoints(String value) {
        if (value == null || !value.matches("[1-5]")) throw new IllegalArgumentException("points must be 1 to 5");
        return Integer.parseInt(value);
    }

    // ----- Slices -----

    private void addSlice(String wheel, String rawText) {
        boolean isVoice = parseWheel(wheel);
        List<Slice> slices = isVoice ? voices : tasks;
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Text is empty");
        if (text.length() > MAX_SLICE) throw new IllegalArgumentException("Text is longer than " + MAX_SLICE + " characters");
        for (Slice s : slices) {
            if (same(s.text, text)) throw new IllegalArgumentException("That is already on the wheel");
        }
        if (isVoice) slices.add(new Slice("v" + nextVoiceId++, text, CUSTOM, true));
        else slices.add(new Slice("t" + nextTaskId++, text, null, true));
    }

    private void removeSlice(String wheel, String id) {
        boolean isVoice = parseWheel(wheel);
        List<Slice> slices = isVoice ? voices : tasks;
        Slice found = null;
        for (Slice s : slices) {
            if (s.id.equals(id)) found = s;
        }
        if (found == null) throw new IllegalArgumentException("No such slice");
        if (slices.size() <= MIN_SLICES) throw new IllegalArgumentException("Each wheel keeps at least " + MIN_SLICES + " slices");
        slices.remove(found);
        (isVoice ? usedVoices : usedTasks).remove(found.id);
    }

    /** True for the voice wheel, false for the task wheel. */
    private static boolean parseWheel(String wheel) {
        if ("voice".equals(wheel)) return true;
        if ("task".equals(wheel)) return false;
        throw new IllegalArgumentException("wheel must be voice or task");
    }
}
