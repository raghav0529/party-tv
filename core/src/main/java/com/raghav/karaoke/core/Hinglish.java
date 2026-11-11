package com.raghav.karaoke.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Devanagari lyrics to Hindi in English letters. Common words come from a dictionary, because their usual
 * spelling (hum, nahi, kyun) does not follow from the letters. Everything else follows spelling rules, with the
 * standard right-to-left removal of silent vowels, so rarer words can come out readable but unusually spelled.
 */
public final class Hinglish {
    private Hinglish() {}

    private static final Map<String, String> WORDS = new HashMap<String, String>();
    private static final Map<Character, String> CONS = new HashMap<Character, String>();
    private static final Map<Character, String> NUKTA = new HashMap<Character, String>();
    private static final Map<Character, String> VOWEL = new HashMap<Character, String>();
    private static final Map<Character, String> MATRA = new HashMap<Character, String>();

    private static final char VIRAMA = '्', NUKTA_SIGN = '़', ANUSVARA = 'ं', CHANDRA = 'ँ', VISARGA = 'ः';

    private static void fill(Map<Character, String> map, String chars, String... values) {
        for (int i = 0; i < values.length; i++) map.put(chars.charAt(i), values[i]);
    }

    static {
        fill(CONS, "कखगघङचछजझञटठडढणतथदधन"
                        + "पफबभमयरलवशषसहळ",
                "k", "kh", "g", "gh", "n", "ch", "chh", "j", "jh", "ny", "t", "th", "d", "dh", "n", "t", "th", "d", "dh", "n",
                "p", "ph", "b", "bh", "m", "y", "r", "l", "v", "sh", "sh", "s", "h", "l");
        fill(NUKTA, "कखगजडढफ", "q", "kh", "gh", "z", "r", "rh", "f");
        fill(VOWEL, "अआइईउऊऋएऐओऔऑ",
                "a", "aa", "i", "ee", "u", "oo", "ri", "e", "ai", "o", "au", "o");
        fill(MATRA, "ािीुूृेैोौॉ",
                "aa", "i", "i", "u", "oo", "ri", "e", "ai", "o", "au", "o");

        String[][] w = {
            {"है", "hai"}, {"हैं", "hain"}, {"मैं", "main"}, {"में", "mein"},
            {"नहीं", "nahi"}, {"नही", "nahi"}, {"यह", "yeh"}, {"वह", "woh"},
            {"वो", "woh"}, {"ये", "ye"}, {"तू", "tu"}, {"तुम", "tum"},
            {"हम", "hum"}, {"क्या", "kya"}, {"क्यों", "kyun"},
            {"कि", "ki"}, {"की", "ki"}, {"कौन", "kaun"}, {"तो", "to"},
            {"भी", "bhi"}, {"ही", "hi"}, {"न", "na"}, {"ना", "na"}, {"और", "aur"},
            {"दिल", "dil"}, {"इश्क", "ishq"}, {"इश्क़", "ishq"},
            {"प्यार", "pyaar"}, {"यार", "yaar"}, {"आ", "aa"}, {"जा", "ja"},
            {"आज", "aaj"}, {"पे", "pe"}, {"मुझे", "mujhe"}, {"तुझे", "tujhe"},
            {"मुझ", "mujh"}, {"तुझ", "tujh"}, {"हूँ", "hoon"}, {"हूं", "hoon"},
            {"हों", "hon"}, {"जी", "ji"}, {"यहाँ", "yahan"}, {"यहां", "yahan"},
            {"वहाँ", "wahan"}, {"वहां", "wahan"}, {"कहाँ", "kahan"}, {"कहां", "kahan"},
            {"जहाँ", "jahan"}, {"जहां", "jahan"}, {"ख़ुशी", "khushi"},
            {"तेरे", "tere"}, {"मेरे", "mere"}, {"कुछ", "kuch"}, {"सच", "sach"},
            {"ज़िंदगी", "zindagi"}, {"जिंदगी", "zindagi"},
            {"दुनिया", "duniya"}, {"ख़ुद", "khud"}, {"खुद", "khud"},
        };
        for (String[] p : w) WORDS.put(p[0], p[1]);
    }

    /** True for Hindi (Devanagari) or Punjabi (Gurmukhi) script, both of which this class converts. */
    public static boolean hasDevanagari(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'ऀ' && c <= 'ॿ') || (c >= '਀' && c <= '੿')) return true;
        }
        return false;
    }

    public static boolean hasArabic(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '؀' && c <= 'ۿ') return true;
        }
        return false;
    }

    /** Gurmukhi sits in the same layout as Devanagari, 0x100 higher, apart from its two nasal and doubling signs. */
    private static String gurmukhiToDevanagari(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'ੰ') {
                out.append(ANUSVARA);
            } else if (c == 'ੱ') {
                // Addak doubles the next consonant.
                if (i + 1 < s.length()) {
                    out.append((char) (s.charAt(i + 1) - 0x100)).append(VIRAMA);
                }
            } else if (c >= '਀' && c <= '੯') {
                out.append((char) (c - 0x100));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripLatinAccents(String s) {
        boolean accented = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'À' && c <= 'ɏ') accented = true;
            if (c >= 'Ḁ' && c <= 'ỿ') accented = true;
        }
        if (!accented) return s;
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036F]", "");
    }

    /** Converts a line to plain English letters. Text already in plain English letters is returned unchanged. */
    public static String line(String s) {
        if (s == null) return null;
        if (!hasDevanagari(s)) return stripLatinAccents(s);
        // Decomposing splits letters like U+095B into a base plus nukta, the one form word() reads.
        s = Normalizer.normalize(gurmukhiToDevanagari(s), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isWordChar(c)) {
                word.append(c);
            } else {
                flush(word, out);
                if (c == '।') out.append('.');
                else if (c == '॥') out.append("..");
                else out.append(c);
            }
        }
        flush(word, out);
        String r = out.toString().trim();
        return r.isEmpty() ? r : Character.toUpperCase(r.charAt(0)) + r.substring(1);
    }

    private static boolean isWordChar(char c) {
        return c >= 'ऀ' && c <= 'ॿ' && c != '।' && c != '॥';
    }

    private static void flush(StringBuilder word, StringBuilder out) {
        if (word.length() == 0) return;
        out.append(word(word.toString()));
        word.setLength(0);
    }

    /** One unit of a word: a consonant plus its vowel. A null consonant is a standalone vowel. */
    private static final class Unit {
        String cons;
        String vowel = "a";
        boolean inherent;
        String nasal = "";
    }

    static String word(String w) {
        String known = WORDS.get(w);
        if (known != null) return known;

        List<Unit> units = new ArrayList<Unit>();
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c >= '०' && c <= '९') {
                Unit u = new Unit();
                u.vowel = String.valueOf((char) ('0' + (c - '०')));
                units.add(u);
            } else if (CONS.containsKey(c)) {
                Unit u = new Unit();
                boolean dot = i + 1 < w.length() && w.charAt(i + 1) == NUKTA_SIGN;
                u.cons = dot && NUKTA.containsKey(c) ? NUKTA.get(c) : CONS.get(c);
                if (dot) i++;
                u.inherent = true;
                if (i + 1 < w.length() && w.charAt(i + 1) == VIRAMA) {
                    u.vowel = "";
                    u.inherent = false;
                    i++;
                } else if (i + 1 < w.length() && MATRA.containsKey(w.charAt(i + 1))) {
                    u.vowel = MATRA.get(w.charAt(i + 1));
                    u.inherent = false;
                    i++;
                }
                units.add(u);
            } else if (VOWEL.containsKey(c)) {
                Unit u = new Unit();
                u.vowel = VOWEL.get(c);
                units.add(u);
            } else if ((c == ANUSVARA || c == CHANDRA) && !units.isEmpty()) {
                units.get(units.size() - 1).nasal = "n";
            } else if (c == VISARGA && !units.isEmpty()) {
                units.get(units.size() - 1).nasal += "h";
            }
        }
        if (units.isEmpty()) return "";

        dropSilentVowels(units);

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            Unit next = i + 1 < units.size() ? units.get(i + 1) : null;
            if (u.cons != null) out.append(spellCons(u, i));
            out.append(spellVowel(u, next));
            if (!u.nasal.isEmpty()) {
                boolean lip = next != null && next.cons != null && (next.cons.startsWith("p") || next.cons.startsWith("b") || next.cons.startsWith("m"));
                out.append(lip && u.nasal.equals("n") ? "m" : u.nasal);
            }
        }
        return out.toString();
    }

    /** Right to left: a silent "a" sits after a vowel and one consonant, before a consonant and vowel, or word-final. */
    private static void dropSilentVowels(List<Unit> u) {
        int n = u.size();
        Unit last = u.get(n - 1);
        if (n > 1 && last.inherent && last.cons != null && last.nasal.isEmpty()) {
            last.vowel = "";
            last.inherent = false;
        }
        for (int i = n - 2; i >= 1; i--) {
            Unit cur = u.get(i), prev = u.get(i - 1), next = u.get(i + 1);
            if (!cur.inherent || cur.cons == null || !cur.nasal.isEmpty()) continue;
            boolean vowelBefore = !prev.vowel.isEmpty() && prev.nasal.isEmpty();
            boolean vowelAfter = next.cons != null && !next.vowel.isEmpty();
            if (vowelBefore && vowelAfter) {
                cur.vowel = "";
                cur.inherent = false;
            }
        }
    }

    private static String spellCons(Unit u, int index) {
        // Hindi writes one letter where English readers expect "w" before an "a" sound or at a word's start.
        if (u.cons.equals("v") && (u.vowel.startsWith("a") || index == 0)) return "w";
        return u.cons;
    }

    private static String spellVowel(Unit u, Unit next) {
        if (u.vowel.equals("aa") && u.cons != null && next == null && u.nasal.isEmpty()) return "a";
        return u.vowel;
    }
}
