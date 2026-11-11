package com.raghav.karaoke.core;

import java.util.Collections;
import java.util.List;

/**
 * Real ApiServer, fake player, real Saavn and LRCLIB. Runs until killed.
 * Song length override: system property harness.songSeconds, or env HARNESS_SONG_SECONDS (works through Gradle).
 * Port override: system property harness.port, or env HARNESS_PORT. Defaults to 8765.
 */
public final class Harness {
    private static final class FakePlayer implements PlayerControl {
        private final int overrideSec;
        private Song song;
        private boolean paused;
        private long basePos;
        private long resumedAt;

        FakePlayer(int overrideSec) {
            this.overrideSec = overrideSec;
        }

        @Override
        public synchronized void play(Song s) {
            song = s;
            paused = false;
            basePos = 0;
            resumedAt = System.currentTimeMillis();
            System.out.println("[harness] play: " + s.title + " | " + s.streamUrl);
        }

        @Override
        public synchronized void stop() {
            song = null;
            basePos = 0;
            System.out.println("[harness] stop");
        }

        @Override
        public synchronized void setPaused(boolean p) {
            if (song == null || p == paused) return;
            long t = System.currentTimeMillis();
            if (p) basePos += t - resumedAt;
            else resumedAt = t;
            paused = p;
        }

        @Override
        public void setVocalCut(boolean on) {
            System.out.println("[harness] vocal cut: " + on);
        }

        @Override
        public synchronized long positionMs() {
            if (song == null) return 0;
            return paused ? basePos : basePos + System.currentTimeMillis() - resumedAt;
        }

        @Override
        public synchronized boolean isPlaying() {
            return song != null && !paused;
        }

        synchronized boolean finished() {
            if (song == null) return false;
            int sec = overrideSec > 0 ? overrideSec : song.durationSec > 0 ? song.durationSec : 180;
            return positionMs() >= sec * 1000L;
        }

        /** The real player stops producing audio at the end. Mirrors that so the end event is not seen as stale. */
        synchronized void markEnded() {
            song = null;
        }
    }

    private Harness() {}

    private static int intSetting(String prop, String env, int fallback) {
        String v = System.getProperty(prop);
        if (v == null) v = System.getenv(env);
        try {
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = intSetting("harness.port", "HARNESS_PORT", 8765);
        FakePlayer player = new FakePlayer(intSetting("harness.songSeconds", "HARNESS_SONG_SECONDS", 0));
        LocalLibrary empty = new LocalLibrary() {
            @Override
            public List<Song> search(String query) {
                return Collections.emptyList();
            }
        };
        ApiServer server = new ApiServer(port, player, empty, "http://192.0.2.10:" + port + "/");
        server.start();
        System.out.println("[harness] listening on http://127.0.0.1:" + port + "/");
        while (true) {
            Thread.sleep(200);
            if (player.finished()) {
                player.markEnded();
                server.onSongEnded();
            }
        }
    }
}
