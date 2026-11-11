package com.raghav.karaoke;

import android.os.SystemClock;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import com.raghav.karaoke.core.VocalCut;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Runs core VocalCut in ExoPlayer's audio chain. Stays active for 16-bit stereo even with the cut off, because
 * becoming active or inactive makes the sink reconfigure and the toggle would glitch. Other formats pass through.
 */
final class VocalCutAudioProcessor extends BaseAudioProcessor {
    private volatile boolean cutOn;
    private VocalCut cut; // Playback thread only.
    private int cutSampleRate = -1;
    private long lastMeterAt;

    void setCutEnabled(boolean on) {
        cutOn = on;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat format) {
        if (format.encoding != C.ENCODING_PCM_16BIT || format.channelCount != 2) {
            return AudioFormat.NOT_SET;
        }
        return format;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int size = input.remaining();
        if (size == 0) {
            return;
        }
        ByteBuffer out = replaceOutputBuffer(size);
        cut.setEnabled(cutOn);
        int start = input.position();
        cut.process(input, out);
        input.position(input.limit());
        out.flip();
        meter(input, start, out);
    }

    /** Logs measured levels about every 2 s, so "adb logcat -s KaraokeAudio" shows the cut really changing the sound. */
    private void meter(ByteBuffer in, int start, ByteBuffer out) {
        long t = SystemClock.elapsedRealtime();
        if (t - lastMeterAt < 2000) {
            return;
        }
        lastMeterAt = t;
        double inMid = 0, inSide = 0, outMid = 0, outSide = 0;
        int frames = Math.min(size(in, start), out.remaining()) / 4;
        for (int i = 0; i < frames; i++) {
            int a = start + i * 4, b = out.position() + i * 4;
            int il = (short) ((in.get(a) & 0xff) | (in.get(a + 1) << 8));
            int ir = (short) ((in.get(a + 2) & 0xff) | (in.get(a + 3) << 8));
            int ol = (short) ((out.get(b) & 0xff) | (out.get(b + 1) << 8));
            int or = (short) ((out.get(b + 2) & 0xff) | (out.get(b + 3) << 8));
            inMid += (il + ir) * (il + ir);
            inSide += (il - ir) * (il - ir);
            outMid += (ol + or) * (ol + or);
            outSide += (ol - or) * (ol - or);
        }
        int n = Math.max(1, frames);
        Log.i("KaraokeAudio", String.format(Locale.ROOT,
                "cutOn=%b rate=%d frames=%d inCentre=%.0f inSides=%.0f outCentre=%.0f outSides=%.0f",
                cutOn, cutSampleRate, frames, Math.sqrt(inMid / n), Math.sqrt(inSide / n),
                Math.sqrt(outMid / n), Math.sqrt(outSide / n)));
    }

    private static int size(ByteBuffer in, int start) {
        return in.limit() - start;
    }

    @Override
    protected void onFlush() {
        // The configured format takes effect at flush, and queueInput only ever runs after it.
        if (inputAudioFormat == AudioFormat.NOT_SET) {
            return;
        }
        if (cut == null || cutSampleRate != inputAudioFormat.sampleRate) {
            cutSampleRate = inputAudioFormat.sampleRate;
            cut = new VocalCut(cutSampleRate);
        } else {
            cut.reset();
        }
    }

    @Override
    protected void onReset() {
        cut = null;
        cutSampleRate = -1;
    }
}
