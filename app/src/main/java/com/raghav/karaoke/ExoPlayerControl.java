package com.raghav.karaoke;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.raghav.karaoke.core.ApiServer;
import com.raghav.karaoke.core.PlayerControl;
import com.raghav.karaoke.core.Song;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PlayerControl over ExoPlayer. Construct on the main thread; every other method may be called from any thread. */
final class ExoPlayerControl implements PlayerControl {
    private static final long TICK_MS = 250;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final Handler main = new Handler(Looper.getMainLooper());
    // The server may hold locks while it works; never let that stall the main thread.
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor();
    private final VocalCutAudioProcessor vocalCut = new VocalCutAudioProcessor();
    private final ExoPlayer player;

    private volatile ApiServer server;
    private volatile long tickPositionMs;
    private volatile long tickAtMs;
    private volatile boolean advancing;
    private volatile boolean playing;
    private volatile boolean released;
    private Song current; // Main thread only.
    private volatile boolean stalled;
    private static final long RETRY_MS = 5000;

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (!released && current != null && player.getPlaybackState() == Player.STATE_IDLE) {
                player.prepare();
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (released) {
                return;
            }
            refresh();
            main.postDelayed(this, TICK_MS);
        }
    };

    ExoPlayerControl(Context context) {
        Context app = context.getApplicationContext();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(app) {
            @Override
            protected AudioSink buildAudioSink(Context c, boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(c)
                        .setEnableFloatOutput(false) // Decoders then give the 16-bit PCM VocalCut works on.
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(new AudioProcessor[] {vocalCut})
                        .build();
            }
        };
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000);
        // Audio only on a 1 GB TV: hold seconds of compressed audio, not the 50 s default.
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 1500, 3000)
                .setTargetBufferBytes(4 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        player = new ExoPlayer.Builder(app, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(app, http)))
                .setLoadControl(load)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), false)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                refresh();
                if (state == Player.STATE_ENDED) {
                    current = null;
                    notifyServer(null);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                refresh();
                if (isPlaying && stalled) {
                    stalled = false;
                    final ApiServer s = server;
                    if (s != null && !released) {
                        callbacks.execute(new Runnable() {
                            @Override
                            public void run() {
                                s.onSongRecovered();
                            }
                        });
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                refresh();
                if (current != null && isNetworkError(error)) {
                    // Keep the song and the list. prepare() resumes from the same position once the internet is back.
                    stalled = true;
                    final ApiServer s = server;
                    if (s != null && !released) {
                        callbacks.execute(new Runnable() {
                            @Override
                            public void run() {
                                s.onSongStalled("Internet connection lost. The song will continue when it is back.");
                            }
                        });
                    }
                    main.removeCallbacks(retry);
                    main.postDelayed(retry, RETRY_MS);
                    return;
                }
                current = null;
                notifyServer("Could not play this song (" + error.getErrorCodeName() + ")");
            }
        });
        main.post(tick);
    }

    private static boolean isNetworkError(PlaybackException e) {
        return e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                || e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT;
    }

    void setServer(ApiServer server) {
        this.server = server;
    }

    private void notifyServer(final String errorOrNull) {
        final ApiServer s = server;
        if (s == null || released) {
            return;
        }
        callbacks.execute(new Runnable() {
            @Override
            public void run() {
                if (errorOrNull == null) {
                    s.onSongEnded();
                } else {
                    s.onSongFailed(errorOrNull);
                }
            }
        });
    }

    /** Main thread. */
    private void refresh() {
        tickPositionMs = player.getCurrentPosition();
        tickAtMs = SystemClock.elapsedRealtime();
        advancing = player.isPlaying();
        int state = player.getPlaybackState();
        // Buffering still counts as playing: the TV shows "Paused" only for a real pause.
        playing = player.getPlayWhenReady() && (state == Player.STATE_READY || state == Player.STATE_BUFFERING);
        // USB songs have no known duration until the player reads the file.
        Song song = current;
        long duration = player.getDuration();
        if (song != null && song.durationSec <= 0 && duration != C.TIME_UNSET && duration > 0) {
            song.durationSec = (int) (duration / 1000);
        }
    }

    private void onMain(final Runnable r) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!released) {
                    r.run();
                }
            }
        });
    }

    @Override
    public void play(final Song song) {
        tickPositionMs = 0;
        tickAtMs = SystemClock.elapsedRealtime();
        advancing = false;
        playing = true;
        onMain(new Runnable() {
            @Override
            public void run() {
                main.removeCallbacks(retry);
                stalled = false;
                current = song;
                String url = song.streamUrl;
                Uri uri = url.startsWith("/") ? Uri.fromFile(new File(url)) : Uri.parse(url);
                player.setMediaItem(MediaItem.fromUri(uri));
                player.prepare();
                player.setPlayWhenReady(true);
            }
        });
    }

    @Override
    public void stop() {
        tickPositionMs = 0;
        advancing = false;
        playing = false;
        onMain(new Runnable() {
            @Override
            public void run() {
                main.removeCallbacks(retry);
                stalled = false;
                current = null;
                player.stop();
                player.clearMediaItems();
            }
        });
    }

    @Override
    public void setPaused(final boolean paused) {
        playing = !paused;
        onMain(new Runnable() {
            @Override
            public void run() {
                player.setPlayWhenReady(!paused);
            }
        });
    }

    @Override
    public void setVocalCut(boolean on) {
        vocalCut.setCutEnabled(on);
    }

    @Override
    public long positionMs() {
        long position = tickPositionMs;
        return advancing ? position + (SystemClock.elapsedRealtime() - tickAtMs) : position;
    }

    @Override
    public boolean isPlaying() {
        return playing;
    }

    void release() {
        released = true;
        main.removeCallbacksAndMessages(null);
        callbacks.shutdown();
        player.release();
    }
}
