package com.raghav.karaoke.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class SaavnClient {
    private static final String API = "https://www.jiosaavn.com/api.php?";
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final long RETRY_ONLY_IF_FAILED_WITHIN_MS = 8000;

    /** Kept only so a stream URL can be fetched at play time if local DES is unavailable. */
    private final Map<String, String> encryptedById = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 500;
        }
    };

    /** One retry on a quick failure, which covers a dropped connection without outlasting the phone's timeout. */
    public List<Song> search(String query) throws IOException {
        long started = System.currentTimeMillis();
        try {
            return searchOnce(query);
        } catch (IOException e) {
            if (System.currentTimeMillis() - started > RETRY_ONLY_IF_FAILED_WITHIN_MS) throw e;
            return searchOnce(query);
        }
    }

    private List<Song> searchOnce(String query) throws IOException {
        Http.Response r = Http.get(API + "__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=15&p=1&q=" + Http.enc(query));
        if (r.status != 200) throw new IOException("Saavn search returned HTTP " + r.status);
        return parseSearch(r.body);
    }

    List<Song> parseSearch(String body) throws IOException {
        JsonObject root = parseObject(body);
        List<Song> out = new ArrayList<Song>();
        JsonElement results = root.get("results");
        if (results == null || !results.isJsonArray()) return out;
        for (JsonElement e : (JsonArray) results) {
            if (!e.isJsonObject()) continue;
            Song s = toSong(e.getAsJsonObject());
            if (s != null) out.add(s);
        }
        return out;
    }

    private Song toSong(JsonObject o) {
        JsonObject mi = o.has("more_info") && o.get("more_info").isJsonObject() ? o.getAsJsonObject("more_info") : new JsonObject();
        String encrypted = str(mi, "encrypted_media_url");
        String id = str(o, "id");
        if (encrypted == null || encrypted.isEmpty() || id == null) return null; // cannot be played
        Song s = new Song();
        s.id = id;
        s.source = "saavn";
        s.title = unescape(str(o, "title"));
        s.album = unescape(str(mi, "album"));
        s.image = str(o, "image");
        s.artist = artists(mi);
        try {
            String d = str(mi, "duration");
            s.durationSec = d == null ? 0 : Integer.parseInt(d.trim());
        } catch (NumberFormatException ex) {
            s.durationSec = 0;
        }
        synchronized (encryptedById) {
            encryptedById.put(id, encrypted);
        }
        try {
            s.streamUrl = streamFromEncrypted(encrypted);
        } catch (Exception | LinkageError ex) {
            s.streamUrl = null; // resolved through the token endpoint at play time
        }
        return s;
    }

    /** Returns a playable URL, using the token endpoint if local decryption is not possible. */
    public String resolveStream(Song song) throws IOException {
        if (song.streamUrl != null) return song.streamUrl;
        String encrypted;
        synchronized (encryptedById) {
            encrypted = encryptedById.get(song.id);
        }
        if (encrypted == null) throw new IOException("No stream data for this song");
        return streamFromToken(encrypted);
    }

    /**
     * The stream key is not in this repository. Put it in core/src/main/resources/saavn.key (ignored by git) to decode
     * stream links locally. Without it, every song is resolved through the token endpoint instead, which also works.
     */
    private static volatile String streamKey;

    static String streamKey() throws IOException {
        String k = streamKey;
        if (k != null) return k;
        java.io.InputStream in = SaavnClient.class.getResourceAsStream("/saavn.key");
        if (in == null) throw new IOException("No stream key: using the token endpoint");
        try {
            k = new String(Http.readAll(in, 256), StandardCharsets.US_ASCII).trim();
        } finally {
            in.close();
        }
        if (k.isEmpty() || k.startsWith("INSERT")) throw new IOException("No stream key: using the token endpoint");
        streamKey = k;
        return k;
    }

    static String streamFromEncrypted(String encrypted) throws Exception {
        Cipher c = Cipher.getInstance("DES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(streamKey().getBytes(StandardCharsets.US_ASCII), "DES"));
        byte[] plain = c.doFinal(Base64.getDecoder().decode(encrypted.replaceAll("\\s", "")));
        return new String(plain, StandardCharsets.UTF_8).replace("_96.mp4", "_160.mp4");
    }

    /**
     * Measured from a laptop: ctx=web6dot0 returns web.saavncdn.com links that answer 403, so ctx=android is used.
     * Its signed link is stuck at 96 kbps, but the same path on aac.saavncdn.com serves unsigned at 160 kbps, which is
     * what DES would have produced. Prefer that when it answers, otherwise use the signed link as given.
     */
    static String streamFromToken(String encrypted) throws IOException {
        Http.Response r = Http.get(API + "__call=song.generateAuthToken&url=" + Http.enc(encrypted)
                + "&bitrate=160&api_version=4&_format=json&ctx=android&_marker=0");
        if (r.status != 200) throw new IOException("Saavn token returned HTTP " + r.status);
        String signed = str(parseObject(r.body), "auth_url");
        if (signed == null || signed.isEmpty()) throw new IOException("Saavn did not return a stream link");
        String unsigned = unsignedFromAuthUrl(signed);
        return unsigned != null && Http.isAudioReachable(unsigned) ? unsigned : signed;
    }

    /** https://any.host/path/x_96.mp4?sig becomes https://aac.saavncdn.com/path/x_160.mp4, or null if the shape differs. */
    static String unsignedFromAuthUrl(String authUrl) {
        int q = authUrl.indexOf('?');
        String bare = q >= 0 ? authUrl.substring(0, q) : authUrl;
        int scheme = bare.indexOf("://");
        int path = scheme < 0 ? -1 : bare.indexOf('/', scheme + 3);
        if (path < 0) return null;
        return "https://aac.saavncdn.com" + bare.substring(path).replace("_96.mp4", "_160.mp4");
    }

    private static JsonObject parseObject(String body) throws IOException {
        int at = body == null ? -1 : body.indexOf('{');
        if (at < 0) throw new IOException("Saavn returned an unreadable response");
        try {
            return JsonParser.parseString(body.substring(at)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Saavn returned an unreadable response", e);
        }
    }

    private static String artists(JsonObject mi) {
        StringBuilder b = new StringBuilder();
        JsonElement map = mi.get("artistMap");
        if (map != null && map.isJsonObject() && map.getAsJsonObject().get("primary_artists") != null
                && map.getAsJsonObject().get("primary_artists").isJsonArray()) {
            for (JsonElement a : map.getAsJsonObject().getAsJsonArray("primary_artists")) {
                String name = a.isJsonObject() ? str(a.getAsJsonObject(), "name") : null;
                if (name == null || name.isEmpty()) continue;
                if (b.length() > 0) b.append(", ");
                b.append(unescape(name));
            }
        }
        return b.toString();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    static String unescape(String s) {
        if (s == null) return null;
        String out = s.replace("&quot;", "\"").replace("&#039;", "'").replace("&apos;", "'");
        Matcher m = NUMERIC_ENTITY.matcher(out);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String rep;
            try {
                rep = new String(Character.toChars(Integer.parseInt(m.group(1))));
            } catch (IllegalArgumentException e) {
                rep = m.group();
            }
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        m.appendTail(b);
        return b.toString().replace("&amp;", "&");
    }
}
