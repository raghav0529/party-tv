package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiServerTest {
    private static final String HINDI = "गाना";

    private static final class FakePlayer implements PlayerControl {
        final List<String> played = Collections.synchronizedList(new ArrayList<String>());
        volatile int stops;
        volatile boolean paused, vocal, playing;
        volatile long pos;

        @Override public void play(Song s) { played.add(s.title); playing = true; }
        @Override public void stop() { stops++; playing = false; }
        @Override public void setPaused(boolean p) { paused = p; }
        @Override public void setVocalCut(boolean on) { vocal = on; }
        @Override public long positionMs() { return pos; }
        @Override public boolean isPlaying() { return playing && !paused; }
    }

    private FakePlayer player;
    private ApiServer api;
    private final AtomicLong clock = new AtomicLong(1000000);
    private volatile boolean catalogFails;
    private volatile boolean resolveFails;
    private volatile boolean resolveOffline;
    private volatile CountDownLatch holdSearch;
    private volatile CountDownLatch holdLyrics;
    private volatile String lyricsNotHeldFor;
    private volatile CountDownLatch holdResolve;
    private volatile LyricsClient.Result lyricResult = LyricsClient.NONE;
    private final String resolvedUrl = "https://x/resolved";

    private static Song song(String id, String title, String url) {
        Song s = new Song();
        s.id = id;
        s.title = title;
        s.artist = "Artist";
        s.source = "saavn";
        s.streamUrl = url;
        return s;
    }

    private final ApiServer.Catalog catalog = new ApiServer.Catalog() {
        @Override
        public List<Song> search(String q) throws IOException {
            CountDownLatch stuck = holdSearch;
            if (stuck != null) {
                try {
                    stuck.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (catalogFails) throw new IOException("offline");
            return Arrays.asList(song("a", "A", "https://x/a"), song("b", "B", "https://x/b"), song("c", "C", "https://x/c"),
                    song("d", HINDI, null), song("q:" + q, q, "https://x/q"));
        }

        @Override
        public String resolveStream(Song s) throws IOException {
            CountDownLatch hold = holdResolve;
            if (hold != null) {
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (resolveOffline) throw new java.net.UnknownHostException("offline");
            if (resolveFails) throw new IOException("no token");
            return resolvedUrl;
        }
    };

    @Before
    public void setUp() throws Exception {
        player = new FakePlayer();
        LocalLibrary lib = new LocalLibrary() {
            @Override
            public List<Song> search(String q) {
                Song u = song("usb:/m/x.mp3", "Usb Song", "/m/x.mp3");
                u.source = "usb";
                return Collections.singletonList(u);
            }
        };
        api = new ApiServer(0, player, lib, "http://192.0.2.10:8765/", catalog, new ApiServer.LyricFetcher() {
            @Override
            public LyricsClient.Result fetch(Song s) {
                CountDownLatch hold = holdLyrics;
                if (hold != null && !s.title.equals(lyricsNotHeldFor)) {
                    try {
                        hold.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        return LyricsClient.NONE;
                    }
                }
                return lyricResult;
            }
        }, clock::get);
        api.search("anything");
        api.start();
    }

    @After
    public void tearDown() {
        api.stop();
    }

    private void later() throws Exception {
        clock.addAndGet(ApiServer.END_GUARD_MS + 1);
        api.flushPlayerCommands();
    }

    @SuppressWarnings("unchecked")
    private List<Song> queue() {
        return (List<Song>) api.state().get("queue");
    }

    private Song now() {
        return (Song) api.state().get("now");
    }

    @Test
    public void firstSongAutoStartsAndRestQueue() throws Exception {
        assertNotNull(api.enqueue("a"));
        api.enqueue("b");
        api.flushPlayerCommands();
        assertEquals(Arrays.asList("A"), player.played);
        assertEquals("A", now().title);
        assertEquals(1, queue().size());
    }

    @Test
    public void unknownIdRejected() {
        assertNull(api.enqueue("nope"));
    }

    @Test
    public void sameSongTwiceGetsDistinctUids() {
        assertNotEquals(api.enqueue("a"), api.enqueue("a"));
    }

    @Test
    public void endAdvancesThenStopsWhenEmpty() throws Exception {
        api.enqueue("a");
        api.enqueue("b");
        later();
        api.onSongEnded();
        later();
        assertEquals(Arrays.asList("A", "B"), player.played);
        api.onSongEnded();
        later();
        assertNull(now());
        assertEquals(1, player.stops);
    }

    @Test
    public void doubleEndSkipsOnlyOneSong() throws Exception {
        api.enqueue("a");
        api.enqueue("b");
        api.enqueue("c");
        later();
        api.onSongEnded();
        api.onSongEnded();
        api.flushPlayerCommands();
        api.onSongEnded(); // after the next song has been dispatched, still within the guard window
        api.flushPlayerCommands();
        assertEquals("B", now().title);
        assertEquals(1, queue().size());
    }

    @Test
    public void realEndSoonAfterStartStillAdvances() throws Exception {
        api.enqueue("a");
        api.enqueue("b");
        api.flushPlayerCommands();
        player.playing = false; // a broken or tiny file: the player really has stopped
        api.onSongEnded();
        api.flushPlayerCommands();
        assertEquals("B", now().title);
    }

    @Test
    public void doubleSkipSkipsOnlyOneSong() throws Exception {
        api.enqueue("a");
        api.enqueue("b");
        api.enqueue("c");
        later();
        api.skip();
        api.skip();
        api.flushPlayerCommands();
        assertEquals("B", now().title);
        clock.addAndGet(ApiServer.SKIP_GUARD_MS + 1);
        api.skip();
        api.flushPlayerCommands();
        assertEquals("C", now().title);
    }

    @Test
    public void removeOnlyUpcoming() throws Exception {
        String a = api.enqueue("a");
        String b = api.enqueue("b");
        api.enqueue("c");
        assertFalse(api.removeFromQueue(a));
        assertTrue(api.removeFromQueue(b));
        assertFalse(api.removeFromQueue(b));
        assertEquals(1, queue().size());
        assertEquals("C", queue().get(0).title);
    }

    @Test
    public void failureSetsErrorAdvancesAndClearsOnceNextPlays() throws Exception {
        api.enqueue("a");
        api.enqueue("b");
        api.flushPlayerCommands();
        api.onSongFailed("dead link");
        api.flushPlayerCommands();
        assertEquals("B", now().title);
        assertEquals("dead link", api.state().get("lastError"));
        player.pos = ApiServer.ERROR_CLEAR_AFTER_MS;
        assertNull(api.state().get("lastError"));
    }

    @Test
    public void songNowPlayingGetsLyricsEvenWhenBothWorkersAreStuckOnOtherSongs() throws Exception {
        holdLyrics = new CountDownLatch(1);
        lyricsNotHeldFor = "C";
        lyricResult = new LyricsClient.Result("synced", Arrays.asList(new LrcParser.Line(1000, "line")), "test");
        api.enqueue("a");
        api.enqueue("b");
        String c = api.enqueue("c");
        api.flushPlayerCommands();
        Thread.sleep(100); // Both workers now hold A and B, and C waits behind them.
        assertEquals("loading", api.lyrics(c).get("type"));
        clock.addAndGet(ApiServer.SKIP_GUARD_MS + 1);
        api.skip();
        clock.addAndGet(ApiServer.SKIP_GUARD_MS + 1);
        api.skip();
        api.flushPlayerCommands();
        assertEquals("C", now().title);
        Object type = null;
        for (int i = 0; i < 100 && !"synced".equals(type); i++) {
            type = api.lyrics(c).get("type");
            Thread.sleep(20);
        }
        holdLyrics.countDown();
        assertEquals("synced", type);
    }

    @Test
    public void songWaitsBrieflyForItsLyricsThenPlays() throws Exception {
        api.lyricWaitMs = 3000;
        holdLyrics = new CountDownLatch(1);
        lyricResult = new LyricsClient.Result("synced", Arrays.asList(new LrcParser.Line(1000, "line")), "test");
        api.enqueue("a");
        Thread.sleep(300);
        assertTrue("must not play while lyrics are being found", player.played.isEmpty());
        holdLyrics.countDown();
        api.flushPlayerCommands();
        assertEquals(Arrays.asList("A"), player.played);
    }

    @Test
    public void slowLyricsNeverKeepTheRoomInSilence() throws Exception {
        api.lyricWaitMs = 200;
        holdLyrics = new CountDownLatch(1);
        api.enqueue("a");
        api.flushPlayerCommands();
        assertEquals(Arrays.asList("A"), player.played);
        holdLyrics.countDown();
    }

    @Test
    public void skippingDuringTheLyricWaitDoesNotPlayTheSkippedSong() throws Exception {
        api.lyricWaitMs = 2000;
        holdLyrics = new CountDownLatch(1);
        api.enqueue("a");
        api.enqueue("b");
        Thread.sleep(150);
        clock.addAndGet(ApiServer.SKIP_GUARD_MS + 1);
        api.skip();
        holdLyrics.countDown();
        api.flushPlayerCommands();
        assertFalse(player.played.contains("A"));
    }

    @Test
    public void stuckSearchAnswersWithAClearMessageInsteadOfHanging() {
        holdSearch = new CountDownLatch(1);
        api.searchLimitMs = 100;
        long started = System.currentTimeMillis();
        java.util.Map<String, Object> out = api.search("anything");
        holdSearch.countDown();
        assertTrue(System.currentTimeMillis() - started < 2000);
        assertTrue(String.valueOf(out.get("error")).contains("slow"));
    }

    @Test
    public void internetDropKeepsSongAndListUntilRecovered() throws Exception {
        api.enqueue("a");
        api.enqueue("b");
        api.flushPlayerCommands();
        api.onSongStalled("Internet connection lost.");
        player.pos = ApiServer.ERROR_CLEAR_AFTER_MS + 1000;
        assertEquals("A", now().title);
        assertEquals("Internet connection lost.", api.state().get("lastError"));
        assertEquals(1, ((java.util.List<?>) api.state().get("queue")).size());
        api.onSongRecovered();
        assertNull(api.state().get("lastError"));
        assertEquals("A", now().title);
    }

    @Test
    public void noInternetWhileStartingKeepsTheSongAndRetries() throws Exception {
        resolveOffline = true;
        api.networkRetryMs = 50;
        api.enqueue("d");
        api.enqueue("b");
        api.flushPlayerCommands();
        assertEquals(HINDI, now().title);
        assertEquals(1, ((java.util.List<?>) api.state().get("queue")).size());
        assertNotNull(api.state().get("lastError"));
        resolveOffline = false;
        Thread.sleep(300);
        api.flushPlayerCommands();
        api.onSongRecovered();
        assertEquals(HINDI, now().title);
        assertNull(api.state().get("lastError"));
    }

    @Test
    public void runOfFailuresWalksTheWholeQueue() throws Exception {
        api.enqueue("a");
        api.enqueue("b");
        api.enqueue("c");
        for (int i = 0; i < 3; i++) {
            api.flushPlayerCommands();
            api.onSongFailed("dead link");
        }
        api.flushPlayerCommands();
        assertEquals(Arrays.asList("A", "B", "C"), player.played);
        assertNull(now());
    }

    @Test
    public void failureOnLastSongEmptiesAndKeepsError() throws Exception {
        api.enqueue("a");
        api.flushPlayerCommands();
        api.onSongFailed("dead link");
        api.flushPlayerCommands();
        assertNull(now());
        assertEquals("dead link", api.state().get("lastError"));
    }

    @Test
    public void eventBeforePlayIsDispatchedCannotSkipThatSong() throws Exception {
        holdResolve = new CountDownLatch(1);
        api.enqueue("d");
        api.enqueue("a");
        clock.addAndGet(ApiServer.END_GUARD_MS + 1);
        api.onSongEnded();
        api.onSongFailed("stale");
        assertEquals(HINDI, now().title);
        assertNull(api.state().get("lastError"));
        holdResolve.countDown();
        api.flushPlayerCommands();
        assertEquals(Arrays.asList(HINDI), player.played);
    }

    @Test
    public void missingUrlResolvedAtPlayTime() throws Exception {
        api.enqueue("d");
        api.flushPlayerCommands();
        assertEquals(resolvedUrl, now().streamUrl);
        assertEquals(1, player.played.size());
    }

    @Test
    public void resolveFailureAdvancesWithError() throws Exception {
        resolveFails = true;
        api.enqueue("d");
        api.enqueue("a");
        api.flushPlayerCommands();
        api.flushPlayerCommands();
        assertEquals("A", now().title);
        assertNotNull(api.state().get("lastError"));
    }

    @Test
    public void pausedFlagFollowsUserAndResetsOnNextSong() throws Exception {
        api.enqueue("a");
        api.enqueue("b");
        api.flushPlayerCommands();
        assertEquals(false, api.state().get("paused"));
        api.togglePause();
        api.flushPlayerCommands();
        assertEquals(true, api.state().get("paused"));
        assertEquals(false, api.state().get("playing"));
        assertTrue(player.paused);
        api.skip();
        api.flushPlayerCommands();
        assertEquals(false, api.state().get("paused"));
        assertFalse(player.paused);
        assertEquals(true, api.state().get("playing"));
    }

    @Test
    public void pausedIsFalseWhileBufferingLooksNotPlaying() throws Exception {
        api.enqueue("a");
        api.flushPlayerCommands();
        player.playing = false;
        assertEquals(false, api.state().get("playing"));
        assertEquals(false, api.state().get("paused"));
    }

    @Test
    public void staleEndWhilePausedDoesNotSkip() throws Exception {
        api.enqueue("a");
        api.enqueue("b");
        api.flushPlayerCommands();
        api.togglePause();
        api.flushPlayerCommands();
        api.onSongEnded();
        api.flushPlayerCommands();
        assertEquals("A", now().title);
    }

    @Test
    public void failedSaavnSearchKeepsLocalAndReportsError() {
        catalogFails = true;
        Map<String, Object> r = api.search("x");
        assertEquals(1, ((List<?>) r.get("results")).size());
        assertTrue(r.get("error") instanceof String);
    }

    @Test
    public void usbMatchesFirst() {
        List<?> r = (List<?>) api.search("x").get("results");
        assertEquals("usb:/m/x.mp3", ((Song) r.get(0)).id);
        assertFalse(api.search("x").containsKey("error"));
    }

    @Test
    public void qrHasQuietZoneAndPath() throws Exception {
        String svg = ApiServer.buildQrSvg("http://192.0.2.10:8765/");
        assertTrue(svg.startsWith("<svg"));
        assertTrue(svg.contains("#000000"));
        assertTrue(svg.contains("M4 4h7"));
        assertFalse(svg.contains("M0 0"));
    }

    // ----- Over real HTTP -----

    private static final class Reply {
        int status;
        String contentType;
        String body;

        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private Reply http(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + api.listeningPort() + path).openConnection();
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Reply r = new Reply();
        r.status = c.getResponseCode();
        r.contentType = c.getContentType();
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = new String(Http.readAll(in, 8 * 1024 * 1024), StandardCharsets.UTF_8);
        return r;
    }

    @Test
    public void httpSearchRoundTripsDevanagariAndHidesStreamUrl() throws Exception {
        Reply r = http("GET", "/api/search?q=" + URLEncoder.encode(HINDI, "UTF-8"), null);
        assertEquals(200, r.status);
        assertTrue(r.contentType.toLowerCase().contains("charset=utf-8"));
        assertTrue(r.body.contains(HINDI));
        assertFalse(r.body.contains("streamUrl"));
        assertFalse(r.body.contains("https://x/"));
    }

    @Test
    public void httpFailedSearchIsNot500() throws Exception {
        catalogFails = true;
        Reply r = http("GET", "/api/search?q=x", null);
        assertEquals(200, r.status);
        assertTrue(r.json().has("error"));
        assertEquals(1, r.json().getAsJsonArray("results").size());
    }

    @Test
    public void httpQueueStateAndControl() throws Exception {
        Reply q = http("POST", "/api/queue", "{\"id\":\"a\"}");
        assertEquals(200, q.status);
        assertTrue(q.json().get("ok").getAsBoolean());
        api.flushPlayerCommands();

        JsonObject s = http("GET", "/api/state", null).json();
        for (String k : new String[] {"joinUrl", "now", "positionMs", "playing", "paused", "vocalCut", "queue", "lastError"}) {
            assertTrue(k, s.has(k));
        }
        assertEquals("A", s.getAsJsonObject("now").get("title").getAsString());
        assertTrue(s.get("lastError").isJsonNull());
        assertFalse(s.get("vocalCut").getAsBoolean());

        JsonObject v = http("POST", "/api/control", "{\"action\":\"toggle_vocals\"}").json();
        assertTrue(v.get("vocalCut").getAsBoolean());
        api.flushPlayerCommands();
        assertTrue(player.vocal);

        JsonObject p = http("POST", "/api/control", "{\"action\":\"toggle_pause\"}").json();
        assertTrue(p.get("paused").getAsBoolean());
        assertFalse(p.get("playing").getAsBoolean());

        JsonObject resumed = http("POST", "/api/control", "{\"action\":\"toggle_pause\"}").json();
        assertFalse(resumed.get("paused").getAsBoolean());
        assertTrue(resumed.get("playing").getAsBoolean());

        assertEquals(400, http("POST", "/api/control", "{\"action\":\"nope\"}").status);
    }

    @Test
    public void httpErrorsAreJson() throws Exception {
        Reply missing = http("POST", "/api/queue", "{\"id\":\"nope\"}");
        assertEquals(404, missing.status);
        assertFalse(missing.json().get("ok").getAsBoolean());
        assertEquals(400, http("POST", "/api/queue", "not json").status);
        assertEquals(404, http("POST", "/api/queue/remove", "{\"uid\":\"x\"}").status);
        assertEquals(405, http("GET", "/api/queue", null).status);
        assertEquals(404, http("GET", "/api/nothing", null).status);
    }

    @Test
    public void httpRemove() throws Exception {
        api.enqueue("a");
        String uid = api.enqueue("b");
        Reply r = http("POST", "/api/queue/remove", "{\"uid\":\"" + uid + "\"}");
        assertEquals(200, r.status);
        assertTrue(r.json().get("ok").getAsBoolean());
        assertTrue(queue().isEmpty());
    }

    @Test
    public void httpLyricsLoadingThenResult() throws Exception {
        holdLyrics = new CountDownLatch(1);
        lyricResult = new LyricsClient.Result("synced", Arrays.asList(new LrcParser.Line(12340, HINDI)), "test");
        String uid = api.enqueue("a");
        assertEquals("loading", http("GET", "/api/lyrics?uid=" + uid, null).json().get("type").getAsString());
        holdLyrics.countDown();
        JsonObject j = null;
        for (int i = 0; i < 100; i++) {
            j = http("GET", "/api/lyrics?uid=" + uid, null).json();
            if (!"loading".equals(j.get("type").getAsString())) break;
            Thread.sleep(20);
        }
        assertEquals("synced", j.get("type").getAsString());
        assertEquals(uid, j.get("uid").getAsString());
        JsonObject line = j.getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals(12340, line.get("t").getAsLong());
        assertEquals(HINDI, line.get("text").getAsString());
        assertEquals("none", http("GET", "/api/lyrics?uid=unknown", null).json().get("type").getAsString());
    }

    @Test
    public void httpPagesAndQr() throws Exception {
        Reply phone = http("GET", "/", null);
        assertEquals(200, phone.status);
        assertTrue(phone.contentType.toLowerCase().contains("text/html"));
        assertTrue(phone.contentType.toLowerCase().contains("utf-8"));
        assertEquals(200, http("GET", "/tv", null).status);
        Reply qr = http("GET", "/qr.svg?u=abc", null);
        assertEquals(200, qr.status);
        assertTrue(qr.contentType.contains("image/svg+xml"));
    }
}
