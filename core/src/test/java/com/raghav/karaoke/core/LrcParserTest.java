package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class LrcParserTest {
    @Test
    public void multiStampLinesExpandAndSort() {
        List<LrcParser.Line> l = LrcParser.parse("[00:45.67][00:12.34]chorus\n[00:20.5]verse");
        assertEquals(3, l.size());
        assertEquals(12340, l.get(0).t);
        assertEquals("chorus", l.get(0).text);
        assertEquals("verse", l.get(1).text);
        assertEquals(20500, l.get(1).t);
        assertEquals(45670, l.get(2).t);
    }

    @Test
    public void threeDigitFractionAndNoFraction() {
        List<LrcParser.Line> l = LrcParser.parse("[01:02.345]a\n[01:03]b");
        assertEquals(62345, l.get(0).t);
        assertEquals(63000, l.get(1).t);
    }

    @Test
    public void badLinesAndTagsSkipped() {
        List<LrcParser.Line> l = LrcParser.parse("[ar:Someone]\nnot timed\n[xx:yy]junk\n[00:99.00]bad seconds\n\n[00:01.00]good");
        assertEquals(1, l.size());
        assertEquals("good", l.get(0).text);
        assertTrue(LrcParser.parse(null).isEmpty());
    }

    @Test
    public void devanagariKept() {
        List<LrcParser.Line> l = LrcParser.parse("﻿[00:05.00]तुम ही हो\r\n");
        assertEquals("तुम ही हो", l.get(0).text);
        assertEquals(5000, l.get(0).t);
    }

    @Test
    public void blankRunCollapsesButSingleBlankStays() {
        List<LrcParser.Line> l = LrcParser.parse("[00:01.00]a\n[00:02.00]\n[00:03.00]\n[00:04.00]b\n[00:05.00]\n[00:06.00]c");
        assertEquals(5, l.size());
        assertEquals(2000, l.get(1).t);
        assertEquals("", l.get(3).text);
    }

    @Test
    public void plainDropsBlanksAndUsesMinusOne() {
        List<LrcParser.Line> l = LrcParser.plain("one\n\n[ar:x]\n[00:01.00]two");
        assertEquals(2, l.size());
        assertEquals(-1, l.get(0).t);
        assertEquals("two", l.get(1).text);
    }
}
