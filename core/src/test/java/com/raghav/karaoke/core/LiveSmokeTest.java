package com.raghav.karaoke.core;

import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

/**
 * Hits real Saavn and LRCLIB. Skipped unless env KARAOKE_LIVE=1. Writes build/live-smoke.txt.
 * The test records what happened rather than failing on a miss, since a miss is the thing being measured.
 */
public class LiveSmokeTest {
    private static final String[] SONGS = {"Kal Ho Naa Ho", "Tum Hi Ho", "Chaiyya Chaiyya", "Lag Ja Gale", "Kabhi Kabhi Aditi",
            "Mere Sapno Ki Rani", "Tunak Tunak Tun", "Mundian To Bach Ke", "Lover Diljit Dosanjh", "Badtameez Dil"};

    private static String probe(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", Http.UA);
            c.setRequestProperty("Range", "bytes=0-1023");
            int code = c.getResponseCode();
            String type = c.getContentType();
            c.disconnect();
            return code + " " + type;
        } catch (Exception e) {
            return "ERR " + e;
        }
    }

    @Test
    public void tenSongs() throws Exception {
        assumeTrue("1".equals(System.getenv("KARAOKE_LIVE")));
        StringBuilder log = new StringBuilder();
        SaavnClient saavn = new SaavnClient();
        LyricsClient lyrics = new LyricsClient();
        int audio = 0, token = 0, des = 0, synced = 0, plain = 0, sameAsDes = 0;
        for (String q : SONGS) {
            List<Song> res = saavn.search(q);
            if (res.isEmpty()) {
                log.append(q).append(" => no results\n");
                continue;
            }
            Song s = res.get(0);
            if (s.streamUrl != null) des++;
            String desUrl = saavn.resolveStream(s);
            String a = probe(desUrl);
            if (a.startsWith("200") || a.startsWith("206")) audio++;
            s.streamUrl = null;
            String t;
            try {
                String tokenUrl = saavn.resolveStream(s);
                t = probe(tokenUrl);
                if (tokenUrl.equals(desUrl)) sameAsDes++;
            } catch (Exception e) {
                t = "ERR " + e;
            }
            if (t.startsWith("200") || t.startsWith("206")) token++;
            long t0 = System.currentTimeMillis();
            LyricsClient.Result r = lyrics.fetch(s);
            long ms = System.currentTimeMillis() - t0;
            if ("synced".equals(r.type)) synced++;
            if ("plain".equals(r.type)) plain++;
            String sample = "";
            for (LrcParser.Line l : r.lines) {
                if (!l.text.isEmpty()) {
                    sample = l.text;
                    break;
                }
            }
            log.append(q).append(" => ").append(s.title).append(" | ").append(s.artist).append(" | ").append(s.durationSec)
                    .append("s | audio ").append(a).append(" | token ").append(t).append(" | lyrics ").append(r.type)
                    .append(" (").append(r.lines.size()).append(" lines, ").append(ms).append(" ms) from [").append(r.matched)
                    .append("] first: ").append(sample).append('\n');
        }
        log.append("\nDES ").append(des).append("/10 | AUDIO ").append(audio).append("/10 | TOKEN FALLBACK AUDIO ").append(token)
                .append("/10 (same URL as DES: ").append(sameAsDes).append(") | SYNCED ").append(synced).append("/10 | PLAIN ").append(plain).append("\n");
        File out = new File("build/live-smoke.txt");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), log.toString().getBytes(StandardCharsets.UTF_8));
    }
}
