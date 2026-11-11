package com.raghav.karaoke.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class ApiServer {
    static final int MAX_RESULTS = 15;
    static final long END_GUARD_MS = 1500;
    static final long SKIP_GUARD_MS = 800;
    static final long CONTROL_WAIT_MS = 500;
    /** lastError stays visible until the following song has clearly played this long. */
    static final long ERROR_CLEAR_AFTER_MS = 3000;

    static final String KARAOKE = "karaoke";
    /** App ids become resource paths, so this allows no dots, slashes or anything else that could leave web/apps/. */
    private static final Pattern APP_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    /** Used only if apps.json is missing or broken, so the home screen is never empty. */
    private static final String FALLBACK_APPS = "[{\"id\":\"karaoke\",\"title\":\"Karaoke\",\"subtitle\":\"\",\"icon\":\"mic\",\"backend\":true},"
            + "{\"id\":\"accent-wheel\",\"title\":\"Accent Wheel\",\"subtitle\":\"\",\"icon\":\"wheel\",\"backend\":true}]";

    /** Online song source. Replaced by a fake in tests. */
    interface Catalog {
        List<Song> search(String query) throws IOException;

        String resolveStream(Song song) throws IOException;
    }

    interface LyricFetcher {
        LyricsClient.Result fetch(Song song);
    }

    private final PlayerControl player;
    private final LocalLibrary library;
    private final String joinUrl;
    private final Catalog catalog;
    private final LyricFetcher lyricFetcher;
    private final LongSupplier clock;
    private final WebServer http;
    private final Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    /** Runs player commands in order and off the caller's thread, so the lock is never held across them. */
    private final ExecutorService playerExec = Executors.newSingleThreadExecutor(daemon("karaoke-player"));
    private final ExecutorService lyricExec = Executors.newFixedThreadPool(2, daemon("karaoke-lyrics"));
    private final ExecutorService searchExec = Executors.newCachedThreadPool(daemon("karaoke-search"));
    long searchLimitMs = 12000;
    /** Zero unless set: the real app turns this on, and tests with held lyrics opt in. */
    long lyricWaitMs = 0;

    private final Object lock = new Object();
    private final ArrayDeque<Song> queue = new ArrayDeque<Song>();
    private final Map<String, Song> recent = lru(500);
    private final Map<String, LyricsClient.Result> lyricsByUid = lru(200);
    private final Map<String, LyricsClient.Result> lyricsBySongId = lru(200);
    private final Set<String> lyricClaimed = new HashSet<String>();
    private Song now;
    /** True once player.play has been called for {@link #now}. An event before that cannot be about this song. */
    private boolean dispatched;
    private long startedAt;
    private long lastSkipAt = Long.MIN_VALUE / 2;
    private long nextUid = 1;
    private boolean paused;
    private boolean vocalCut;
    private String lastError;
    /** Set only while the internet is down. Unlike lastError it stays until the player recovers. */
    private String stallMessage;
    long networkRetryMs = 5000;

    /** Id of the app on every screen, or null for the home screen. Guarded by lock. */
    private String activeApp;
    /** True while no song may make sound: the hub has taken the party away from Karaoke. Guarded by lock. */
    private boolean musicHeld;
    /** True when the hub made the current pause, so only that pause is undone on returning to Karaoke. */
    private boolean hubPaused;
    /** A song that reached its turn while music was held. It starts on returning to Karaoke. */
    private Song heldBack;
    private volatile JsonArray apps = new JsonArray();
    private volatile Set<String> appIds = new HashSet<String>();
    private final Map<String, AppBackend> backends = new ConcurrentHashMap<String, AppBackend>();

    private String qrSvg;

    private final GameSongs gameSongs = new GameSongs();
    /** Who owns the song a game asked for, and whether it reached the player. Guarded by lock. */
    private String gameSongOwner;
    private boolean gameSongLoaded;
    private long gameSongSeq;
    private GuessGame.SongListener gameSongListener;

    public ApiServer(int port, PlayerControl player, LocalLibrary library, String joinUrl) {
        this(port, player, library, joinUrl, realCatalog(), new LyricFetcher() {
            private final LyricsClient client = new LyricsClient();

            @Override
            public LyricsClient.Result fetch(Song song) {
                return client.fetch(song);
            }
        }, new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
        lyricWaitMs = 4000;
    }

    ApiServer(int port, PlayerControl player, LocalLibrary library, String joinUrl, Catalog catalog,
            LyricFetcher lyricFetcher, LongSupplier clock) {
        this.player = player;
        this.library = library;
        this.joinUrl = joinUrl;
        this.catalog = catalog;
        this.lyricFetcher = lyricFetcher;
        this.clock = clock;
        this.http = new WebServer(port);
        loadApps(readAppsResource());
        registerApp(AccentWheelGame.ID, new AccentWheelGame(clock));
        registerApp(ReverseCharadesGame.ID, new ReverseCharadesGame(clock));
        registerApp(PictionaryGame.ID, new PictionaryGame(clock));
        registerApp(GuessGame.ENGLISH_SONG, GuessGame.fromResource(GuessGame.ENGLISH_SONG, clock, gameSongs));
        registerApp(GuessGame.EMOJI_MOVIE, GuessGame.fromResource(GuessGame.EMOJI_MOVIE, clock, null));
    }

    private static Catalog realCatalog() {
        final SaavnClient saavn = new SaavnClient();
        return new Catalog() {
            @Override
            public List<Song> search(String query) throws IOException {
                return saavn.search(query);
            }

            @Override
            public String resolveStream(Song song) throws IOException {
                return saavn.resolveStream(song);
            }
        };
    }

    public void start() throws IOException {
        http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
    }

    public void stop() {
        http.stop();
        playerExec.shutdown();
        lyricExec.shutdown();
        searchExec.shutdownNow();
    }

    int listeningPort() {
        return http.getListeningPort();
    }

    // ----- Player and remote events -----

    /**
     * Single-play guard. A second end event right after a song starts is stale and ignored, so it cannot skip the
     * new song. An end shortly after the start with the player truly stopped is real (a very short or broken file)
     * and still advances, otherwise the party would sit on a dead screen.
     */
    public void onSongEnded() {
        boolean playerActive = player.isPlaying();
        synchronized (lock) {
            gameSongOverLocked("off");
            if (now == null || !dispatched) return;
            boolean justStarted = clock.getAsLong() - startedAt < END_GUARD_MS;
            if (justStarted && (playerActive || paused)) return;
            lastError = null;
            advanceLocked();
        }
    }

    /** No time guard: a run of dead links can legitimately fail within milliseconds and must keep advancing. */
    public void onSongFailed(String message) {
        synchronized (lock) {
            gameSongOverLocked("failed");
            if (now == null || !dispatched) return;
            failLocked(message);
        }
    }

    /** The internet dropped: show the message but keep the song and the list. The player retries by itself. */
    public void onSongStalled(String message) {
        synchronized (lock) {
            if (now == null) return;
            stallMessage = message;
        }
    }

    public void onSongRecovered() {
        synchronized (lock) {
            stallMessage = null;
        }
    }

    private static boolean isNetworkFailure(Throwable e) {
        return e instanceof UnknownHostException || e instanceof ConnectException
                || e instanceof SocketTimeoutException || e instanceof NoRouteToHostException;
    }

    /** No internet while starting a song: keep it and the list, and try this song again shortly. */
    private void retryBeginLater(final Song song) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(networkRetryMs);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (lock) {
                    if (now == song) beginLocked(song);
                }
            }
        }, "karaoke-net-retry");
        t.setDaemon(true);
        t.start();
    }

    public void togglePause() {
        synchronized (lock) {
            if (now == null) return;
            if (musicHeld) {
                // A phone still on the Karaoke page for a moment. Change what happens on return, not the sound now.
                if (paused) hubPaused = !hubPaused;
                return;
            }
            paused = !paused;
            final boolean p = paused;
            runOnPlayer(new Runnable() {
                @Override
                public void run() {
                    player.setPaused(p);
                }
            });
        }
    }

    public void skip() {
        synchronized (lock) {
            long t = clock.getAsLong();
            if (now == null || t - lastSkipAt < SKIP_GUARD_MS) return;
            lastSkipAt = t;
            lastError = null;
            advanceLocked();
        }
    }

    private void failLocked(String message) {
        lastError = message == null || message.isEmpty() ? "This song could not be played" : message;
        advanceLocked();
    }

    private void advanceLocked() {
        stallMessage = null;
        now = null;
        dispatched = false;
        hubPaused = false;
        heldBack = null;
        Song next = queue.poll();
        if (next != null) {
            beginLocked(next);
            return;
        }
        final boolean wasPaused = paused;
        paused = false;
        runOnPlayer(new Runnable() {
            @Override
            public void run() {
                if (wasPaused) player.setPaused(false); // So the next song cannot inherit this pause.
                player.stop();
            }
        });
    }

    private void beginLocked(final Song song) {
        now = song;
        dispatched = false;
        startedAt = clock.getAsLong();
        final boolean wasPaused = paused;
        paused = false;
        runOnPlayer(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (now != song) return; // Skipped before it began; the newer task handles the player.
                    if (musicHeld) {
                        holdBackLocked(song);
                        return;
                    }
                }
                try {
                    if (wasPaused) player.setPaused(false);
                    if (song.streamUrl == null) {
                        String url = catalog.resolveStream(song);
                        synchronized (lock) {
                            song.streamUrl = url;
                        }
                    }
                    if (!waitBrieflyForLyrics(song)) return;
                    synchronized (lock) {
                        if (now != song) return;
                        if (musicHeld) {
                            holdBackLocked(song);
                            return;
                        }
                        dispatched = true;
                        stallMessage = null;
                        // The song now playing never waits for lyrics behind songs further down the list. A lookup
                        // that failed earlier gets one more try now it matters.
                        LyricsClient.Result lyrics = lyricsByUid.get(song.uid);
                        boolean failed = lyrics != null && lyrics.matched != null && lyrics.matched.startsWith("lookup failed");
                        boolean stillWaiting = lyrics != null && "loading".equals(lyrics.type) && !lyricClaimed.contains(song.uid);
                        if (failed) {
                            lyricClaimed.remove(song.uid);
                            lyricsByUid.put(song.uid, LyricsClient.LOADING);
                        }
                        if (failed || stillWaiting) fetchLyricsNow(song);
                    }
                    player.play(song);
                } catch (Exception e) {
                    synchronized (lock) {
                        if (now != song) return;
                        if (isNetworkFailure(e)) {
                            stallMessage = "Internet connection lost. The song will start when it is back.";
                            retryBeginLater(song);
                        } else {
                            failLocked("Could not start \"" + song.title + "\": " + e.getMessage());
                        }
                    }
                }
            }
        });
    }

    /** Silences any older song still loaded, which a later setPaused(false) would otherwise bring back. */
    private void holdBackLocked(Song song) {
        heldBack = song;
        stallMessage = null;
        runOnPlayer(new Runnable() {
            @Override
            public void run() {
                player.stop();
            }
        });
    }

    /**
     * Songs that open with singing would otherwise play under "Finding lyrics...". Holds the start until the lyrics
     * are in, for a short time only, so a slow lookup can never keep the room in silence. False if the song was
     * skipped while waiting.
     */
    private boolean waitBrieflyForLyrics(Song song) {
        long until = System.currentTimeMillis() + lyricWaitMs;
        synchronized (lock) {
            LyricsClient.Result lyrics = lyricsByUid.get(song.uid);
            if (lyricWaitMs > 0 && lyrics != null && "loading".equals(lyrics.type) && !lyricClaimed.contains(song.uid)) {
                fetchLyricsNow(song);
            }
        }
        while (System.currentTimeMillis() < until) {
            synchronized (lock) {
                if (now != song) return false;
                LyricsClient.Result lyrics = lyricsByUid.get(song.uid);
                if (lyrics == null || !"loading".equals(lyrics.type)) return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return true;
    }

    /** After stop() the executor refuses work, and a late player event must not crash the app for that. */
    private void runOnPlayer(Runnable r) {
        try {
            playerExec.execute(r);
        } catch (RejectedExecutionException e) {
            // Shutting down.
        }
    }

    // ----- A game's song -----

    /** For a game's song, this is what ended. Karaoke's own song has now set, so this never touches it. */
    private void gameSongOverLocked(String state) {
        if (now != null || !gameSongLoaded) return;
        gameSongLoaded = false;
        final GuessGame.SongListener listener = gameSongListener;
        if (listener != null) listener.onSongState(state);
    }

    GuessGame.SongHost gameSongs() {
        return gameSongs;
    }

    /**
     * One song on the TV for the app on screen. It stays out of now and the queue, so Karaoke never sees it. It is refused
     * whenever Karaoke holds any song, because the player has one slot and taking it would lose Karaoke's paused place.
     * Finding the song happens away from the player thread, so a slow lookup can never hold up Karaoke's commands.
     */
    private final class GameSongs implements GuessGame.SongHost {
        @Override
        public void play(final String owner, final String query, final GuessGame.SongListener listener) {
            final long mine;
            synchronized (lock) {
                if (!mayPlayLocked(owner)) {
                    mine = -1;
                } else {
                    mine = ++gameSongSeq;
                    gameSongOwner = owner;
                    gameSongListener = listener;
                }
            }
            if (mine < 0) {
                listener.onSongState("failed");
                return;
            }
            try {
                searchExec.execute(new Runnable() {
                    @Override
                    public void run() {
                        listener.onSongState(find(owner, query, mine) ? "playing" : "failed");
                    }
                });
            } catch (RejectedExecutionException e) {
                listener.onSongState("failed");
            }
        }

        private boolean mayPlayLocked(String owner) {
            return owner != null && owner.equals(activeApp) && now == null && queue.isEmpty() && heldBack == null;
        }

        private boolean find(String owner, String query, final long mine) {
            final Song song;
            try {
                List<Song> found = catalog.search(query);
                if (found == null || found.isEmpty() || found.get(0) == null) return false;
                song = copy(found.get(0));
                if (song.streamUrl == null) song.streamUrl = catalog.resolveStream(song);
                if (song.streamUrl == null) return false;
            } catch (Exception e) {
                return false; // No internet just means no song.
            }
            synchronized (lock) {
                if (mine != gameSongSeq || !mayPlayLocked(owner)) return false;
                gameSongLoaded = true;
                runOnPlayer(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (lock) {
                            if (mine != gameSongSeq || now != null) return;
                        }
                        player.play(song);
                    }
                });
            }
            return true;
        }

        @Override
        public void stop(String owner) {
            synchronized (lock) {
                if (owner == null || !owner.equals(gameSongOwner)) return;
                gameSongSeq++;
                gameSongOwner = null;
                gameSongListener = null;
                boolean loaded = gameSongLoaded;
                gameSongLoaded = false;
                // A dispatched song is Karaoke's, and that one is never stopped from here.
                if (!loaded || dispatched) return;
                runOnPlayer(new Runnable() {
                    @Override
                    public void run() {
                        player.stop();
                    }
                });
            }
        }
    }

    // ----- Queue -----

    /** Returns the uid, or null if the id is not in recent search results. */
    String enqueue(String id) {
        final Song song;
        synchronized (lock) {
            Song found = id == null ? null : recent.get(id);
            if (found == null) return null;
            song = copy(found);
            song.uid = "q" + nextUid++;
            LyricsClient.Result known = lyricsBySongId.get(song.id);
            lyricsByUid.put(song.uid, known != null ? known : LyricsClient.LOADING);
            if (known == null) fetchLyricsAsync(song);
            if (now == null) beginLocked(song);
            else queue.add(song);
        }
        return song.uid;
    }

    private void fetchLyricsAsync(final Song song) {
        try {
            lyricExec.execute(new Runnable() {
                @Override
                public void run() {
                    runLyricLookup(song);
                }
            });
        } catch (RejectedExecutionException e) {
            lyricsByUid.put(song.uid, LyricsClient.NONE);
        }
    }

    /** Its own thread: both shared workers may be busy, or stuck on a slow lookup for a song further down the list. */
    private void fetchLyricsNow(final Song song) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runLyricLookup(song);
            }
        }, "karaoke-lyrics-now");
        t.setDaemon(true);
        t.start();
    }

    /** Claims the song first, so a worker and the now-playing thread never look up the same entry twice. */
    private void runLyricLookup(Song song) {
        synchronized (lock) {
            if (!lyricClaimed.add(song.uid)) return;
            LyricsClient.Result known = lyricsBySongId.get(song.id);
            if (known != null) {
                lyricsByUid.put(song.uid, known);
                return;
            }
        }
        LyricsClient.Result r;
        try {
            r = lyricFetcher.fetch(song);
            if (r == null) r = LyricsClient.NONE;
        } catch (Throwable t) {
            r = LyricsClient.NONE;
        }
        synchronized (lock) {
            lyricsByUid.put(song.uid, r);
            // A miss may be a network blip, so only hits are reused for later requests.
            if (!"none".equals(r.type)) lyricsBySongId.put(song.id, r);
        }
    }

    boolean removeFromQueue(String uid) {
        synchronized (lock) {
            for (Iterator<Song> it = queue.iterator(); it.hasNext();) {
                if (it.next().uid.equals(uid)) {
                    it.remove();
                    return true;
                }
            }
            return false;
        }
    }

    private static Song copy(Song s) {
        Song c = new Song();
        c.id = s.id;
        c.title = s.title;
        c.artist = s.artist;
        c.album = s.album;
        c.image = s.image;
        c.durationSec = s.durationSec;
        c.source = s.source;
        c.streamUrl = s.streamUrl;
        return c;
    }

    // ----- Search -----

    Map<String, Object> search(String rawQuery) {
        final String q = rawQuery == null ? "" : rawQuery.trim();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Song> merged = new ArrayList<Song>();
        String error = null;
        if (!q.isEmpty()) {
            Set<String> seen = new HashSet<String>();
            try {
                List<Song> local = library == null ? null : library.search(q);
                if (local != null) addResults(merged, seen, local);
            } catch (RuntimeException e) {
                error = "USB search failed: " + e.getMessage();
            }
            // A stuck internet lookup can ignore connection timeouts. Answer the phone anyway, with the real reason.
            Future<List<Song>> online = searchExec.submit(new Callable<List<Song>>() {
                @Override
                public List<Song> call() throws IOException {
                    return catalog.search(q);
                }
            });
            try {
                addResults(merged, seen, online.get(searchLimitMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                online.cancel(true);
                error = "The internet is slow right now, so this search took too long. Please try again.";
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                error = "Online search is not working right now, so only USB songs are shown. Check the TV's internet. ("
                        + cause.getClass().getSimpleName() + ")";
            }
            synchronized (lock) {
                for (Song s : merged) recent.put(s.id, s);
            }
        }
        out.put("results", merged);
        if (error != null) out.put("error", error);
        return out;
    }

    private static void addResults(List<Song> merged, Set<String> seen, List<Song> add) {
        for (Song s : add) {
            if (merged.size() >= MAX_RESULTS) return;
            if (s != null && s.id != null && seen.add(s.id)) merged.add(s);
        }
    }

    // ----- State -----

    Map<String, Object> state() {
        // Read the player outside the lock. It is non-blocking by contract, but nothing here needs to rely on that.
        boolean playerPlaying = player.isPlaying();
        long position = player.positionMs();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        synchronized (lock) {
            if (lastError != null && now != null && dispatched && playerPlaying && position >= ERROR_CLEAR_AFTER_MS) {
                lastError = null;
            }
            out.put("joinUrl", joinUrl);
            out.put("now", now);
            out.put("positionMs", now == null || !dispatched ? 0L : position);
            out.put("playing", now != null && dispatched && !paused && playerPlaying);
            out.put("paused", now != null && paused);
            out.put("vocalCut", vocalCut);
            out.put("queue", new ArrayList<Song>(queue));
            out.put("lastError", stallMessage != null ? stallMessage : lastError);
        }
        return out;
    }

    private void toggleVocals() {
        synchronized (lock) {
            vocalCut = !vocalCut;
            final boolean on = vocalCut;
            runOnPlayer(new Runnable() {
                @Override
                public void run() {
                    player.setVocalCut(on);
                }
            });
        }
    }

    Map<String, Object> lyrics(String uid) {
        LyricsClient.Result r;
        synchronized (lock) {
            r = uid == null ? null : lyricsByUid.get(uid);
        }
        if (r == null) r = LyricsClient.NONE;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("uid", uid);
        out.put("type", r.type);
        out.put("matched", r.matched);
        out.put("lines", r.lines);
        return out;
    }

    /** Waits for queued player commands to finish. For tests. */
    void flushPlayerCommands() throws Exception {
        playerExec.submit(new Runnable() {
            @Override
            public void run() {}
        }).get(10, TimeUnit.SECONDS);
    }

    // ----- Hub -----

    /** Id of the app on every screen, or null for the home screen. */
    public String activeApp() {
        synchronized (lock) {
            return activeApp;
        }
    }

    public void goHome() {
        synchronized (lock) {
            changeActiveLocked(null);
        }
    }

    /** An unknown id changes nothing. */
    public void openApp(String id) {
        tryOpenApp(id);
    }

    boolean tryOpenApp(String id) {
        if (id == null || !appIds.contains(id)) return false;
        synchronized (lock) {
            changeActiveLocked(id);
        }
        return true;
    }

    /** A game with server state registers here. Its routes are /api/app/&lt;id&gt;/&lt;rest&gt;. */
    public void registerApp(String id, AppBackend backend) {
        if (id == null || !APP_ID.matcher(id).matches()) throw new IllegalArgumentException("Bad app id: " + id);
        if (backend == null) throw new IllegalArgumentException("No backend for " + id);
        backends.put(id, backend);
    }

    /**
     * Music rule. The first state, before anyone has opened anything, holds nothing. Leaving Karaoke for the home
     * screen or a game holds the music until Karaoke is open again, so a song can never start over a game.
     */
    private void changeActiveLocked(String id) {
        String old = activeApp;
        activeApp = id;
        if (old != null && !old.equals(id)) {
            AppBackend left = backends.get(old);
            if (left != null) {
                try {
                    left.onLeave();
                } catch (RuntimeException e) {
                    // A game's trouble must never stop the room moving, or the music rule below.
                }
            }
        }
        if (KARAOKE.equals(id)) releaseMusicLocked();
        else if (id != null || KARAOKE.equals(old)) holdMusicLocked();
    }

    private void holdMusicLocked() {
        if (musicHeld) return;
        musicHeld = true;
        // A song not dispatched yet sees musicHeld in its own task. Once dispatched, this pause runs after its play.
        if (now == null || !dispatched || paused) return;
        paused = true;
        hubPaused = true;
        runOnPlayer(new Runnable() {
            @Override
            public void run() {
                player.setPaused(true);
            }
        });
    }

    private void releaseMusicLocked() {
        if (!musicHeld) return;
        musicHeld = false;
        Song waiting = heldBack;
        heldBack = null;
        boolean resume = hubPaused;
        hubPaused = false;
        if (waiting != null) {
            if (waiting == now) {
                paused = true; // The player may have been left paused. beginLocked clears that before it plays.
                beginLocked(waiting);
            }
            return;
        }
        if (!resume || now == null || !paused) return;
        paused = false;
        runOnPlayer(new Runnable() {
            @Override
            public void run() {
                player.setPaused(false);
            }
        });
    }

    Map<String, Object> hubState() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("active", activeApp());
        out.put("apps", apps);
        out.put("joinUrl", joinUrl);
        return out;
    }

    private static String readAppsResource() {
        try (InputStream in = ApiServer.class.getResourceAsStream("/web/apps.json")) {
            return in == null ? null : new String(Http.readAll(in, 1024 * 1024), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Keeps only entries with a valid, unique id. A broken or empty list falls back to the built-in one. */
    void loadApps(String json) {
        JsonArray good = parseApps(json);
        if (good.size() == 0) good = parseApps(FALLBACK_APPS);
        Set<String> ids = new HashSet<String>();
        for (JsonElement e : good) ids.add(e.getAsJsonObject().get("id").getAsString());
        apps = good;
        appIds = ids;
    }

    private static JsonArray parseApps(String json) {
        JsonArray good = new JsonArray();
        if (json == null) return good;
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return good;
        }
        if (!root.isJsonArray()) return good;
        Set<String> seen = new HashSet<String>();
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject app = e.getAsJsonObject();
            JsonElement id = app.get("id");
            if (id == null || !id.isJsonPrimitive()) continue;
            String text = id.getAsString();
            if (APP_ID.matcher(text).matches() && seen.add(text)) good.add(app);
        }
        return good;
    }

    // ----- HTTP -----

    private final class WebServer extends NanoHTTPD {
        WebServer(int port) {
            super(port);
        }

        /** The TV polls twice a second over loopback; compressing that is wasted CPU on a slow chip. */
        @Override
        protected boolean useGzipWhenAccepted(Response r) {
            return false;
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {
                return route(session);
            } catch (Exception e) {
                return json(Response.Status.INTERNAL_ERROR, error(String.valueOf(e.getMessage())));
            }
        }

        private Response route(IHTTPSession s) throws Exception {
            String uri = s.getUri();
            if (uri == null) return json(Response.Status.BAD_REQUEST, error("Bad address"));
            if ("/".equals(uri)) return page("/web/hub/phone.html");
            if ("/tv".equals(uri)) return page("/web/hub/tv.html");
            if (uri.startsWith("/tv/")) return appPage(uri.substring("/tv/".length()), "tv.html");
            if (uri.startsWith("/phone/")) return appPage(uri.substring("/phone/".length()), "phone.html");
            if ("/shared/hub-client.js".equals(uri)) return script("/web/shared/hub-client.js");
            if ("/shared/guess-tv.js".equals(uri)) return script("/web/shared/guess-tv.js");
            if ("/shared/guess-phone.js".equals(uri)) return script("/web/shared/guess-phone.js");
            if ("/api/hub".equals(uri)) return json(Response.Status.OK, hubState());
            if ("/api/hub/open".equals(uri)) return hubOpenRoute(s);
            if ("/api/hub/home".equals(uri)) return hubHomeRoute(s);
            if (uri.startsWith("/api/app/")) return appRoute(s, uri.substring("/api/app/".length()));
            if ("/qr.svg".equals(uri)) {
                return bytes(Response.Status.OK, "image/svg+xml; charset=UTF-8", qr().getBytes(StandardCharsets.UTF_8));
            }
            if ("/api/search".equals(uri)) return json(Response.Status.OK, search(param(s, "q")));
            if ("/api/state".equals(uri)) return json(Response.Status.OK, state());
            if ("/api/lyrics".equals(uri)) return json(Response.Status.OK, lyrics(param(s, "uid")));
            if ("/api/queue".equals(uri) || "/api/queue/remove".equals(uri) || "/api/control".equals(uri)) {
                if (s.getMethod() != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, error("Use POST"));
                Map<String, String> body = readJson(s);
                if (body == null) return json(Response.Status.BAD_REQUEST, error("Body must be JSON"));
                if ("/api/queue".equals(uri)) return queueRoute(body);
                if ("/api/queue/remove".equals(uri)) return removeRoute(body);
                return controlRoute(body);
            }
            return json(Response.Status.NOT_FOUND, error("Not found"));
        }

        /** The id must match the strict pattern and be listed, so nothing from the address reaches a path unchecked. */
        private Response appPage(String id, String file) throws IOException {
            if (!APP_ID.matcher(id).matches() || !appIds.contains(id)) {
                return json(Response.Status.NOT_FOUND, error("Unknown app"));
            }
            return page("/web/apps/" + id + "/" + file);
        }

        private Response script(String resource) throws IOException {
            InputStream in = ApiServer.class.getResourceAsStream(resource);
            if (in == null) return json(Response.Status.NOT_FOUND, error("Not found"));
            return bytes(Response.Status.OK, "application/javascript; charset=UTF-8", Http.readAll(in, 8 * 1024 * 1024));
        }

        private Response hubOpenRoute(IHTTPSession s) throws IOException {
            if (s.getMethod() != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, error("Use POST"));
            Map<String, String> body = readJson(s);
            if (body == null || body.get("id") == null) return json(Response.Status.BAD_REQUEST, error("Body must be JSON with an id"));
            if (!tryOpenApp(body.get("id"))) return json(Response.Status.NOT_FOUND, error("Unknown app"));
            return json(Response.Status.OK, hubState());
        }

        private Response hubHomeRoute(IHTTPSession s) throws IOException {
            if (s.getMethod() != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, error("Use POST"));
            readJson(s); // No fields needed, but an unread body would corrupt the next request on this connection.
            goHome();
            return json(Response.Status.OK, hubState());
        }

        private Response appRoute(IHTTPSession s, String rest) throws IOException {
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "" : rest.substring(slash + 1);
            // Never getQueryParameterString(): NanoHTTPD keeps it from the previous request on a keep-alive connection, so
            // state polls after /qr.svg?u=... came through as state?u=... and every game answered 400.
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, List<String>> p : s.getParameters().entrySet()) {
                for (String v : p.getValue()) {
                    query.append(query.length() == 0 ? '?' : '&').append(p.getKey()).append('=').append(v);
                }
            }
            path += query; // For GET strokes?since=n. Games ignoring a query never get one from their own pages.
            AppBackend backend = APP_ID.matcher(id).matches() ? backends.get(id) : null;
            if (backend == null) return json(Response.Status.NOT_FOUND, error("Unknown app"));
            Map<String, String> body = null;
            if (s.getMethod() == Method.POST) {
                String len = s.getHeaders().get("content-length");
                if (len == null || "0".equals(len.trim())) {
                    body = new HashMap<String, String>();
                } else {
                    body = readJson(s);
                    if (body == null) return json(Response.Status.BAD_REQUEST, error("Body must be JSON"));
                }
            } else if (s.getMethod() != Method.GET) {
                return json(Response.Status.METHOD_NOT_ALLOWED, error("Use GET or POST"));
            }
            try {
                return json(Response.Status.OK, backend.handle(s.getMethod().name(), path, body));
            } catch (IllegalArgumentException e) {
                return json(Response.Status.BAD_REQUEST, error(String.valueOf(e.getMessage())));
            }
        }

        private Response queueRoute(Map<String, String> body) {
            String uid = enqueue(body.get("id"));
            if (uid == null) return json(Response.Status.NOT_FOUND, error("Song not found. Search again and retry."));
            Map<String, Object> ok = new LinkedHashMap<String, Object>();
            ok.put("ok", true);
            ok.put("uid", uid);
            return json(Response.Status.OK, ok);
        }

        private Response removeRoute(Map<String, String> body) {
            if (!removeFromQueue(body.get("uid"))) {
                return json(Response.Status.NOT_FOUND, error("That song is no longer in the queue."));
            }
            Map<String, Object> ok = new LinkedHashMap<String, Object>();
            ok.put("ok", true);
            return json(Response.Status.OK, ok);
        }

        private Response controlRoute(Map<String, String> body) {
            String action = body.get("action");
            if ("toggle_pause".equals(action)) togglePause();
            else if ("skip".equals(action)) skip();
            else if ("toggle_vocals".equals(action)) toggleVocals();
            else return json(Response.Status.BAD_REQUEST, error("Unknown action"));
            awaitPlayerCommands();
            return json(Response.Status.OK, state());
        }

        /** So the returned state reflects this action. Bounded: a slow stream lookup must not hold up the phone. */
        private void awaitPlayerCommands() {
            try {
                playerExec.submit(new Runnable() {
                    @Override
                    public void run() {}
                }).get(CONTROL_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // Timed out or shutting down. The next poll will have the true state.
            }
        }

        private String param(IHTTPSession s, String key) {
            List<String> v = s.getParameters().get(key);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        /** Reads the body by Content-Length, whatever content type the browser declared. */
        private Map<String, String> readJson(IHTTPSession s) throws IOException {
            String len = s.getHeaders().get("content-length");
            int n;
            try {
                n = len == null ? 0 : Integer.parseInt(len.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (n <= 0 || n > 64 * 1024) return null;
            byte[] buf = new byte[n];
            InputStream in = s.getInputStream();
            int got = 0;
            while (got < n) {
                int r = in.read(buf, got, n - got);
                if (r < 0) break;
                got += r;
            }
            try {
                JsonElement e = JsonParser.parseString(new String(buf, 0, got, StandardCharsets.UTF_8));
                if (!e.isJsonObject()) return null;
                Map<String, String> out = new HashMap<String, String>();
                for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
                    if (kv.getValue().isJsonPrimitive()) out.put(kv.getKey(), kv.getValue().getAsString());
                }
                return out;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private Response page(String resource) throws IOException {
            InputStream in = ApiServer.class.getResourceAsStream(resource);
            if (in == null) {
                String msg = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body>Page not ready: " + resource + "</body></html>";
                return bytes(Response.Status.OK, "text/html; charset=UTF-8", msg.getBytes(StandardCharsets.UTF_8));
            }
            return bytes(Response.Status.OK, "text/html; charset=UTF-8", Http.readAll(in, 8 * 1024 * 1024));
        }

        private Response json(Response.IStatus status, Object body) {
            return bytes(status, "application/json; charset=UTF-8", gson.toJson(body).getBytes(StandardCharsets.UTF_8));
        }

        private Response bytes(Response.IStatus status, String mime, byte[] data) {
            Response r = newFixedLengthResponse(status, mime, new ByteArrayInputStream(data), data.length);
            r.addHeader("Cache-Control", "no-store");
            return r;
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }

    private synchronized String qr() throws Exception {
        if (qrSvg == null) qrSvg = buildQrSvg(joinUrl);
        return qrSvg;
    }

    static String buildQrSvg(String text) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
        hints.put(EncodeHintType.MARGIN, 4);
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        int w = m.getWidth(), h = m.getHeight();
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                if (!m.get(x, y)) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < w && m.get(x, y)) x++;
                path.append('M').append(start).append(' ').append(y).append('h').append(x - start).append("v1h-").append(x - start).append('z');
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h + "\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + w + "\" height=\"" + h + "\" fill=\"#ffffff\"/>"
                + "<path fill=\"#000000\" d=\"" + path + "\"/></svg>";
    }

    private static <V> Map<String, V> lru(final int max) {
        return new LinkedHashMap<String, V>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> e) {
                return size() > max;
            }
        };
    }

    private static ThreadFactory daemon(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }
}
