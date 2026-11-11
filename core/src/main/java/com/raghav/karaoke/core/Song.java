package com.raghav.karaoke.core;

public final class Song {
    public String id;
    public String title, artist, album, image;
    public int durationSec;
    public String source;
    public transient String streamUrl;
    public String uid;
}
