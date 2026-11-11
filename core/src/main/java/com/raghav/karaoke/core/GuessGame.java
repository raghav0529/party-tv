package com.raghav.karaoke.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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
 * Lost in Translation and Emoji Movie: one quiz show with two decks. The whole room sees state, so state never carries
 * the answer before reveal. Only the host's token gets it. No timer thread: time is worked out from the clock on every
 * read or action, and a buzz stops the clock while the host judges.
 *
 * Lock order: ApiServer calls onLeave holding its own lock, so this class must never call the song host while holding
 * its own monitor. Song commands are collected inside the monitor and run after it is released.
 */
public final class GuessGame implements AppBackend {
    public static final String ENGLISH_SONG = "english-song";
    public static final String EMOJI_MOVIE = "emoji-movie";

    /** Plays one song on the TV without touching Karaoke. May refuse by telling the listener "failed". */
    public interface SongHost {
        void play(String owner, String query, SongListener listener);

        void stop(String owner);
    }

    public interface SongListener {
        /** "playing" or "failed". */
        void onSongState(String state);
    }

    static final long QUESTION_MS = 40000;
    static final long CLUE_MS = 12000;
    /** After a wrong answer the others always get this long to steal, even if the clock was nearly out. */
    static final long MIN_STEAL_MS = 6000;
    static final int CLUES = 4;
    static final int STREAK_LENGTH = 3;
    static final int STREAK_BONUS = 2;
    /** Round 1 keeps to easy questions, so the room warms up. */
    static final int ROUND_SIZE = 6;
    static final int MIN_TEAMS = 2;
    static final int MAX_TEAMS = 3;
    static final int MAX_TEAM_NAME = 20;

    private static final String READY = "ready";
    private static final String QUESTION = "question";
    private static final String BUZZED = "buzzed";
    private static final String REVEAL = "reveal";
    private static final String FINAL = "final";
    private static final String[] DEFAULT_TEAMS = {"Spades", "Hearts", "Diamonds"};

    static final class Question {
        String id, title, difficulty;
        List<String> alternates = new ArrayList<String>();
        int year;
        String movie, singer, original;
        List<String> lines = new ArrayList<String>();
        String emojis, lead;
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

    private final String id;
    private final boolean songMode;
    private final List<Question> deck;
    private final LongSupplier clock;
    private final Random random;
    private final SongHost songHost;
    private final SecureRandom tokens = new SecureRandom();

    private final List<Team> teams = new ArrayList<Team>();
    private int nextTeamId = 1;

    private String phase = READY;
    private boolean paused;
    private String hostToken;
    private boolean easyOnly;

    private final Set<Question> used = new HashSet<Question>();
    private Question current;
    private String questionId;
    private int questionSeq;
    private int asked;
    private int clue;
    /** Absolute times while the clock runs. Meaningful only in question and not paused. */
    private long endsAt, nextClueAt;
    /** Time left while the clock is stopped. */
    private long heldMs, heldClueMs;

    private Team buzzTeam;
    private int buzzClue;
    private int buzzSeq;
    private final List<Team> buzzOrder = new ArrayList<Team>();
    private final Set<Team> lockedOut = new HashSet<Team>();

    private boolean scored;
    private int revealClue;
    private String revealReason;
    private Map<String, Object> lastAward;
    private Team suggested;

    private Team streakTeam;
    private int streakCount;
    private int bonusSeq;

    private String songState = "off";
    private int songSeq;
    /** Set inside the monitor, run outside it. */
    private Runnable songCommand;

    public static GuessGame fromResource(String id, LongSupplier clock, SongHost songHost) {
        return new GuessGame(id, parseDeck(readDeck(id)), clock, new Random(), songHost);
    }

    GuessGame(String id, List<Question> deck, LongSupplier clock, Random random, SongHost songHost) {
        this.id = id;
        this.songMode = ENGLISH_SONG.equals(id);
        this.deck = deck;
        this.clock = clock;
        this.random = random;
        this.songHost = songHost;
        addTeam(DEFAULT_TEAMS[0]);
        addTeam(DEFAULT_TEAMS[1]);
    }

    private void addTeam(String name) {
        teams.add(new Team("t" + nextTeamId++, name));
    }

    // ----- Deck -----

    private static String readDeck(String id) {
        try (InputStream in = GuessGame.class.getResourceAsStream("/web/decks/" + id + ".json")) {
            return in == null ? null : new String(Http.readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** A broken entry is left out and a broken file gives an empty deck, so a typo cannot stop the app starting. */
    static List<Question> parseDeck(String json) {
        List<Question> out = new ArrayList<Question>();
        if (json == null) return out;
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return out;
        }
        if (!root.isJsonArray()) return out;
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            try {
                out.add(parseQuestion(e.getAsJsonObject()));
            } catch (RuntimeException bad) {
                // Skipped.
            }
        }
        return out;
    }

    private static Question parseQuestion(JsonObject o) {
        Question q = new Question();
        q.id = o.get("id").getAsString();
        q.title = o.get("title").getAsString();
        q.year = o.get("year").getAsInt();
        q.difficulty = o.get("difficulty").getAsString();
        q.alternates = strings(o.get("alternates"));
        q.movie = text(o, "movie");
        q.singer = text(o, "singer");
        q.original = text(o, "original");
        q.lines = strings(o.get("lines"));
        q.emojis = text(o, "emojis");
        q.lead = text(o, "lead");
        return q;
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<String>();
        if (e == null || !e.isJsonArray()) return out;
        JsonArray a = e.getAsJsonArray();
        for (JsonElement x : a) out.add(x.getAsString());
        return out;
    }

    // ----- Entry points -----

    @Override
    public Object handle(String method, String path, Map<String, String> body) {
        Object reply;
        Runnable command;
        synchronized (this) {
            reply = route(method, path, body);
            command = songCommand;
            songCommand = null;
        }
        runSongCommand(command);
        return reply;
    }

    private Object route(String method, String path, Map<String, String> body) {
        String route = path == null ? "" : path;
        int q = route.indexOf('?');
        if (q >= 0) route = route.substring(0, q);
        if ("GET".equals(method) && "state".equals(route)) return state();
        if ("POST".equals(method) && "host-view".equals(route)) return hostView(body);
        if ("POST".equals(method) && "action".equals(route)) return action(body);
        throw new IllegalArgumentException("Unknown request: " + method + " " + route);
    }

    /** A live question waits, and the song stops. A person resumes it. */
    @Override
    public void onLeave() {
        Runnable command;
        synchronized (this) {
            tick();
            if (QUESTION.equals(phase) && !paused) {
                holdClock();
                paused = true;
            }
            stopSong();
            command = songCommand;
            songCommand = null;
        }
        runSongCommand(command);
    }

    /** No internet or a player problem means no song. The game never hears about it. */
    private static void runSongCommand(Runnable command) {
        if (command == null) return;
        try {
            command.run();
        } catch (RuntimeException e) {
            // Fail quietly.
        }
    }

    // ----- Time -----

    private void tick() {
        if (!QUESTION.equals(phase) || paused) return;
        long t = clock.getAsLong();
        while (clue < CLUES && t >= nextClueAt) {
            clue++;
            nextClueAt += CLUE_MS;
        }
        if (t >= endsAt) goToReveal("time");
    }

    private void holdClock() {
        long t = clock.getAsLong();
        heldMs = Math.max(0, endsAt - t);
        heldClueMs = Math.max(0, nextClueAt - t);
    }

    private void runClock() {
        long t = clock.getAsLong();
        endsAt = t + heldMs;
        nextClueAt = t + heldClueMs;
    }

    private long msLeft() {
        if (QUESTION.equals(phase) && !paused) return Math.max(0, endsAt - clock.getAsLong());
        return QUESTION.equals(phase) || BUZZED.equals(phase) ? heldMs : 0;
    }

    private long nextClueMs() {
        if (clue >= CLUES) return 0;
        if (QUESTION.equals(phase) && !paused) return Math.max(0, nextClueAt - clock.getAsLong());
        return QUESTION.equals(phase) || BUZZED.equals(phase) ? heldClueMs : 0;
    }

    // ----- Reading -----

    /** Goes to the TV and to every phone. Before reveal it must hold nothing that names the answer. */
    synchronized Map<String, Object> state() {
        tick();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        boolean live = QUESTION.equals(phase) || BUZZED.equals(phase);
        boolean showing = live || REVEAL.equals(phase);
        long left = msLeft();
        out.put("app", id);
        out.put("phase", phase);
        out.put("paused", paused);
        out.put("questionId", showing ? questionId : null);
        out.put("questionNumber", asked);
        out.put("round", asked <= 0 ? 1 : 1 + (asked - 1) / ROUND_SIZE);
        out.put("clue", showing ? clue : 0);
        out.put("cluesTotal", CLUES);
        out.put("clues", showing ? cluesJson() : new ArrayList<Map<String, Object>>());
        out.put("msLeft", left);
        out.put("secondsLeft", (int) ((left + 999) / 1000));
        out.put("nextClueMs", nextClueMs());
        out.put("totalSeconds", (int) (QUESTION_MS / 1000));
        out.put("worth", live ? worth(BUZZED.equals(phase) ? buzzClue : clue) : 0);
        out.put("steal", live && !lockedOut.isEmpty());
        out.put("teams", teamsJson());
        out.put("buzzTeamId", BUZZED.equals(phase) ? buzzTeam.id : null);
        out.put("buzzTeamName", BUZZED.equals(phase) ? buzzTeam.name : null);
        out.put("buzzSeq", buzzSeq);
        out.put("buzzOrder", BUZZED.equals(phase) ? namesOf(buzzOrder) : new ArrayList<String>());
        out.put("hasHost", hostToken != null);
        out.put("difficulty", easyOnly ? "easy" : "mixed");
        out.put("bonusSeq", bonusSeq);
        out.put("deckSize", deck.size());
        out.put("songMode", songMode);
        out.put("canPlaySong", songMode && songHost != null);
        out.put("song", songState);
        if (REVEAL.equals(phase)) {
            out.put("reveal", revealJson());
            out.put("lastAward", lastAward);
            out.put("canAward", !scored);
            out.put("awardPoints", scored ? 0 : worth(revealClue));
            out.put("suggestedTeamId", suggested == null || scored ? null : suggested.id);
            out.put("revealReason", revealReason);
        }
        if (FINAL.equals(phase)) out.put("winners", winners());
        out.put("serverNow", clock.getAsLong());
        return out;
    }

    private List<Map<String, Object>> teamsJson() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Team t : teams) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", t.id);
            m.put("name", t.name);
            m.put("score", t.score);
            m.put("lockedOut", lockedOut.contains(t));
            m.put("streak", t == streakTeam ? streakCount : 0);
            out.add(m);
        }
        return out;
    }

    private static List<String> namesOf(List<Team> list) {
        List<String> out = new ArrayList<String>();
        for (Team t : list) out.add(t.name);
        return out;
    }

    private List<Map<String, Object>> cluesJson() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (current == null) return out;
        for (int i = 1; i <= clue; i++) out.add(clueJson(i));
        return out;
    }

    private Map<String, Object> clueJson(int n) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String kind, text;
        if (n == CLUES) {
            kind = "letters";
            text = firstLetters(current.title);
        } else if (songMode) {
            if (n <= 2) {
                kind = "line";
                text = n <= current.lines.size() ? current.lines.get(n - 1) : "";
            } else {
                kind = "info";
                text = current.year + ", sung by " + current.singer;
            }
        } else if (n == 1) {
            kind = "emoji";
            text = current.emojis;
        } else if (n == 2) {
            kind = "info";
            text = "Year: " + current.year;
        } else {
            kind = "info";
            text = "Lead: " + current.lead;
        }
        m.put("kind", kind);
        m.put("text", text);
        return m;
    }

    static String firstLetters(String title) {
        StringBuilder sb = new StringBuilder();
        for (String word : title.trim().split("\\s+")) {
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(Character.toUpperCase(c));
                    break;
                }
            }
        }
        return sb.toString();
    }

    private Map<String, Object> answerJson(Question q) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("answer", q.title);
        m.put("alternates", new ArrayList<String>(q.alternates));
        m.put("year", q.year);
        if (songMode) {
            m.put("movie", q.movie);
            m.put("singer", q.singer);
            m.put("lines", new ArrayList<String>(q.lines));
            m.put("original", q.original);
        } else {
            m.put("emojis", q.emojis);
            m.put("lead", q.lead);
        }
        return m;
    }

    private Map<String, Object> revealJson() {
        return current == null ? null : answerJson(current);
    }

    private List<String> winners() {
        int best = 0;
        for (Team t : teams) best = Math.max(best, t.score);
        List<String> out = new ArrayList<String>();
        if (best == 0) return out;
        for (Team t : teams) {
            if (t.score == best) out.add(t.name);
        }
        return out;
    }

    /** The only way to learn the answer before reveal. */
    private Map<String, Object> hostView(Map<String, String> body) {
        if (body == null) throw new IllegalArgumentException("Body must be JSON");
        tick();
        requireHost(body);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("phase", phase);
        boolean showing = QUESTION.equals(phase) || BUZZED.equals(phase) || REVEAL.equals(phase);
        out.put("questionId", showing ? questionId : null);
        if (showing && current != null) out.putAll(answerJson(current));
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
            case "become_host":
                if (hostToken != null) throw new IllegalArgumentException("There is already a host");
                hostToken = newToken();
                extra = tokenReply();
                break;
            case "take_over_host":
                hostToken = newToken();
                extra = tokenReply();
                break;
            case "start_question":
                requirePhase(READY);
                nextQuestion();
                break;
            case "next_question":
                if (stale(body)) return ignored();
                requirePhase(REVEAL);
                nextQuestion();
                break;
            case "next_clue":
                if (stale(body) || staleClue(body)) return ignored();
                nextClue();
                break;
            case "buzz":
                extra = buzz(body.get("teamId"), body);
                break;
            case "judge":
                extra = judge(body);
                break;
            case "award":
                extra = award(body);
                break;
            case "reveal":
                if (stale(body)) return ignored();
                if (!QUESTION.equals(phase) && !BUZZED.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
                goToReveal("host");
                break;
            case "resume":
                if (!QUESTION.equals(phase) || !paused) throw new IllegalArgumentException("Not paused");
                paused = false;
                runClock();
                break;
            case "set_team_name":
                setTeamName(body.get("id"), body.get("name"));
                break;
            case "add_team":
                requireBetweenQuestions();
                addOneTeam(body.get("name"));
                break;
            case "remove_team":
                requireBetweenQuestions();
                removeTeam(body.get("id"));
                break;
            case "set_difficulty":
                setDifficulty(body.get("difficulty"));
                break;
            case "end_game":
                if (FINAL.equals(phase)) break;
                stopSong();
                endGame();
                break;
            case "reset_game":
                stopSong();
                resetGame();
                break;
            case "play_song":
                requirePhase(REVEAL);
                playSong();
                break;
            case "stop_song":
                stopSong();
                break;
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
        Map<String, Object> out = state();
        if (extra != null) out.putAll(extra);
        return out;
    }

    private void requirePhase(String wanted) {
        if (!wanted.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
    }

    private void requireBetweenQuestions() {
        if (!READY.equals(phase) && !REVEAL.equals(phase) && !FINAL.equals(phase)) {
            throw new IllegalArgumentException("Only between questions");
        }
    }

    private void requireHost(Map<String, String> body) {
        String token = body.get("token");
        if (token == null || hostToken == null || !hostToken.equals(token)) {
            throw new IllegalArgumentException("This phone is not the host");
        }
    }

    private Map<String, Object> tokenReply() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("hostToken", hostToken);
        return m;
    }

    private String newToken() {
        byte[] b = new byte[16];
        tokens.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /** A tap for a question that has gone. It does nothing and is no error. */
    private boolean stale(Map<String, String> body) {
        String given = body.get("questionId");
        return given != null && !given.equals(questionId);
    }

    /** Two phones tapping Next clue at once must not jump two clues. */
    private boolean staleClue(Map<String, String> body) {
        String given = body.get("fromClue");
        return given != null && !given.equals(String.valueOf(clue));
    }

    private Map<String, Object> ignored() {
        Map<String, Object> out = state();
        out.put("ignored", true);
        return out;
    }

    private static Map<String, Object> ignoredMark() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ignored", true);
        return m;
    }

    // ----- Question flow -----

    private void nextQuestion() {
        Question q = pick();
        stopSong();
        if (q == null) {
            if (asked == 0) throw new IllegalArgumentException("This game has no questions");
            endGame();
            return;
        }
        used.add(q);
        current = q;
        questionId = "q" + ++questionSeq;
        asked++;
        clue = 1;
        paused = false;
        long t = clock.getAsLong();
        endsAt = t + QUESTION_MS;
        nextClueAt = t + CLUE_MS;
        buzzTeam = null;
        buzzOrder.clear();
        lockedOut.clear();
        scored = false;
        lastAward = null;
        suggested = null;
        revealReason = null;
        phase = QUESTION;
    }

    /** Easy first in round 1, or always on easy. If easy runs out the game carries on with the rest, without repeats. */
    private Question pick() {
        boolean wantEasy = easyOnly || asked < ROUND_SIZE;
        List<Question> choices = new ArrayList<Question>();
        if (wantEasy) {
            for (Question q : deck) {
                if (!used.contains(q) && "easy".equals(q.difficulty)) choices.add(q);
            }
        }
        if (choices.isEmpty()) {
            for (Question q : deck) {
                if (!used.contains(q)) choices.add(q);
            }
        }
        return choices.isEmpty() ? null : choices.get(random.nextInt(choices.size()));
    }

    private void nextClue() {
        requirePhase(QUESTION);
        if (paused) throw new IllegalArgumentException("Paused");
        if (clue >= CLUES) throw new IllegalArgumentException("No more clues");
        clue++;
        nextClueAt = clock.getAsLong() + CLUE_MS;
    }

    private Map<String, Object> buzz(String teamId, Map<String, String> body) {
        if (READY.equals(phase) || FINAL.equals(phase)) throw new IllegalArgumentException("Not allowed during " + phase);
        Team team = findTeam(teamId);
        if (team == null) throw new IllegalArgumentException("No such team");
        if (stale(body) || REVEAL.equals(phase) || paused || lockedOut.contains(team)) return ignoredMark();
        if (BUZZED.equals(phase)) {
            if (!buzzOrder.contains(team)) buzzOrder.add(team);
            return ignoredMark();
        }
        holdClock();
        buzzTeam = team;
        buzzClue = clue;
        buzzSeq++;
        buzzOrder.clear();
        buzzOrder.add(team);
        phase = BUZZED;
        return null;
    }

    private Map<String, Object> judge(Map<String, String> body) {
        requireHost(body);
        String raw = body.get("correct");
        if (!"true".equals(raw) && !"false".equals(raw)) throw new IllegalArgumentException("correct must be true or false");
        if (stale(body)) return ignoredMark();
        String givenBuzz = body.get("buzzSeq");
        if (givenBuzz != null && !givenBuzz.equals(String.valueOf(buzzSeq))) return ignoredMark();
        if (!BUZZED.equals(phase)) {
            if (body.get("questionId") != null) return ignoredMark();
            throw new IllegalArgumentException("Not allowed during " + phase);
        }
        if ("true".equals(raw)) {
            score(buzzTeam, buzzClue);
            goToReveal("correct");
        } else {
            wrong();
        }
        return null;
    }

    private void wrong() {
        lockedOut.add(buzzTeam);
        if (buzzTeam == streakTeam) {
            streakTeam = null;
            streakCount = 0;
        }
        buzzTeam = null;
        buzzOrder.clear();
        if (lockedOut.size() >= teams.size()) {
            goToReveal("wrong");
            return;
        }
        phase = QUESTION;
        heldMs = Math.max(heldMs, MIN_STEAL_MS);
        runClock();
    }

    /** With no buzzing, people shout and the host says who got it. On the result screen the remote can do this too. */
    private Map<String, Object> award(Map<String, String> body) {
        if (stale(body)) return ignoredMark();
        Team team = findTeam(body.get("teamId"));
        if (team == null) throw new IllegalArgumentException("No such team");
        if (REVEAL.equals(phase)) {
            if (scored) return ignoredMark();
            if (lockedOut.contains(team)) throw new IllegalArgumentException("That team was wrong on this one");
            score(team, revealClue);
            return null;
        }
        if (QUESTION.equals(phase)) {
            requireHost(body);
            if (lockedOut.contains(team)) throw new IllegalArgumentException("That team was wrong on this one");
            score(team, clue);
            goToReveal("correct");
            return null;
        }
        throw new IllegalArgumentException("Not allowed during " + phase);
    }

    private int worth(int atClue) {
        int base = CLUES + 1 - Math.max(1, Math.min(CLUES, atClue));
        return lockedOut.isEmpty() ? base : (base + 1) / 2;
    }

    private void score(Team team, int atClue) {
        int points = worth(atClue);
        boolean steal = !lockedOut.isEmpty();
        if (team == streakTeam) {
            streakCount++;
        } else {
            streakTeam = team;
            streakCount = 1;
        }
        boolean bonus = streakCount % STREAK_LENGTH == 0;
        if (bonus) bonusSeq++;
        int total = points + (bonus ? STREAK_BONUS : 0);
        team.score += total;
        scored = true;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("teamId", team.id);
        m.put("teamName", team.name);
        m.put("points", total);
        m.put("steal", steal);
        m.put("bonus", bonus);
        lastAward = m;
    }

    private void goToReveal(String reason) {
        if (revealReason == null) revealReason = reason;
        // An unjudged buzz keeps its clue, so the remote can still give that team the right points.
        revealClue = BUZZED.equals(phase) ? buzzClue : clue;
        suggested = BUZZED.equals(phase) ? buzzTeam : null;
        buzzTeam = null;
        paused = false;
        phase = REVEAL;
    }

    private void endGame() {
        current = null;
        questionId = null;
        buzzTeam = null;
        buzzOrder.clear();
        lockedOut.clear();
        paused = false;
        lastAward = null;
        phase = FINAL;
    }

    private void resetGame() {
        endGame();
        used.clear();
        asked = 0;
        clue = 0;
        scored = false;
        streakTeam = null;
        streakCount = 0;
        for (Team t : teams) t.score = 0;
        phase = READY;
    }

    private void setDifficulty(String value) {
        if ("easy".equals(value)) easyOnly = true;
        else if ("mixed".equals(value)) easyOnly = false;
        else throw new IllegalArgumentException("difficulty must be easy or mixed");
    }

    // ----- The song -----

    private void playSong() {
        if (!songMode || songHost == null) throw new IllegalArgumentException("No song in this game");
        if ("loading".equals(songState) || "playing".equals(songState)) return;
        final Question q = current;
        if (q == null) throw new IllegalArgumentException("No song");
        final int seq = ++songSeq;
        songState = "loading";
        final String query = q.title + " " + q.movie;
        final SongListener listener = new SongListener() {
            @Override
            public void onSongState(String state) {
                songUpdate(seq, state);
            }
        };
        songCommand = new Runnable() {
            @Override
            public void run() {
                songHost.play(id, query, listener);
            }
        };
    }

    private synchronized void songUpdate(int seq, String state) {
        if (seq == songSeq) songState = state;
    }

    private void stopSong() {
        if ("off".equals(songState)) return;
        songSeq++;
        songState = "off";
        if (songHost == null) return;
        songCommand = new Runnable() {
            @Override
            public void run() {
                songHost.stop(id);
            }
        };
    }

    // ----- Teams -----

    private Team findTeam(String teamId) {
        for (Team t : teams) {
            if (t.id.equals(teamId)) return t;
        }
        return null;
    }

    private String checkName(String raw, Team self) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Name is empty");
        if (name.length() > MAX_TEAM_NAME) throw new IllegalArgumentException("Name is longer than " + MAX_TEAM_NAME + " characters");
        for (Team t : teams) {
            if (t != self && t.name.equalsIgnoreCase(name)) throw new IllegalArgumentException("That name is taken");
        }
        return name;
    }

    private void setTeamName(String teamId, String raw) {
        Team team = findTeam(teamId);
        if (team == null) throw new IllegalArgumentException("No such team");
        team.name = checkName(raw, team);
    }

    private void addOneTeam(String raw) {
        if (teams.size() >= MAX_TEAMS) throw new IllegalArgumentException("At most " + MAX_TEAMS + " teams");
        String name = raw == null || raw.trim().isEmpty() ? null : checkName(raw, null);
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
        addTeam(name == null ? "Team " + nextTeamId : name);
    }

    private void removeTeam(String teamId) {
        Team team = findTeam(teamId);
        if (team == null) throw new IllegalArgumentException("No such team");
        if (teams.size() <= MIN_TEAMS) throw new IllegalArgumentException("At least " + MIN_TEAMS + " teams");
        teams.remove(team);
        lockedOut.remove(team);
        if (team == streakTeam) {
            streakTeam = null;
            streakCount = 0;
        }
        if (team == suggested) suggested = null;
    }
}
