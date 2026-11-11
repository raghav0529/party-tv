package com.raghav.karaoke.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class HinglishTest {
    private static void word(String hindi, String expected) {
        assertEquals(hindi, expected, Hinglish.word(hindi));
    }

    @Test
    public void commonWordsUseTheUsualSpelling() {
        word("है", "hai");
        word("हम", "hum");
        word("नहीं", "nahi");
        word("ज़िंदगी", "zindagi");
    }

    @Test
    public void silentVowelsAreDropped() {
        word("कल", "kal");                 // kal
        word("बदल", "badal");         // badal
        word("रहना", "rahna");   // rahna
        word("करता", "karta");   // karta
        word("सपनों", "sapnon"); // sapnon
    }

    @Test
    public void longVowelsAndNasals() {
        word("साथ", "saath");     // saath
        word("धूप", "dhoop");     // dhoop
        word("छाँव", "chhaanv");
    }

    @Test
    public void leadingBlanksAndJunkCharactersAreRemovedSoTheCountdownReachesTheFirstWords() {
        java.util.List<LrcParser.Line> in = new java.util.ArrayList<LrcParser.Line>();
        in.add(new LrcParser.Line(5200, ""));
        in.add(new LrcParser.Line(9000, "￼￼"));
        in.add(new LrcParser.Line(17500, "Daayein lage"));
        in.add(new LrcParser.Line(20000, "￼"));
        in.add(new LrcParser.Line(21700, "Naino ki"));
        java.util.List<LrcParser.Line> out = LyricsClient.toHinglish(new LyricsClient.Result("synced", in, "x")).lines;
        assertEquals(3, out.size());
        assertEquals(17500, out.get(0).t);
        assertEquals("Daayein lage", out.get(0).text);
        assertEquals("", out.get(1).text);
    }

    @Test
    public void punjabiScriptConverts() {
        String out = Hinglish.line("ਮੁੰਡਿਆਂ ਤੋ ਬੱਚ ਕੇ");
        assertFalse(out, Hinglish.hasDevanagari(out));
        assertEquals("Mundiaan to bachch ke", out);
    }

    @Test
    public void accentsAreRemovedFromEnglishLetters() {
        assertEquals("Tunak da da da", Hinglish.line("Tunak dā dā dā"));
    }

    @Test
    public void precomposedNuktaLettersConvert() {
        assertEquals("Zara", Hinglish.line("ज़रा"));
    }

    @Test
    public void lineKeepsPunctuationAndCapitalises() {
        assertEquals("Jo hai samaan kal ho na ho",
                Hinglish.line("जो है समाँ कल हो न हो"));
        assertEquals("Hello, world", Hinglish.line("Hello, world"));
        assertFalse(Hinglish.hasDevanagari(Hinglish.line("दिल, तेरा ।")));
    }
}
