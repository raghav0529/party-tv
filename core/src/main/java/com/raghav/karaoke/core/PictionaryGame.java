package com.raghav.karaoke.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Pictionary. One phone draws, the TV shows the drawing live, and the team guesses out loud. The TV is in front of
 * everyone, so the prompt leaves this class only to whoever holds the drawer token, until the summary.
 * No timer thread: phase changes that depend on time are worked out from the clock on every read or action.
 */
public final class PictionaryGame implements AppBackend {
    public static final String ID = "pictionary";

    static final long CLAIM_MS = 15000;
    static final long DRAW_MS = 60000;
    static final long STEAL_MS = 10000;
    static final long FAST_MS = 20000;
    /** A batch already on its way when time ran out still counts, so the last line of a drawing is not lost. */
    static final long DRAW_GRACE_MS = 1000;
    static final int FAST_POINTS = 3;
    static final int SLOW_POINTS = 2;
    static final int HARD_BONUS = 1;
    static final int STEAL_POINTS = 1;
    static final int MAX_STROKES = 800;
    static final int MAX_POINTS = 40000;
    static final int MAX_COORD = 1000;
    static final int MIN_TEAMS = 2;
    static final int MAX_TEAMS = 3;
    static final int MAX_TEAM_NAME = 20;
    static final int MAX_DRAWER_NAME = 20;
    static final int MAX_PROMPT = 40;
    static final int MAX_CUSTOM = 200;

    private static final String READY = "ready";
    private static final String CLAIMED = "claimed";
    private static final String DRAWING = "drawing";
    private static final String PAUSED = "paused";
    private static final String STEAL = "steal";
    private static final String SUMMARY = "summary";

    private static final String EASY = "Easy";
    private static final String MEDIUM = "Medium";
    private static final String HARD = "Hard";
    private static final String CUSTOM = "Custom";

    static final Set<String> COLOURS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("black", "red", "blue", "green", "orange", "purple")));
    private static final Pattern STROKE_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final String[] DEFAULT_TEAMS = {"Spades", "Hearts", "Diamonds"};

    private static final String[] EASY_DECK = {"Candle", "Rainbow", "Rocket", "Guitar", "Laddoo", "Samosa", "Chai",
        "Mango", "Kite", "Elephant", "Peacock", "Cricket bat", "Auto rickshaw", "Taj Mahal", "Pressure cooker",
        "Ceiling fan", "Umbrella", "Sunglasses"};
    private static final String[] MEDIUM_DECK = {"Gulab jamun", "Tiffin box", "Mehndi", "Wedding baraat",
        "Traffic signal", "Sabziwala", "Metro train", "Titanic", "Spider-Man", "Jurassic Park", "Selfie stick",
        "School uniform", "Remote control", "Blowing out birthday candles"};
    private static final String[] HARD_DECK = {"Power cut", "Wi-Fi buffering", "Traffic jam", "Monday morning",
        "Global warming", "Getting scolded by Mummy", "Relatives asking about marks", "Missing the last metro"};

    private static final class Prompt {
        final String text, level;

        Prompt(String text, String level) {
            this.text = text;
            this.level = level;
        }
    }

    private static final class Team {
        final String id;
        String name;
        int score;

        Team(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Stroke {
        final String id, color;
        final int size, seq;
        int[] xy = new int[32];
        /** Points held, each one an x and a y. */
        int count;

        Stroke(String id, String color, int size, int seq) {
            this.id = id;
            this.color = color;
            this.size = size;
            this.seq = seq;
        }

        void add(int[] from, int firstPoint, int points) {
            int need = (count + points) * 2;
            if (need > xy.length) xy = Arrays.copyOf(xy, Math.max(need, xy.length * 2));
            System.arraycopy(from, firstPoint * 2, xy, count * 2, points * 2);
            count += points;
        }

        Map<String, Object> toJson(int fromPoint) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("color", color);
            m.put("size", size);
            m.put("from", fromPoint);
            m.put("points", Arrays.copyOfRange(xy, fromPoint * 2, count * 2));
            return m;
        }
    }

    /** Which points of a stroke arrived at which version, so a poll gets only what it lacks. */
    private static final class Delta {
        final int version;
        final Stroke stroke;
        final int from;

        Delta(int version, Stroke stroke, int from) {
            this.version = version;
            this.stroke = stroke;
            this.from = from;
        }
    }

    private static final class Incoming {
        String id, color;
        int size;
        /** -1 when the sender did not say where these points belong: they then simply follow on. */
        int from = -1;
        int[] xy;
    }

    private final LongSupplier clock;
    private final Random random;
    private final SecureRandom tokens = new SecureRandom();

    private final List<Team> teams = new ArrayList<Team>();
    private int nextTeamId = 1;
    private int currentIndex;
    private int round = 1;

    private final List<Prompt> deck = new ArrayList<Prompt>();
    private final Set<Prompt> used = new HashSet<Prompt>();
    private final List<Prompt> skipped = new ArrayList<Prompt>();
    private int customCount;

    private String phase = READY;
    private String pausedFrom;
    private long pausedMsLeft;
    /** When the current timed phase ends. Meaningful only during claimed, drawing and steal. */
    private long phaseEndsAt;
    private long stealStartedAt;

    private Prompt prompt;
    private String promptId;
    private int promptSeq;
    private String drawerToken;
    private String drawerName;
    private boolean skipUsed;

    private final List<Stroke> strokes = new ArrayList<Stroke>();
    private final Map<String, Stroke> strokesById = new HashMap<String, Stroke>();
    /** Ids undone or cleared, so a retried batch cannot bring a removed stroke back. */
    private final Set<String> removedIds = new HashSet<String>();
    private final List<Delta> log = new ArrayList<Delta>();
    private int totalPoints;
    private int strokeSeq;
    /** Never goes back, even across turns, so a TV holding an old number always knows it is out of date. */
    private int version;
    private int clearedAt;

    private String outcome;
    private int lastPoints;
    private String scoredTeamId, scoredTeamName;

    public PictionaryGame() {
        this(new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    public PictionaryGame(LongSupplier clock) {
        this(clock, new Random());
    }

    PictionaryGame(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
        addDeck(EASY_DECK, EASY);
        addDeck(MEDIUM_DECK, MEDIUM);
        addDeck(HARD_DECK, HARD);
        addTeam(DEFAULT_TEAMS[0]);
        addTeam(DEFAULT_TEAMS[1]);
    }

    private void addDeck(String[] texts, String level) {
        for (String t : texts) deck.add(new Prompt(t, level));
    }

    private Team addTeam(String name) {
        Team t = new Team("t" + nextTeamId++, name);
        teams.add(t);
        return t;
    }

    @Override
    public synchronized Object handle(String method, String path, Map<String, String> body) {
        String route = path == null ? "" : path;
        String query = "";
        int q = route.indexOf('?');
        if (q >= 0) {
            query = route.substring(q + 1);
            route = route.substring(0, q);
        }
        if ("GET".equals(method) && "state".equals(route)) return state();
        if ("GET".equals(method) && "strokes".equals(route)) return strokes(parseSince(query));
        if ("POST".equals(method) && "drawer".equals(route)) return drawer(body);
        if ("POST".equals(method) && "action".equals(route)) return action(body);
        throw new IllegalArgumentException("Unknown request: " + method + " " + route);
    }

    /** Leaving the game stops the clock, so nobody loses time to a trip to the home screen. */
    @Override
    public synchronized void onLeave() {
        tick();
        if (isTimed(phase)) pause();
    }

    private static boolean isTimed(String p) {
        return CLAIMED.equals(p) || DRAWING.equals(p) || STEAL.equals(p);
    }

    // ----- Time -----

    /** Applies whatever is due. A long gap can pass through several phases, each starting when the last one ended. */
    private void tick() {
        long t = clock.getAsLong();
        if (CLAIMED.equals(phase) && t >= phaseEndsAt) {
            long startedAt = phaseEndsAt;
            phase = DRAWING;
            phaseEndsAt = startedAt + DRAW_MS;
        }
        if (DRAWING.equals(phase) && t >= phaseEndsAt) {
            stealStartedAt = phaseEndsAt;
            phase = STEAL;
            phaseEndsAt = stealStartedAt + STEAL_MS;
        }
        if (STEAL.equals(phase) && t >= phaseEndsAt) {
            finishTurn("none", 0, null);
        }
    }

    private void pause() {
        pausedFrom = phase;
        pausedMsLeft = Math.max(0, phaseEndsAt - clock.getAsLong());
        phase = PAUSED;
    }

    private void resume() {
        phaseEndsAt = clock.getAsLong() + pausedMsLeft;
        if (STEAL.equals(pausedFrom)) stealStartedAt = phaseEndsAt - STEAL_MS;
        phase = pausedFrom;
        pausedFrom = null;
    }

    private long msLeft() {
        if (PAUSED.equals(phase)) return pausedMsLeft;
        return isTimed(phase) ? Math.max(0, phaseEndsAt - clock.getAsLong()) : 0;
    }

    private static int wholeSecondsUp(long ms) {
        return (int) ((ms + 999) / 1000);
    }

    // ----- Reading -----

    /** Goes to the TV and to every phone. It must never carry the prompt before the summary, nor ever the token. */
    synchronized Map<String, Object> state() {
        tick();
        List<Map<String, Object>> teamsOut = new ArrayList<Map<String, Object>>();
        for (Team team : teams) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", team.id);
            m.put("name", team.name);
            m.put("score", team.score);
            teamsOut.add(m);
        }
        long left = msLeft();
        Team current = teams.get(currentIndex);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("phase", phase);
        out.put("pausedFrom", pausedFrom);
        out.put("promptId", promptId);
        out.put("secondsLeft", wholeSecondsUp(left));
        out.put("msLeft", left);
        out.put("turnSeconds", (int) (DRAW_MS / 1000));
        out.put("teams", teamsOut);
        out.put("currentTeamId", current.id);
        out.put("currentTeamName", current.name);
        out.put("round", round);
        out.put("drawerName", drawerName);
        out.put("strokeVersion", version);
        out.put("clearedAt", clearedAt);
        out.put("skipLeft", skipUsed ? 0 : 1);
        out.put("customPrompts", customCount);
        if (SUMMARY.equals(phase)) {
            Team next = teams.get((currentIndex + 1) % teams.size());
            out.put("prompt", prompt == null ? null : prompt.text);
            out.put("hard", prompt != null && HARD.equals(prompt.level));
            out.put("outcome", outcome);
            out.put("points", lastPoints);
            out.put("scoredTeamId", scoredTeamId);
            out.put("scoredTeamName", scoredTeamName);
            out.put("nextTeamId", next.id);
            out.put("nextTeamName", next.name);
        }
        out.put("serverNow", clock.getAsLong());
        return out;
    }

    private static int parseSince(String query) {
        for (String part : query.split("&")) {
            if (!part.startsWith("since=")) continue;
            String v = part.substring("since=".length());
            if (!v.matches("[0-9]{1,9}")) throw new IllegalArgumentException("since must be a number");
            return Integer.parseInt(v);
        }
        return 0;
    }

    /**
     * What a screen at version since still lacks. reset means it must wipe its canvas and draw this list from nothing:
     * the canvas was cleared or changed after since, or the number is one this server never gave out.
     */
    synchronized Map<String, Object> strokes(int since) {
        tick();
        boolean reset = since < clearedAt || since > version;
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (reset || since == 0) {
            for (Stroke s : strokes) out.add(s.toJson(0));
        } else {
            Map<Stroke, Integer> firstNew = new HashMap<Stroke, Integer>();
            for (int i = log.size() - 1; i >= 0 && log.get(i).version > since; i--) {
                Delta d = log.get(i);
                Integer known = firstNew.get(d.stroke);
                if (known == null || d.from < known) firstNew.put(d.stroke, d.from);
            }
            List<Stroke> changed = new ArrayList<Stroke>(firstNew.keySet());
            Collections.sort(changed, new Comparator<Stroke>() {
                @Override
                public int compare(Stroke a, Stroke b) {
                    return a.seq - b.seq;
                }
            });
            for (Stroke s : changed) out.add(s.toJson(firstNew.get(s)));
        }
        Map<String, Object> reply = new LinkedHashMap<String, Object>();
        reply.put("version", version);
        reply.put("clearedAt", clearedAt);
        reply.put("reset", reset);
        reply.put("strokes", out);
        return reply;
    }

    /** The only way to learn the prompt during a turn. A valid token gets no prompt once the drawing has ended. */
    private Map<String, Object> drawer(Map<String, String> body) {
        if (body == null) throw new IllegalArgumentException("Body must be JSON");
        tick();
        requireToken(body);
        boolean live = CLAIMED.equals(phase) || DRAWING.equals(phase)
                || (PAUSED.equals(phase) && !STEAL.equals(pausedFrom));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("phase", phase);
        out.put("promptId", promptId);
        out.put("prompt", live ? prompt.text : null);
        out.put("hard", live && HARD.equals(prompt.level));
        return out;
    }

    // ----- Actions -----

    private Map<String, Object> action(Map<String, String> body) {
        if (body == null) throw new IllegalArgumentException("Body must be JSON");
        String action = body.get("action");
        if (action == null) throw new IllegalArgumentException("Missing action");
        tick();
        Map<String, Object> extra = null;
        switch (action) {
            case "claim":
                requirePhase(READY, "Someone is already drawing");
                claim(body.get("name"));
                extra = withToken();
                break;
            case "take_over":
                takeOver(body.get("name"));
                extra = withToken();
                break;
            case "start_drawing":
                requireToken(body);
                requirePhase(CLAIMED, "The drawing has already started");
                phase = DRAWING;
                phaseEndsAt = clock.getAsLong() + DRAW_MS;
                break;
            case "draw":
                extra = draw(body);
                break;
            case "undo_stroke":
                requireToken(body);
                requirePhase(DRAWING, "Not allowed during " + phase);
                undoStroke(body.get("strokeId"));
                break;
            case "clear":
                requireToken(body);
                requirePhase(DRAWING, "Not allowed during " + phase);
                clearCanvas();
                break;
            case "skip":
                requireToken(body);
                requirePhase(DRAWING, "Not allowed during " + phase);
                if (skipUsed) throw new IllegalArgumentException("The free skip has been used");
                skipUsed = true;
                skipped.add(replacePrompt());
                clearCanvas();
                break;
            case "got_it":
                extra = gotIt(body.get("promptId"));
                break;
            case "steal":
                steal(body);
                break;
            case "pause":
                if (!isTimed(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
                pause();
                break;
            case "resume":
                requirePhase(PAUSED, "Not paused");
                resume();
                tick();
                break;
            case "next_turn":
                requirePhase(SUMMARY, "Not allowed during " + phase);
                nextTurn();
                break;
            case "set_team_name":
                setTeamName(body.get("id"), body.get("name"));
                break;
            case "add_team":
                requireBetweenTurns();
                addThirdTeam(body.get("name"));
                break;
            case "remove_team":
                requireBetweenTurns();
                removeTeam(body.get("id"));
                break;
            case "add_prompt":
                addPrompt(body.get("text"));
                break;
            case "reset_game":
                resetGame();
                break;
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
        Map<String, Object> out = state();
        if (extra != null) out.putAll(extra);
        return out;
    }

    private void requirePhase(String wanted, String message) {
        if (!wanted.equals(phase)) throw new IllegalArgumentException(message);
    }

    private void requireBetweenTurns() {
        if (!READY.equals(phase) && !SUMMARY.equals(phase)) throw new IllegalArgumentException("Only between turns");
    }

    private void requireToken(Map<String, String> body) {
        String token = body.get("token");
        if (token == null || drawerToken == null || !drawerToken.equals(token)) {
            throw new IllegalArgumentException("This phone is not the drawer");
        }
    }

    private Map<String, Object> withToken() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("drawerToken", drawerToken);
        return m;
    }

    private String newToken() {
        byte[] b = new byte[16];
        tokens.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static String cleanName(String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.isEmpty()) return null;
        return name.length() > MAX_DRAWER_NAME ? name.substring(0, MAX_DRAWER_NAME) : name;
    }

    // ----- Turn flow -----

    private void claim(String name) {
        skipUsed = false;
        removedIds.clear();
        beginDrawer(name);
    }

    /** Whoever draws next starts from nothing: they may already have seen the old drawing. */
    private void takeOver(String name) {
        boolean allowed = CLAIMED.equals(phase) || DRAWING.equals(phase)
                || (PAUSED.equals(phase) && !STEAL.equals(pausedFrom));
        if (!allowed) throw new IllegalArgumentException("Nothing to take over");
        skipped.add(prompt);
        prompt = null;
        pausedFrom = null;
        clearCanvas();
        beginDrawer(name);
    }

    private void beginDrawer(String name) {
        replacePrompt();
        drawerToken = newToken();
        drawerName = cleanName(name);
        phase = CLAIMED;
        phaseEndsAt = clock.getAsLong() + CLAIM_MS;
    }

    /** Returns the prompt it replaced, if any. */
    private Prompt replacePrompt() {
        Prompt old = prompt;
        prompt = pick(old);
        used.add(prompt);
        promptId = "d" + ++promptSeq;
        return old;
    }

    /** Fresh prompts first, then skipped ones, then the whole deck again. Round 1 keeps to Easy while it can. */
    private Prompt pick(Prompt current) {
        List<Prompt> choices = new ArrayList<Prompt>();
        if (round == 1) {
            for (Prompt p : deck) {
                if (EASY.equals(p.level) && !used.contains(p)) choices.add(p);
            }
        }
        if (choices.isEmpty()) {
            for (Prompt p : deck) {
                if (!used.contains(p)) choices.add(p);
            }
        }
        if (choices.isEmpty()) {
            for (Prompt p : skipped) {
                if (p != current) choices.add(p);
            }
            if (!choices.isEmpty()) {
                Prompt p = choices.get(random.nextInt(choices.size()));
                skipped.remove(p);
                return p;
            }
            used.clear();
            for (Prompt p : deck) {
                if (p != current) choices.add(p);
            }
        }
        return choices.get(random.nextInt(choices.size()));
    }

    private Map<String, Object> gotIt(String id) {
        if (id == null) throw new IllegalArgumentException("Missing promptId");
        boolean drawingNow = DRAWING.equals(phase) || (PAUSED.equals(phase) && DRAWING.equals(pausedFrom));
        boolean reading = CLAIMED.equals(phase) || (PAUSED.equals(phase) && CLAIMED.equals(pausedFrom));
        boolean current = id.equals(promptId);
        if (current && reading) throw new IllegalArgumentException("The drawing has not started");
        if (!current || !drawingNow) {
            // A double tap, or a tap the server clock says came after time was up. Quietly nothing.
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ignored", true);
            return m;
        }
        long elapsed = DRAW_MS - msLeft();
        int points = elapsed <= FAST_MS ? FAST_POINTS : SLOW_POINTS;
        if (HARD.equals(prompt.level)) points += HARD_BONUS;
        finishTurn("guessed", points, teams.get(currentIndex));
        return null;
    }

    private void steal(Map<String, String> body) {
        requirePhase(STEAL, "Not allowed during " + phase);
        String teamId = body.get("teamId");
        if (teamId == null && "true".equals(body.get("none"))) teamId = "none";
        if ("none".equals(teamId)) {
            finishTurn("none", 0, null);
            return;
        }
        Team found = null;
        for (Team t : teams) {
            if (t.id.equals(teamId)) found = t;
        }
        if (found == null) throw new IllegalArgumentException("No such team");
        if (found == teams.get(currentIndex)) throw new IllegalArgumentException("The drawing team cannot steal");
        finishTurn("stolen", STEAL_POINTS, found);
    }

    /** The token dies here and the final drawing stays until next_turn. */
    private void finishTurn(String how, int points, Team winner) {
        if (winner != null) winner.score += points;
        outcome = how;
        lastPoints = points;
        scoredTeamId = winner == null ? null : winner.id;
        scoredTeamName = winner == null ? null : winner.name;
        drawerToken = null;
        pausedFrom = null;
        phase = SUMMARY;
    }

    private void nextTurn() {
        endTurnData();
        currentIndex++;
        if (currentIndex >= teams.size()) {
            currentIndex = 0;
            round++;
        }
        phase = READY;
    }

    private void endTurnData() {
        prompt = null;
        promptId = null;
        drawerName = null;
        drawerToken = null;
        pausedFrom = null;
        skipUsed = false;
        outcome = null;
        lastPoints = 0;
        scoredTeamId = null;
        scoredTeamName = null;
        clearCanvas();
    }

    private void resetGame() {
        endTurnData();
        removedIds.clear();
        used.clear();
        skipped.clear();
        for (Team t : teams) t.score = 0;
        currentIndex = 0;
        round = 1;
        phase = READY;
    }

    // ----- Drawing -----

    private Map<String, Object> draw(Map<String, String> body) {
        requireToken(body);
        boolean graceful = STEAL.equals(phase) && clock.getAsLong() - stealStartedAt < DRAW_GRACE_MS;
        if (!DRAWING.equals(phase) && !graceful) throw new IllegalArgumentException("Not allowed during " + phase);
        List<Incoming> batch = parseStrokes(body.get("strokes"));

        int accepted = 0, ignored = 0;
        for (Incoming in : batch) {
            int points = in.xy.length / 2;
            if (points == 0 || removedIds.contains(in.id)) continue;
            Stroke stroke = strokesById.get(in.id);
            if (stroke == null && strokes.size() >= MAX_STROKES) {
                ignored += points;
                continue;
            }
            int held = stroke == null ? 0 : stroke.count;
            // A retry may repeat points that arrived although the reply was lost. Those are dropped, not doubled.
            int skip = in.from >= 0 && in.from < held ? Math.min(points, held - in.from) : 0;
            int fresh = points - skip;
            if (fresh == 0) continue;
            int take = Math.min(fresh, MAX_POINTS - totalPoints);
            ignored += fresh - take;
            if (take == 0) continue;
            if (stroke == null) {
                stroke = new Stroke(in.id, in.color, in.size, ++strokeSeq);
                strokes.add(stroke);
                strokesById.put(in.id, stroke);
            }
            version++;
            log.add(new Delta(version, stroke, stroke.count));
            stroke.add(in.xy, skip, take);
            totalPoints += take;
            accepted += take;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("accepted", accepted);
        m.put("ignoredPoints", ignored);
        m.put("limited", ignored > 0 || totalPoints >= MAX_POINTS || strokes.size() >= MAX_STROKES);
        return m;
    }

    /** Checks the whole batch first, so a bad one changes nothing. */
    private static List<Incoming> parseStrokes(String json) {
        if (json == null) throw new IllegalArgumentException("Missing strokes");
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("strokes must be JSON");
        }
        if (!root.isJsonArray()) throw new IllegalArgumentException("strokes must be a list");
        List<Incoming> out = new ArrayList<Incoming>();
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) throw new IllegalArgumentException("Bad stroke");
            JsonObject o = e.getAsJsonObject();
            Incoming in = new Incoming();
            in.id = stringField(o, "id");
            if (in.id == null || !STROKE_ID.matcher(in.id).matches()) throw new IllegalArgumentException("Bad stroke id");
            in.color = stringField(o, "color");
            if (in.color == null || !COLOURS.contains(in.color)) throw new IllegalArgumentException("Bad colour");
            in.size = intField(o, "size", 1, 3);
            if (o.has("from")) in.from = intField(o, "from", 0, MAX_POINTS);
            JsonElement pts = o.get("points");
            if (pts == null || !pts.isJsonArray()) throw new IllegalArgumentException("Bad points");
            JsonArray arr = pts.getAsJsonArray();
            if (arr.size() % 2 != 0) throw new IllegalArgumentException("Points come as x then y");
            in.xy = new int[arr.size()];
            for (int i = 0; i < arr.size(); i++) in.xy[i] = number(arr.get(i), 0, MAX_COORD);
            out.add(in);
        }
        return out;
    }

    private static String stringField(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static int intField(JsonObject o, String key, int min, int max) {
        JsonElement e = o.get(key);
        if (e == null) throw new IllegalArgumentException("Missing " + key);
        return number(e, min, max);
    }

    private static int number(JsonElement e, int min, int max) {
        if (!e.isJsonPrimitive()) throw new IllegalArgumentException("Bad number");
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) throw new IllegalArgumentException("Bad number");
        double d;
        try {
            d = p.getAsDouble();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Bad number");
        }
        if (d != Math.rint(d) || d < min || d > max) throw new IllegalArgumentException("Number out of range");
        return (int) d;
    }

    /** With an id, removes exactly that stroke, so retrying an undo whose reply was lost cannot remove a second one. */
    private void undoStroke(String strokeId) {
        Stroke target = null;
        if (strokeId != null) {
            target = strokesById.get(strokeId);
            if (target == null) removedIds.add(strokeId);
        } else if (!strokes.isEmpty()) {
            target = strokes.get(strokes.size() - 1);
        }
        if (target == null) return;
        strokes.remove(target);
        strokesById.remove(target.id);
        removedIds.add(target.id);
        totalPoints -= target.count;
        log.clear(); // Every screen starts over after a removal, so the older deltas can never be asked for.
        clearedAt = ++version;
    }

    private void clearCanvas() {
        for (Stroke s : strokes) removedIds.add(s.id);
        strokes.clear();
        strokesById.clear();
        log.clear();
        totalPoints = 0;
        clearedAt = ++version;
    }

    // ----- Teams and prompts -----

    private Team findTeam(String id) {
        for (Team t : teams) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    private String checkTeamName(String raw, Team self) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Name is empty");
        if (name.length() > MAX_TEAM_NAME) throw new IllegalArgumentException("Name is longer than " + MAX_TEAM_NAME + " characters");
        for (Team t : teams) {
            if (t != self && t.name.equalsIgnoreCase(name)) throw new IllegalArgumentException("That name is taken");
        }
        return name;
    }

    private void setTeamName(String id, String rawName) {
        Team team = findTeam(id);
        if (team == null) throw new IllegalArgumentException("No such team");
        team.name = checkTeamName(rawName, team);
    }

    private void addThirdTeam(String rawName) {
        if (teams.size() >= MAX_TEAMS) throw new IllegalArgumentException("At most " + MAX_TEAMS + " teams");
        String name = rawName == null || rawName.trim().isEmpty() ? null : checkTeamName(rawName, null);
        if (name == null) {
            for (String candidate : DEFAULT_TEAMS) {
                boolean free = true;
                for (Team t : teams) {
                    if (t.name.equalsIgnoreCase(candidate)) free = false;
                }
                if (free) {
                    name = candidate;
                    break;
                }
            }
        }
        if (name == null) name = "Team " + nextTeamId;
        addTeam(name);
    }

    private void removeTeam(String id) {
        Team team = findTeam(id);
        if (team == null) throw new IllegalArgumentException("No such team");
        if (teams.size() <= MIN_TEAMS) throw new IllegalArgumentException("At least " + MIN_TEAMS + " teams");
        int index = teams.indexOf(team);
        teams.remove(index);
        if (index < currentIndex) {
            currentIndex--;
        } else if (index == currentIndex) {
            // In the summary, next_turn is about to step forward, so step back onto the team before this one.
            if (SUMMARY.equals(phase)) currentIndex = (currentIndex + teams.size() - 1) % teams.size();
            else currentIndex = currentIndex % teams.size();
        }
    }

    private void addPrompt(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Prompt is empty");
        if (text.length() > MAX_PROMPT) throw new IllegalArgumentException("Prompt is longer than " + MAX_PROMPT + " characters");
        if (customCount >= MAX_CUSTOM) throw new IllegalArgumentException("Too many added prompts");
        for (Prompt p : deck) {
            if (p.text.equalsIgnoreCase(text)) throw new IllegalArgumentException("That prompt is already in the deck");
        }
        deck.add(new Prompt(text, CUSTOM));
        customCount++;
    }
}
