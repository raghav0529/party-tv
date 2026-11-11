package com.raghav.karaoke.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Small GET helper. Android and JVM safe. */
final class Http {
    static final String UA = "Mozilla/5.0 (Linux; Android 9; MiTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private Http() {}

    static Response get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(status, in == null ? "" : new String(readAll(in, MAX_BYTES), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    /** Asks for the first KB. Any failure counts as not reachable. */
    static boolean isAudioReachable(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Range", "bytes=0-1023");
            int status = c.getResponseCode();
            return status == 200 || status == 206;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) {
                if (out.size() + n > max) throw new IOException("Response too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
