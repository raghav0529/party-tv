package com.raghav.karaoke.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lyrics from a sibling .lrc (USB songs) or LRCLIB. Never invents text: no credible match means "none". */
public final class LyricsClient {
    public static final class Result {
        public final String type; // synced, plain, none, loading
        public final List<LrcParser.Line> lines;
        /** Where the match came from, for diagnostics only. */
        public final String matched;

        public Result(String type, List<LrcParser.Line> lines, String matched) {
            this.type = type;
            this.lines = lines;
            this.matched = matched;
        }
    }

    static final Result NONE = new Result("none", Collections.<LrcParser.Line>emptyList(), null);
    static final Result LOADING = new Result("loading", Collections.<LrcParser.Line>emptyList(), null);

    private static final String BASE = "https://lrclib.net/api/";
    private static final long BUDGET_MS = 30000;
    private static final int REJECTED = Integer.MAX_VALUE;

    /** One LRCLIB record, reduced to what matching needs. */
    static final class Candidate {
        final String track, artist, synced, plain;
        final int duration;
        final boolean hasSynced;

        Candidate(String track, String artist, int duration, String synced, String plain) {
            this.track = track;
            this.artist = artist;
            this.duration = duration;
            this.synced = synced;
            this.plain = plain;
            this.hasSynced = synced != null && LrcParser.hasText(LrcParser.parse(synced));
        }
    }

    /** Lyrics always reach the screen in English letters. */
    public Result fetch(Song song) {
        Result r = fetchOriginal(song);
        // "None" after a network failure only means the lookup failed, not that the song has no lyrics.
        for (int wait : RETRY_WAIT_MS) {
            if (!"none".equals(r.type) || lastFailure.get() == null) break;
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            r = fetchOriginal(song);
        }
        if ("none".equals(r.type) && lastFailure.get() != null) {
            return new Result("none", r.lines, "lookup failed: " + lastFailure.get());
        }
        return toHinglish(r);
    }

    private static final int[] RETRY_WAIT_MS = {5000, 15000, 30000};
    private final ThreadLocal<String> lastFailure = new ThreadLocal<String>();

    // LRCLIB answers HTTP 429 when guests add many songs quickly, so all lookups share one paced slot.
    private static final long MIN_GAP_MS = 1000, BACKOFF_429_MS = 20000;
    private static final Object PACE_LOCK = new Object();
    private static long nextAllowedAt = 0;

    private static Http.Response pacedGet(String url) throws IOException {
        long wait;
        synchronized (PACE_LOCK) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextAllowedAt);
            nextAllowedAt = slot + MIN_GAP_MS;
            wait = slot - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
        Http.Response r = Http.get(url);
        if (r.status == 429) {
            synchronized (PACE_LOCK) {
                nextAllowedAt = Math.max(nextAllowedAt, System.currentTimeMillis() + BACKOFF_429_MS);
            }
        }
        return r;
    }

    static Result toHinglish(Result r) {
        List<LrcParser.Line> out = new ArrayList<LrcParser.Line>(r.lines.size());
        for (LrcParser.Line l : r.lines) {
            // Lyric files carry stray object-replacement and zero-width characters that show as junk on screen.
            String text = Hinglish.line(l.text.replaceAll("[\\uFFFC\\uFFFD\\u200B-\\u200D\\uFEFF]", "")).trim();
            // The countdown runs to the first line, so a blank before the first words would end it too early.
            if (out.isEmpty() && text.isEmpty()) continue;
            out.add(new LrcParser.Line(l.t, text));
        }
        return new Result(r.type, out, r.matched);
    }

    private Result fetchOriginal(Song song) {
        lastFailure.remove();
        boolean usb = song.id != null && song.id.startsWith("usb:");
        if (usb) {
            Result r = fromSiblingLrc(song.id.substring(4));
            if (r != null) return r;
        }
        if (song.title == null || song.title.trim().isEmpty()) return NONE;
        try {
            return usb ? fromLrclib(usbTitles(song.title), usbArtists(song.title), song.durationSec)
                    : fromLrclib(titleVariants(song.title), splitArtists(song.artist), song.durationSec);
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    // ----- USB -----

    static Result fromSiblingLrc(String path) {
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String stem = dot > slash ? path.substring(0, dot) : path;
        for (String ext : new String[] {".lrc", ".LRC", ".Lrc"}) {
            File f = new File(stem + ext);
            if (!f.isFile()) continue;
            try {
                String text = new String(Http.readAll(new FileInputStream(f), 1024 * 1024), StandardCharsets.UTF_8);
                List<LrcParser.Line> timed = LrcParser.parse(text);
                if (LrcParser.hasText(timed)) return new Result("synced", timed, f.getPath());
                List<LrcParser.Line> plain = LrcParser.plain(text);
                if (!plain.isEmpty()) return new Result("plain", plain, f.getPath());
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /** A file name has no reliable artist field. "A - B" could be either order, so both sides are tried as both. */
    static List<String> usbTitles(String fileTitle) {
        Set<String> out = new LinkedHashSet<String>();
        for (String part : fileTitle.split("\\s+-\\s+")) {
            String c = cleanTitle(part);
            if (!c.isEmpty()) out.add(c);
        }
        return new ArrayList<String>(out);
    }

    static List<String> usbArtists(String fileTitle) {
        List<String> parts = usbTitles(fileTitle);
        return parts.size() > 1 ? parts : new ArrayList<String>();
    }

    // ----- LRCLIB -----

    private Result fromLrclib(List<String> titles, List<String> artists, int dur) {
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        List<Candidate> found = new ArrayList<Candidate>();
        // Searches come first: one call returns many versions, so an English-letter one is found with few requests.
        for (String t : titles) {
            List<String> urls = new ArrayList<String>();
            urls.add(BASE + "search?track_name=" + Http.enc(t));
            for (String a : artists) urls.add(BASE + "search?q=" + Http.enc(t + " " + a));
            urls.add(BASE + "search?q=" + Http.enc(t));
            for (String u : urls) {
                if (System.currentTimeMillis() > deadline) return best(found, titles, artists, dur);
                addSearch(found, u);
                if (hasTopTier(found, titles, artists, dur)) return best(found, titles, artists, dur);
            }
        }
        if (pick(found, titles, artists, dur) != null) return best(found, titles, artists, dur);
        for (String t : titles) {
            for (String a : artists) {
                if (System.currentTimeMillis() > deadline) return best(found, titles, artists, dur);
                addGet(found, t, a, dur);
                if (hasTopTier(found, titles, artists, dur)) return best(found, titles, artists, dur);
            }
        }
        return best(found, titles, artists, dur);
    }

    private void addGet(List<Candidate> found, String title, String artist, int dur) {
        String url = BASE + "get?track_name=" + Http.enc(title) + "&artist_name=" + Http.enc(artist);
        if (dur > 0) url += "&duration=" + dur;
        try {
            Http.Response r = pacedGet(url);
            if (r.status == 200) {
                Candidate c = toCandidate(JsonParser.parseString(r.body).getAsJsonObject());
                if (c != null) found.add(c);
            } else if (r.status != 404) {
                lastFailure.set("HTTP " + r.status);
            }
        } catch (IOException | RuntimeException e) {
            // A missed lookup is not fatal, the next strategy runs.
            lastFailure.set(e.toString());
        }
    }

    private void addSearch(List<Candidate> found, String url) {
        try {
            Http.Response r = pacedGet(url);
            if (r.status != 200) {
                lastFailure.set("HTTP " + r.status);
                return;
            }
            JsonElement root = JsonParser.parseString(r.body);
            if (!root.isJsonArray()) return;
            for (JsonElement e : (JsonArray) root) {
                if (!e.isJsonObject()) continue;
                Candidate c = toCandidate(e.getAsJsonObject());
                if (c != null) found.add(c);
            }
        } catch (IOException | RuntimeException e) {
            // Same as above.
            lastFailure.set(e.toString());
        }
    }

    private static Candidate toCandidate(JsonObject o) {
        String synced = text(o, "syncedLyrics"), plain = text(o, "plainLyrics");
        if (synced == null && plain == null) return null;
        int d = 0;
        JsonElement de = o.get("duration");
        if (de != null && de.isJsonPrimitive()) {
            try {
                d = (int) Math.round(de.getAsDouble());
            } catch (NumberFormatException ex) {
                d = 0;
            }
        }
        return new Candidate(text(o, "trackName"), text(o, "artistName"), d, synced, plain);
    }

    private static String text(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.trim().isEmpty() ? null : s;
    }

    private static boolean hasTopTier(List<Candidate> c, List<String> titles, List<String> artists, int dur) {
        // A Hindi-script match does not end the hunt: a version already in English letters may still turn up.
        // Nor does a Reprise or Remix: the plain version may still turn up.
        for (Candidate x : c) if (tier(x, titles, artists, dur) == 0 && !isDevanagari(x) && exactTitle(x, titles)) return true;
        return false;
    }

    private static boolean exactTitle(Candidate c, List<String> titles) {
        String track = norm(cleanTitle(c.track));
        for (String title : titles) if (track.equals(norm(title))) return true;
        return false;
    }

    private static boolean isDevanagari(Candidate c) {
        return Hinglish.hasDevanagari(c.hasSynced ? c.synced : c.plain);
    }

    /**
     * Lower is better. Well-timed versions already in English letters come first, then well-timed Hindi script
     * (which gets converted), then looser timing. Timing outranks script because a late line is useless to singers.
     */
    static int rank(Candidate c, List<String> titles, List<String> artists, int dur) {
        int t = tier(c, titles, artists, dur);
        if (t == REJECTED) return REJECTED;
        String text = c.hasSynced ? c.synced : c.plain;
        boolean convert = Hinglish.hasDevanagari(text);
        int r;
        switch (t) {
            case 0: r = convert ? 1 : 0; break;
            case 2: r = convert ? 3 : 2; break;
            case 1: r = convert ? 5 : 4; break;
            case 3: r = convert ? 7 : 6; break;
            default: r = 8 + (t - 4) * 2 + (convert ? 1 : 0);
        }
        // Urdu script cannot be converted, so it is the last resort within a credible match.
        if (Hinglish.hasArabic(text)) r += 20;
        int diff = dur <= 0 || c.duration <= 0 ? 0 : Math.min(Math.abs(dur - c.duration), 99);
        // A Reprise or Remix has different words or timing, so it ranks below a converted plain version.
        if (!exactTitle(c, titles)) r += 2;
        return r * 1000 + diff;
    }

    private static Result best(List<Candidate> found, List<String> titles, List<String> artists, int dur) {
        Candidate c = pick(found, titles, artists, dur);
        if (c == null) return NONE;
        String label = c.track + " / " + c.artist + " / " + c.duration + "s";
        if (tier(c, titles, artists, dur) <= 3) return new Result("synced", LrcParser.parse(c.synced), label);
        List<LrcParser.Line> plain = LrcParser.plain(c.plain != null ? c.plain : c.synced);
        return plain.isEmpty() ? NONE : new Result("plain", plain, label);
    }

    /** Best candidate, or null when nothing is credibly the same song. */
    static Candidate pick(List<Candidate> found, List<String> titles, List<String> artists, int dur) {
        Candidate best = null;
        int bestTier = REJECTED;
        for (Candidate c : found) {
            int t = rank(c, titles, artists, dur);
            if (t < bestTier) {
                bestTier = t;
                best = c;
            }
        }
        return best;
    }

    /**
     * Lower is better. Duration windows follow the 8 s then 20 s rule, with a matching artist ranked ahead, so a
     * short common title cannot pull in another song's words. With no duration and no artist to confirm it (a USB
     * file named only by title), only a title of three or more words is accepted, and only as plain text, because
     * the timing of an unconfirmed version cannot be trusted.
     */
    static int tier(Candidate c, List<String> titles, List<String> artists, int dur) {
        if (!titleMatches(c.track, titles)) return REJECTED;
        boolean artist = artistMatches(c.artist, artists);
        if (dur <= 0 && !artist) return longestTitleWords(titles) >= 3 ? 5 : REJECTED;
        // "Lover" by anyone else is a different song. Wrong words are worse than none, so short titles need the artist.
        if (!artist && longestTitleWords(titles) <= 2) return REJECTED;
        int diff = dur <= 0 || c.duration <= 0 ? 0 : Math.abs(dur - c.duration);
        if (c.hasSynced && diff <= 8) return artist ? 0 : 2;
        if (c.hasSynced && diff <= 20) return artist ? 1 : 3;
        if (artist) return 4;
        return diff <= 20 ? 5 : REJECTED;
    }

    private static int longestTitleWords(List<String> titles) {
        int most = 0;
        for (String t : titles) {
            String n = norm(t);
            if (!n.isEmpty()) most = Math.max(most, n.split(" ").length);
        }
        return most;
    }

    // ----- Title and artist helpers -----

    public static String cleanTitle(String title) {
        if (title == null) return "";
        String s = title;
        s = s.replaceAll("\\[[^\\]]*\\]", " ");
        s = s.replaceAll("(?i)\\(\\s*(from|feat\\.?|ft\\.?|featuring|with|original|ost|music from)\\b[^)]*\\)", " ");
        s = s.replaceAll("(?i)\\s+-\\s+from\\b.*$", " ");
        s = s.replaceAll("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? title.trim() : s;
    }

    /** Cleaned title, then the same with any remaining brackets or " - suffix" removed. */
    static List<String> titleVariants(String title) {
        Set<String> out = new LinkedHashSet<String>();
        String clean = cleanTitle(title);
        out.add(clean);
        String bare = clean.replaceAll("\\([^)]*\\)", " ").replaceAll("\\s+-\\s+.*$", " ").replaceAll("\\s+", " ").trim();
        if (!bare.isEmpty()) out.add(bare);
        return new ArrayList<String>(out);
    }

    static List<String> splitArtists(String artist) {
        List<String> out = new ArrayList<String>();
        if (artist == null) return out;
        for (String a : artist.split(",|&|\\band\\b")) {
            String s = a.trim();
            if (!s.isEmpty() && !out.contains(s) && out.size() < 4) out.add(s);
        }
        return out;
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").trim();
    }

    static boolean titleMatches(String candidate, List<String> titles) {
        String c = norm(cleanTitle(candidate));
        if (c.isEmpty()) return false;
        for (String t : titles) {
            String n = norm(t);
            if (n.isEmpty()) continue;
            if (c.equals(n)) return true;
            if (n.length() >= 5 && (" " + c + " ").contains(" " + n + " ")) return true;
            String a = c.replace(" ", ""), b = n.replace(" ", "");
            int allowed = b.length() / 5;
            if (b.length() >= 5 && Math.abs(a.length() - b.length()) <= allowed && editDistance(a, b) <= allowed) return true;
        }
        return false;
    }

    static boolean artistMatches(String candidate, List<String> artists) {
        String c = norm(candidate);
        if (c.isEmpty()) return false;
        Set<String> words = new HashSet<String>();
        Collections.addAll(words, c.split(" "));
        for (String a : artists) {
            String n = norm(a);
            if (n.isEmpty()) continue;
            if (c.contains(n)) return true;
            for (String w : n.split(" ")) if (w.length() >= 4 && words.contains(w)) return true;
        }
        return false;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j], cur[j - 1]) + 1);
            }
            int[] x = prev;
            prev = cur;
            cur = x;
        }
        return prev[b.length()];
    }
}
