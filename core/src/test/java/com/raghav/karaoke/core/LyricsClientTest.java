package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LyricsClientTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void titleCleanup() {
        assertEquals("Kal Ho Naa Ho", LyricsClient.cleanTitle("Kal Ho Naa Ho (From \"Kal Ho Naa Ho\")"));
        assertEquals("Tum Hi Ho", LyricsClient.cleanTitle("Tum Hi Ho [Lofi Mix]"));
        assertEquals("Lover", LyricsClient.cleanTitle("Lover feat. Someone"));
        assertEquals("Lover", LyricsClient.cleanTitle("Lover (feat. Someone Else)"));
        assertEquals("Song", LyricsClient.cleanTitle("Song - From \"Film\""));
        assertEquals("Lag Ja Gale", LyricsClient.cleanTitle("  Lag  Ja  Gale "));
        assertEquals("[Only]", LyricsClient.cleanTitle("[Only]"));
    }

    @Test
    public void variantsAddBareTitle() {
        assertEquals(Arrays.asList("Song (Remix)", "Song"), LyricsClient.titleVariants("Song (Remix)"));
    }

    private static LyricsClient.Candidate c(String track, String artist, int dur, boolean synced) {
        return new LyricsClient.Candidate(track, artist, dur, synced ? "[00:01.00]x" : null, "x");
    }

    @Test
    public void pickPrefersSyncedInWindowAndRejectsOtherSongs() {
        List<String> t = Arrays.asList("Lover");
        List<String> a = Arrays.asList("Diljit Dosanjh");
        LyricsClient.Candidate wrongSinger = c("Lover", "Taylor Swift", 221, true);
        LyricsClient.Candidate plain = c("Lover", "Diljit Dosanjh", 188, false);
        LyricsClient.Candidate sync15 = c("Lover", "Diljit Dosanjh", 203, true);
        LyricsClient.Candidate sync5 = c("Lover", "Diljit Dosanjh", 193, true);
        assertNull(LyricsClient.pick(Arrays.asList(wrongSinger), t, a, 188));
        assertSame(plain, LyricsClient.pick(Arrays.asList(wrongSinger, plain), t, a, 188));
        assertSame(sync15, LyricsClient.pick(Arrays.asList(plain, sync15), t, a, 188));
        assertSame(sync5, LyricsClient.pick(Arrays.asList(plain, sync15, sync5), t, a, 188));
    }

    @Test
    public void usbFileWithoutArtistOrDurationIsNotGivenAShortTitleMatch() {
        List<String> none = Collections.emptyList();
        assertNull(LyricsClient.pick(Arrays.asList(c("Lover", "Taylor Swift", 221, true)), Arrays.asList("Lover"), none, 0));
    }

    @Test
    public void usbLongTitleWithoutConfirmationIsPlainTierOnly() {
        List<String> none = Collections.emptyList();
        assertEquals(5, LyricsClient.tier(c("Tum Hi Ho", "Arijit Singh", 262, true), Arrays.asList("Tum Hi Ho"), none, 0));
    }

    @Test
    public void usbFileNameArtistConfirmsMatch() {
        List<String> titles = LyricsClient.usbTitles("Arijit Singh - Tum Hi Ho");
        List<String> artists = LyricsClient.usbArtists("Arijit Singh - Tum Hi Ho");
        assertEquals(0, LyricsClient.tier(c("Tum Hi Ho", "Arijit Singh", 262, true), titles, artists, 0));
    }

    @Test
    public void titleMatchToleratesTransliteration() {
        assertTrue(LyricsClient.titleMatches("Mere Sapnon Ki Rani", Arrays.asList("Mere Sapno Ki Rani")));
        assertFalse(LyricsClient.titleMatches("Love Story", Arrays.asList("Lover")));
    }

    @Test
    public void tokenUrlMapsToUnsignedHighBitrate() {
        assertEquals("https://aac.saavncdn.com/430/abc_160.mp4",
                SaavnClient.unsignedFromAuthUrl("https://ac.cf.saavncdn.com/430/abc_96.mp4?Expires=1&Signature=x"));
        assertNull(SaavnClient.unsignedFromAuthUrl("not a url"));
    }

    @Test
    public void usbSiblingLrc() throws Exception {
        File song = tmp.newFile("song.mp3");
        Files.write(new File(tmp.getRoot(), "song.lrc").toPath(), "[00:01.00]गाना".getBytes(StandardCharsets.UTF_8));
        Song s = new Song();
        s.id = "usb:" + song.getPath();
        LyricsClient.Result r = new LyricsClient().fetch(s);
        assertEquals("synced", r.type);
        assertEquals("Gaana", r.lines.get(0).text);
    }

    @Test
    public void usbUntimedLrcIsPlain() throws Exception {
        File song = tmp.newFile("other.mp3");
        Files.write(new File(tmp.getRoot(), "other.lrc").toPath(), "just words".getBytes(StandardCharsets.UTF_8));
        Song s = new Song();
        s.id = "usb:" + song.getPath();
        LyricsClient.Result r = new LyricsClient().fetch(s);
        assertEquals("plain", r.type);
        assertEquals(-1, r.lines.get(0).t);
    }

    @Test
    public void usbMissingLrcWithNoTitleIsNone() {
        Song s = new Song();
        s.id = "usb:" + new File(tmp.getRoot(), "nope.mp3").getPath();
        assertEquals("none", new LyricsClient().fetch(s).type);
    }
}
