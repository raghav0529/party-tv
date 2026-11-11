package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** A browser reuses one connection, so a query on one request must not leak into the next. */
public class StaleQueryTest {
    private static final class NoPlayer implements PlayerControl {
        @Override public void play(Song song) {}
        @Override public void stop() {}
        @Override public void setPaused(boolean paused) {}
        @Override public void setVocalCut(boolean on) {}
        @Override public long positionMs() { return 0; }
        @Override public boolean isPlaying() { return false; }
    }

    private static String get(Socket s, String path) throws Exception {
        s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: t\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString("UTF-8").endsWith("\r\n\r\n")) {
            int b = in.read();
            assertTrue("connection closed", b >= 0);
            head.write(b);
        }
        String h = head.toString("UTF-8");
        int at = h.toLowerCase().indexOf("content-length:");
        int len = Integer.parseInt(h.substring(at + 15, h.indexOf("\r\n", at)).trim());
        for (int i = 0; i < len; i++) in.read();
        return h.substring(0, h.indexOf("\r\n"));
    }

    @Test
    public void gameStateWorksAfterQrOnTheSameConnection() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        ApiServer api = new ApiServer(port, new NoPlayer(), new LocalLibrary() {
            @Override
            public List<Song> search(String query) {
                return Collections.emptyList();
            }
        }, "http://192.0.2.10:8765/");
        api.start();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            assertTrue(get(s, "/qr.svg?u=http%3A%2F%2Fx").contains("200"));
            String state = get(s, "/api/app/accent-wheel/state");
            assertTrue(state, state.contains("200"));
        } finally {
            api.stop();
        }
    }
}
