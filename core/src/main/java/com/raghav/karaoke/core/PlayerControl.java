package com.raghav.karaoke.core;

public interface PlayerControl {
    void play(Song song);
    void stop();
    void setPaused(boolean paused);
    void setVocalCut(boolean on);
    long positionMs();
    boolean isPlaying();
}
