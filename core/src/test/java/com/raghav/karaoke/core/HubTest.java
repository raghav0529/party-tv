package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
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

public class HubTest {
    private static final class FakePlayer implements PlayerControl {
        final List<String> played = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean paused, playing;

        @Override public void play(Song s) { played.add(s.title); playing = true; }
        @Override public void stop() { playing = false; }
        @Override public void setPaused(boolean p) { paused = p; }
        @Override public void setVocalCut(boolean on) {}
        @Override public long positionMs() { return 0; }
        @Override public boolean isPlaying() { return playing && !paused; }

        boolean audible() { return playing && !paused; }
    }

    private FakePlayer player;
    private ApiServer api;
    private final AtomicLong clock = new AtomicLong(1000000);
    private volatile CountDownLatch holdResolve;

    private static Song song(String id, String title, String url) {
        Song s = new Song();
        s.id = id;
        s.title = title;
        s.source = "saavn";
        s.streamUrl = url;
        return s;
    }

    @Before
    public void setUp() throws Exception {
        player = new FakePlayer();
        LocalLibrary none = new LocalLibrary() {
            @Override
            public List<Song> search(String q) {
                return Collections.emptyList();
            }
        };
        ApiServer.Catalog catalog = new ApiServer.Catalog() {
            @Override
            public List<Song> search(String q) {
                return Arrays.asList(song("a", "A", "https://x/a"), song("b", "B", "https://x/b"), song("n", "Needs url", null));
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
                return "https://x/resolved";
            }
        };
        api = new ApiServer(0, player, none, "http://192.0.2.10:8765/", catalog, new ApiServer.LyricFetcher() {
            @Override
            public LyricsClient.Result fetch(Song s) {
                return LyricsClient.NONE;
            }
        }, clock::get);
        api.search("anything");
        api.start();
    }

    @After
    public void tearDown() {
        api.stop();
    }

    private Song now() {
        return (Song) api.state().get("now");
    }

    private boolean pausedInState() {
        return (Boolean) api.state().get("paused");
    }

    // ----- HTTP helpers -----

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

    /** Sends the path byte for byte. java.net.URL would tidy a "/../" away before the server ever saw it. */
    private String rawGet(String path) throws Exception {
        try (Socket s = new Socket("127.0.0.1", api.listeningPort())) {
            s.setSoTimeout(5000);
            OutputStream o = s.getOutputStream();
            o.write(("GET " + path + " HTTP/1.0\r\nHost: x\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
            } catch (IOException e) {
                // A reset after a refused request reads as no more data.
            }
            return new String(all.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = ApiServer.class.getResourceAsStream(path)) {
            return Http.readAll(in, 8 * 1024 * 1024);
        }
    }

    // ----- Pages -----

    @Test
    public void hubPagesAreServedAsHtml() throws Exception {
        for (String path : new String[] {"/", "/tv"}) {
            Reply r = http("GET", path, null);
            assertEquals(path, 200, r.status);
            assertTrue(path, r.contentType.toLowerCase().contains("text/html"));
        }
    }

    @Test
    public void karaokePagesAreServedById() throws Exception {
        assertEquals(new String(resource("/web/apps/karaoke/tv.html"), StandardCharsets.UTF_8), http("GET", "/tv/karaoke", null).body);
        assertEquals(new String(resource("/web/apps/karaoke/phone.html"), StandardCharsets.UTF_8), http("GET", "/phone/karaoke", null).body);
    }

    @Test
    public void listedAppWithNoPageYetGetsAPlaceholderNotACrash() throws Exception {
        api.loadApps("[{\"id\":\"ghost-game\",\"title\":\"Ghost\"},{\"id\":\"karaoke\",\"title\":\"K\"}]");
        Reply r = http("GET", "/tv/ghost-game", null);
        assertEquals(200, r.status);
        assertTrue(r.contentType.toLowerCase().contains("text/html"));
        assertTrue(r.body.contains("Page not ready"));
    }

    @Test
    public void unknownAppPagesAre404() throws Exception {
        assertEquals(404, http("GET", "/tv/nope", null).status);
        assertEquals(404, http("GET", "/phone/nope", null).status);
        assertEquals(404, http("GET", "/tv/", null).status);
        assertEquals(404, http("GET", "/phone/", null).status);
    }

    @Test
    public void pathEscapesNeverLeaveTheAppFolder() throws Exception {
        String[] attacks = {
            "/tv/../apps.json", "/phone/../apps.json", "/tv/../../web/apps.json", "/tv/..", "/tv/.", "/phone/..",
            "/tv/..%2f..%2fapps.json", "/tv/..%2Fapps.json", "/tv/%2e%2e/apps.json", "/tv/%2e%2e%2fapps.json", "/tv/%2E%2E",
            "/phone/%2e%2e%2f%2e%2e%2fshared%2fhub-client.js", "/tv/..%5c..%5capps.json", "/tv/..\\..\\apps.json",
            "/tv/%252e%252e%252fapps.json", "/tv/karaoke/../../apps.json", "/tv/karaoke/..", "/tv/karaoke%2f..%2f..%2fapps.json",
            "/tv/karaoke%00", "/tv/karaoke%0a", "/tv/karaoke/", "/tv/KARAOKE", "/tv//karaoke", "/tv/.karaoke", "/tv/karaoke.html",
            "/tv/%c0%ae%c0%ae%c0%afapps.json", "/tv/%zz",
        };
        String appsMarker = "Spin a voice";
        String clientMarker = "HubClient";
        for (String path : attacks) {
            String reply = rawGet(path);
            assertFalse(path + " served: " + reply, reply.startsWith("HTTP/1.1 200") || reply.startsWith("HTTP/1.0 200"));
            assertFalse(path, reply.contains(appsMarker));
            assertFalse(path, reply.contains(clientMarker));
        }
        assertEquals("The server survives all of that", 200, http("GET", "/api/hub", null).status);
    }

    @Test
    public void appBackendRoutesRejectEscapesToo() throws Exception {
        for (String path : new String[] {"/api/app/../state", "/api/app/%2e%2e/state", "/api/app/..%2fstate", "/api/app/", "/api/app/nope/state", "/api/app/ACCENT-WHEEL/state"}) {
            assertTrue(path, rawGet(path).startsWith("HTTP/1.1 404"));
        }
    }

    @Test
    public void sharedScriptIsServedAsJavascript() throws Exception {
        Reply r = http("GET", "/shared/hub-client.js", null);
        assertEquals(200, r.status);
        assertTrue(r.contentType.contains("javascript"));
        assertTrue(r.body.contains("HubClient"));
        assertTrue(r.body.contains("location.replace"));
    }

    @Test
    public void karaokeRoutesStillWork() throws Exception {
        assertEquals(200, http("GET", "/api/state", null).status);
        assertEquals(200, http("GET", "/api/search?q=x", null).status);
        assertEquals(200, http("GET", "/api/lyrics?uid=x", null).status);
        assertEquals(200, http("GET", "/qr.svg", null).status);
        assertEquals(404, http("POST", "/api/queue", "{\"id\":\"nope\"}").status);
        assertEquals(400, http("POST", "/api/control", "{\"action\":\"nope\"}").status);
    }

    // ----- Hub state -----

    @Test
    public void hubStartsHomeWithTheAppList() throws Exception {
        JsonObject hub = http("GET", "/api/hub", null).json();
        assertTrue(hub.get("active").isJsonNull());
        assertEquals("http://192.0.2.10:8765/", hub.get("joinUrl").getAsString());
        JsonArray apps = hub.getAsJsonArray("apps");
        assertTrue("Karaoke and the games are listed", apps.size() >= 3);
        JsonObject first = apps.get(0).getAsJsonObject();
        assertEquals("karaoke", first.get("id").getAsString());
        assertEquals("Karaoke", first.get("title").getAsString());
        assertEquals("Pick songs from your phone and sing", first.get("subtitle").getAsString());
        assertEquals("mic", first.get("icon").getAsString());
        assertTrue(first.get("backend").getAsBoolean());
        assertEquals("accent-wheel", apps.get(1).getAsJsonObject().get("id").getAsString());
        assertNull(api.activeApp());
    }

    @Test
    public void openAndHomeOverHttp() throws Exception {
        Reply opened = http("POST", "/api/hub/open", "{\"id\":\"accent-wheel\"}");
        assertEquals(200, opened.status);
        assertEquals("accent-wheel", opened.json().get("active").getAsString());
        assertEquals("accent-wheel", api.activeApp());
        assertEquals("accent-wheel", http("GET", "/api/hub", null).json().get("active").getAsString());
        Reply home = http("POST", "/api/hub/home", null);
        assertEquals(200, home.status);
        assertTrue(home.json().get("active").isJsonNull());
        assertNull(api.activeApp());
    }

    @Test
    public void homeWithABodyDoesNotBreakTheConnection() throws Exception {
        assertEquals(200, http("POST", "/api/hub/home", "{}").status);
        assertEquals(200, http("GET", "/api/hub", null).status);
    }

    @Test
    public void openRejectsUnknownAndBadRequests() throws Exception {
        api.openApp("karaoke");
        assertEquals(404, http("POST", "/api/hub/open", "{\"id\":\"nope\"}").status);
        assertEquals(404, http("POST", "/api/hub/open", "{\"id\":\"../apps\"}").status);
        assertEquals(400, http("POST", "/api/hub/open", "{}").status);
        assertEquals(400, http("POST", "/api/hub/open", "not json").status);
        assertEquals(405, http("GET", "/api/hub/open", null).status);
        assertEquals(405, http("GET", "/api/hub/home", null).status);
        assertEquals("karaoke", api.activeApp());
    }

    @Test
    public void publicOpenWithUnknownIdChangesNothing() {
        api.openApp("karaoke");
        api.openApp("nope");
        api.openApp(null);
        assertEquals("karaoke", api.activeApp());
    }

    @Test
    public void brokenAppListFallsBackToBuiltIn() throws Exception {
        api.loadApps("this is not json");
        assertEquals(2, http("GET", "/api/hub", null).json().getAsJsonArray("apps").size());
        api.loadApps("[]");
        assertEquals(2, http("GET", "/api/hub", null).json().getAsJsonArray("apps").size());
        api.loadApps(null);
        assertEquals(200, http("GET", "/tv/karaoke", null).status);
    }

    @Test
    public void appListDropsInvalidIds() throws Exception {
        api.loadApps("[{\"id\":\"../evil\"},{\"title\":\"no id\"},7,{\"id\":\"good-one\"},{\"id\":\"good-one\"}]");
        JsonArray apps = http("GET", "/api/hub", null).json().getAsJsonArray("apps");
        assertEquals(1, apps.size());
        assertEquals("good-one", apps.get(0).getAsJsonObject().get("id").getAsString());
    }

    // ----- App backends -----

    @Test
    public void registeredBackendGetsMethodPathAndBody() throws Exception {
        final List<String> seen = new ArrayList<String>();
        api.registerApp("echo", new AppBackend() {
            @Override
            public Object handle(String method, String path, Map<String, String> body) {
                if ("boom".equals(path)) throw new IllegalArgumentException("bad thing");
                seen.add(method + " " + path + " " + body);
                return Collections.singletonMap("ok", true);
            }
        });
        assertEquals(200, http("GET", "/api/app/echo/deep/path", null).status);
        assertEquals(200, http("POST", "/api/app/echo/act", "{\"x\":\"1\"}").status);
        assertEquals(200, http("POST", "/api/app/echo/act", null).status);
        assertEquals(Arrays.asList("GET deep/path null", "POST act {x=1}", "POST act {}"), seen);
        Reply bad = http("GET", "/api/app/echo/boom", null);
        assertEquals(400, bad.status);
        assertEquals("bad thing", bad.json().get("error").getAsString());
        assertEquals(400, http("POST", "/api/app/echo/act", "not json").status);
        assertEquals(405, http("DELETE", "/api/app/echo/act", null).status);
        assertEquals(404, http("GET", "/api/app/nobody/act", null).status);
    }

    @Test
    public void registerRejectsBadIds() {
        AppBackend b = new AppBackend() {
            @Override
            public Object handle(String method, String path, Map<String, String> body) {
                return null;
            }
        };
        for (String id : new String[] {null, "", "../x", "A", "a/b", "a.b"}) {
            try {
                api.registerApp(id, b);
                fail(String.valueOf(id));
            } catch (IllegalArgumentException expected) {
                // Good.
            }
        }
    }

    @Test
    public void accentWheelIsRegisteredByDefaultAndPlaysOverHttp() throws Exception {
        JsonObject s = http("GET", "/api/app/accent-wheel/state", null).json();
        assertEquals("ready", s.get("phase").getAsString());
        assertTrue(s.get("voice").isJsonNull());
        String action = "/api/app/accent-wheel/action";
        assertEquals("p1", http("POST", action, "{\"action\":\"add_player\",\"name\":\"Raghav\"}").json().get("currentPlayerId").getAsString());
        s = http("POST", action, "{\"action\":\"spin\"}").json();
        assertEquals("spinning", s.get("phase").getAsString());
        assertEquals("Absurd", s.getAsJsonObject("voice").get("group").getAsString());
        clock.addAndGet(AccentWheelGame.SPIN_MS);
        assertEquals("landed", http("GET", "/api/app/accent-wheel/state", null).json().get("phase").getAsString());
        assertEquals("performing", http("POST", action, "{\"action\":\"start_timer\"}").json().get("phase").getAsString());
        assertEquals("scoring", http("POST", action, "{\"action\":\"stop_timer\"}").json().get("phase").getAsString());
        // Real JSON numbers and booleans work as well as strings.
        s = http("POST", action, "{\"action\":\"score\",\"points\":4,\"commitment\":true,\"crack\":false,\"stealerId\":null}").json();
        assertEquals(7, s.getAsJsonArray("players").get(0).getAsJsonObject().get("score").getAsInt());
    }

    @Test
    public void accentWheelBadRequestsAre400() throws Exception {
        String action = "/api/app/accent-wheel/action";
        assertEquals(400, http("POST", action, "{\"action\":\"dance\"}").status);
        assertEquals(400, http("POST", action, "{}").status);
        assertEquals(400, http("POST", action, null).status);
        assertEquals(400, http("POST", action, "not json").status);
        assertEquals(400, http("POST", action, "{\"action\":\"start_timer\"}").status);
        assertEquals(400, http("GET", "/api/app/accent-wheel/other", null).status);
        assertFalse(http("POST", action, "{\"action\":\"dance\"}").json().get("ok").getAsBoolean());
    }

    @Test
    public void accentWheelStateSurvivesMovingBetweenApps() throws Exception {
        String action = "/api/app/accent-wheel/action";
        http("POST", action, "{\"action\":\"add_player\",\"name\":\"Raghav\"}");
        api.openApp("accent-wheel");
        api.goHome();
        api.openApp("karaoke");
        api.openApp("accent-wheel");
        assertEquals(1, http("GET", "/api/app/accent-wheel/state", null).json().getAsJsonArray("players").size());
    }

    // ----- Reverse Charades -----

    @Test
    public void reverseCharadesKeepsThePromptOffStateAndTvOverHttp() throws Exception {
        String base = "/api/app/reverse-charades/";
        assertEquals("ready", http("GET", base + "state", null).json().get("phase").getAsString());
        assertEquals(0, http("GET", base + "prompt", null).json().size());
        http("POST", base + "action", "{\"action\":\"start\"}");
        clock.addAndGet(ReverseCharadesGame.COUNTDOWN_MS);

        JsonObject prompt = http("GET", base + "prompt", null).json();
        String text = prompt.get("text").getAsString();
        assertEquals(http("GET", base + "state", null).json().get("promptId").getAsString(), prompt.get("promptId").getAsString());
        assertFalse(http("GET", base + "state", null).body.contains(text));
        assertFalse(http("GET", base + "tv-state", null).body.contains(text));
        assertEquals(400, http("POST", base + "action", "{\"action\":\"dance\"}").status);

        http("POST", base + "action", "{\"action\":\"pause\"}");
        http("POST", base + "action", "{\"action\":\"end_turn\"}");
        assertEquals(200, http("POST", base + "action", "{\"action\":\"set_prompt_on_tv\",\"on\":true}").status);
        assertTrue(http("GET", base + "state", null).json().get("promptOnTv").getAsBoolean());
        http("POST", base + "action", "{\"action\":\"next_turn\"}");
        http("POST", base + "action", "{\"action\":\"start\"}");
        clock.addAndGet(ReverseCharadesGame.COUNTDOWN_MS);
        assertTrue(http("GET", base + "tv-state", null).json().has("prompt"));
        assertFalse(http("GET", base + "state", null).json().has("prompt"));
    }

    @Test
    public void leavingAnAppTellsItsBackendOnceAndOnlyWhenActiveMovesAway() throws Exception {
        final int[] left = new int[1];
        api.registerApp("accent-wheel", new AppBackend() {
            @Override public Object handle(String method, String path, Map<String, String> body) { return null; }
            @Override public void onLeave() { left[0]++; }
        });
        api.openApp("accent-wheel");
        assertEquals(0, left[0]);
        api.openApp("accent-wheel");
        assertEquals("Reopening is not leaving", 0, left[0]);
        api.goHome();
        assertEquals(1, left[0]);
        api.goHome();
        assertEquals(1, left[0]);
        api.openApp("accent-wheel");
        api.openApp("karaoke");
        assertEquals(2, left[0]);
    }

    @Test
    public void aBackendFailingOnLeaveStillLetsTheRoomMove() throws Exception {
        api.registerApp("accent-wheel", new AppBackend() {
            @Override public Object handle(String method, String path, Map<String, String> body) { return null; }
            @Override public void onLeave() { throw new IllegalStateException("broken"); }
        });
        api.openApp("accent-wheel");
        api.goHome();
        assertNull(api.activeApp());
    }

    @Test
    public void leavingReverseCharadesPausesALiveTurnOverHttp() throws Exception {
        String base = "/api/app/reverse-charades/";
        api.openApp("reverse-charades");
        http("POST", base + "action", "{\"action\":\"start\"}");
        clock.addAndGet(ReverseCharadesGame.COUNTDOWN_MS + 10000);
        assertEquals("performing", http("GET", base + "state", null).json().get("phase").getAsString());
        api.goHome();
        clock.addAndGet(1000000);
        JsonObject s = http("GET", base + "state", null).json();
        assertEquals("paused", s.get("phase").getAsString());
        assertEquals(50, s.get("secondsLeft").getAsInt());
    }

    // ----- Music when leaving Karaoke -----

    private void playingOnKaraoke() throws Exception {
        api.openApp("karaoke");
        api.enqueue("a");
        api.flushPlayerCommands();
        assertTrue(player.audible());
    }

    @Test
    public void leavingForAGamePausesAndReturningResumes() throws Exception {
        playingOnKaraoke();
        api.openApp("accent-wheel");
        api.flushPlayerCommands();
        assertTrue(player.paused);
        assertFalse(player.audible());
        assertTrue(pausedInState());
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertFalse(player.paused);
        assertTrue(player.audible());
        assertFalse(pausedInState());
        assertEquals(Arrays.asList("A"), player.played); // Resumed, not restarted.
    }

    @Test
    public void leavingForHomePausesAsWell() throws Exception {
        playingOnKaraoke();
        api.goHome();
        api.flushPlayerCommands();
        assertFalse(player.audible());
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertTrue(player.audible());
    }

    @Test
    public void goingThroughHomeAndAGameStillResumesOnce() throws Exception {
        playingOnKaraoke();
        api.goHome();
        api.openApp("accent-wheel");
        api.goHome();
        api.flushPlayerCommands();
        assertFalse(player.audible());
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertTrue(player.audible());
    }

    @Test
    public void aPauseTheUserMadeStaysPaused() throws Exception {
        playingOnKaraoke();
        api.togglePause();
        api.flushPlayerCommands();
        api.openApp("accent-wheel");
        api.flushPlayerCommands();
        assertTrue(player.paused);
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertTrue(player.paused);
        assertTrue(pausedInState());
        api.togglePause(); // And the user can still undo it.
        api.flushPlayerCommands();
        assertTrue(player.audible());
    }

    @Test
    public void afterAHubResumeALaterUserPauseIsStillTheUsers() throws Exception {
        playingOnKaraoke();
        api.goHome();
        api.openApp("karaoke");
        api.togglePause();
        api.goHome();
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertTrue(player.paused);
    }

    @Test
    public void reopeningKaraokeWhileOnItDoesNothing() throws Exception {
        playingOnKaraoke();
        api.togglePause();
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertTrue(player.paused);
    }

    @Test
    public void queueIsKeptWhileAway() throws Exception {
        playingOnKaraoke();
        api.enqueue("b");
        api.goHome();
        api.flushPlayerCommands();
        assertEquals("A", now().title);
        assertEquals(1, ((List<?>) api.state().get("queue")).size());
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertEquals("A", now().title);
        assertEquals(1, ((List<?>) api.state().get("queue")).size());
    }

    @Test
    public void leavingWithNothingPlayingIsHarmlessAndAddsNoPause() throws Exception {
        api.openApp("karaoke");
        api.openApp("accent-wheel");
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertFalse(player.paused);
        api.enqueue("a");
        api.flushPlayerCommands();
        assertTrue(player.audible());
    }

    @Test
    public void songQueuedDuringAGameWaitsForKaraoke() throws Exception {
        api.openApp("accent-wheel");
        api.enqueue("a");
        api.flushPlayerCommands();
        api.flushPlayerCommands();
        assertTrue(player.played.isEmpty());
        assertFalse(player.audible());
        api.goHome();
        api.flushPlayerCommands();
        assertTrue(player.played.isEmpty());
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertEquals(Arrays.asList("A"), player.played);
        assertTrue(player.audible());
        assertFalse(pausedInState());
    }

    @Test
    public void songStillLoadingWhenTheGameOpensDoesNotPlayOverIt() throws Exception {
        holdResolve = new CountDownLatch(1);
        api.openApp("karaoke");
        api.enqueue("n");
        api.openApp("accent-wheel");
        holdResolve.countDown();
        api.flushPlayerCommands();
        api.flushPlayerCommands();
        assertTrue(player.played.isEmpty());
        assertFalse(player.audible());
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertEquals(Arrays.asList("Needs url"), player.played);
        assertTrue(player.audible());
    }

    @Test
    public void nextSongAfterAFailureAwayFromKaraokeStaysSilentThenStarts() throws Exception {
        playingOnKaraoke();
        api.enqueue("b");
        api.openApp("accent-wheel");
        api.flushPlayerCommands();
        api.onSongFailed("dead link");
        api.flushPlayerCommands();
        api.flushPlayerCommands();
        assertEquals(Arrays.asList("A"), player.played);
        assertFalse(player.audible());
        assertEquals("B", now().title);
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertEquals(Arrays.asList("A", "B"), player.played);
        assertFalse(player.paused);
        assertTrue(player.audible());
    }

    @Test
    public void skipFromAStalePhoneDuringAGameStaysSilent() throws Exception {
        playingOnKaraoke();
        api.enqueue("b");
        api.openApp("accent-wheel");
        api.skip();
        api.flushPlayerCommands();
        api.flushPlayerCommands();
        assertFalse(player.audible());
        assertEquals(Arrays.asList("A"), player.played);
    }

    @Test
    public void togglePauseDuringAGameMakesNoSoundAndDecidesTheReturn() throws Exception {
        playingOnKaraoke();
        api.openApp("accent-wheel");
        api.togglePause(); // "Keep it paused."
        api.flushPlayerCommands();
        assertFalse(player.audible());
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertTrue(player.paused);

        api.openApp("accent-wheel");
        api.togglePause(); // "Play it when we return."
        api.flushPlayerCommands();
        assertFalse(player.audible());
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertTrue(player.audible());
    }

    @Test
    public void songEndingJustAsWeLeaveWithNothingNextIsFine() throws Exception {
        playingOnKaraoke();
        clock.addAndGet(ApiServer.END_GUARD_MS + 1);
        api.goHome();
        api.flushPlayerCommands();
        api.onSongEnded();
        api.flushPlayerCommands();
        assertNull(now());
        api.openApp("karaoke");
        api.flushPlayerCommands();
        assertFalse(player.paused);
        api.enqueue("b");
        api.flushPlayerCommands();
        assertTrue(player.audible());
    }

    @Test
    public void beforeAnyAppIsOpenedKaraokeBehavesAsItAlwaysDid() throws Exception {
        api.enqueue("a");
        api.flushPlayerCommands();
        assertEquals(Arrays.asList("A"), player.played);
        api.goHome(); // Home to home is no change.
        api.flushPlayerCommands();
        assertTrue(player.audible());
        api.openApp("accent-wheel"); // But a game still silences it.
        api.flushPlayerCommands();
        assertFalse(player.audible());
    }
}
