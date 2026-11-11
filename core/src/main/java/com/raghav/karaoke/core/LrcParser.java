package com.raghav.karaoke.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LrcParser {
    public static final class Line {
        public final long t;
        public final String text;

        public Line(long t, String text) {
            this.t = t;
            this.text = text;
        }
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    private LrcParser() {}

    /** Timed lines sorted by time. Bad lines are skipped. A run of blank lines collapses to its first. */
    public static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<Line>();
        if (lrc == null) return lines;
        for (String raw : stripBom(lrc).split("\\r?\\n|\\r")) {
            String s = raw.trim();
            List<Long> stamps = new ArrayList<Long>();
            int at = 0;
            Matcher m = STAMP.matcher(s);
            while (m.find(at) && m.start() == at) {
                int sec = Integer.parseInt(m.group(2));
                if (sec < 60) {
                    long ms = Integer.parseInt(m.group(1)) * 60000L + sec * 1000L;
                    String frac = m.group(3);
                    if (frac != null) {
                        int f = Integer.parseInt(frac);
                        ms += frac.length() == 1 ? f * 100 : frac.length() == 2 ? f * 10 : f;
                    }
                    stamps.add(ms);
                }
                at = m.end();
            }
            String text = s.substring(at).trim();
            for (Long t : stamps) lines.add(new Line(t, text));
        }
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.t, b.t);
            }
        });
        List<Line> out = new ArrayList<Line>(lines.size());
        for (Line l : lines) {
            boolean prevBlank = !out.isEmpty() && out.get(out.size() - 1).text.isEmpty();
            if (l.text.isEmpty() && prevBlank) continue;
            out.add(l);
        }
        return out;
    }

    /** True if at least one parsed line has words. */
    public static boolean hasText(List<Line> lines) {
        for (Line l : lines) if (!l.text.isEmpty()) return true;
        return false;
    }

    /** Untimed lines (t = -1). Blank lines and LRC tags are dropped. */
    public static List<Line> plain(String text) {
        List<Line> out = new ArrayList<Line>();
        if (text == null) return out;
        for (String raw : stripBom(text).split("\\r?\\n|\\r")) {
            String s = raw.trim();
            int at = 0;
            Matcher m = STAMP.matcher(s);
            while (m.find(at) && m.start() == at) at = m.end();
            s = s.substring(at).trim();
            if (s.isEmpty() || (s.startsWith("[") && s.endsWith("]"))) continue;
            out.add(new Line(-1, s));
        }
        return out;
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }
}
