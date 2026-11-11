package com.raghav.karaoke.core;

import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * Centre-channel removal for 16-bit little-endian interleaved stereo. Allocation free.
 *
 * Position and limit setters go through java.nio.Buffer on purpose. This module compiles against the JDK, where
 * ByteBuffer.position(int) returns ByteBuffer. That method does not exist on Android 9 (API 28), so calling it
 * directly would throw NoSuchMethodError on the TV.
 */
public final class VocalCut {
    // L-R is twice the sides of the original mix, so 0.5 keeps them as they were. Higher lifted the singer's echo.
    private static final float SIDE_GAIN = 0.6f;

    private final float lowPass;
    private final float rampStep;
    private volatile boolean enabled;
    private float mix;
    private float bass;
    private float bassPre;

    public VocalCut(int sampleRate) {
        int sr = sampleRate > 0 ? sampleRate : 44100;
        lowPass = (float) (1.0 - Math.exp(-2.0 * Math.PI * 120.0 / sr));
        rampStep = 1f / Math.max(1f, 0.05f * sr);
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }

    /** Clears filter state and settles the ramp. Call from the audio thread on flush. */
    public void reset() {
        bass = 0f;
        bassPre = 0f;
        mix = enabled ? 1f : 0f;
    }

    public void process(ByteBuffer in, ByteBuffer out) {
        int frames = Math.min(in.remaining(), out.remaining()) / 4;
        boolean on = enabled;
        if (!on && mix <= 0f) {
            int limit = in.limit();
            ((Buffer) in).limit(in.position() + frames * 4);
            out.put(in);
            ((Buffer) in).limit(limit);
            return;
        }
        float target = on ? 1f : 0f;
        float m = mix, b = bass, pre = bassPre;
        final float a = lowPass, step = rampStep;
        int ip = in.position(), op = out.position();
        for (int i = 0; i < frames; i++) {
            int l = (in.get(ip) & 0xff) | (in.get(ip + 1) << 8);
            int r = (in.get(ip + 2) & 0xff) | (in.get(ip + 3) << 8);
            ip += 4;
            if (m < target) {
                m += step;
                if (m > target) m = target;
            } else if (m > target) {
                m -= step;
                if (m < target) m = target;
            }
            // Two filters in a row: a single one lets too much of the voice through along with the bass.
            pre += a * ((l + r) * 0.5f - pre);
            b += a * (pre - b);
            float side = (l - r) * SIDE_GAIN;
            int ol = clamp(l + ((side + b) - l) * m);
            int or = clamp(r + ((b - side) - r) * m);
            out.put(op, (byte) ol);
            out.put(op + 1, (byte) (ol >> 8));
            out.put(op + 2, (byte) or);
            out.put(op + 3, (byte) (or >> 8));
            op += 4;
        }
        mix = m;
        bass = b;
        bassPre = pre;
        ((Buffer) in).position(ip);
        ((Buffer) out).position(op);
    }

    private static int clamp(float v) {
        return v >= 32767f ? 32767 : v <= -32768f ? -32768 : Math.round(v);
    }
}
