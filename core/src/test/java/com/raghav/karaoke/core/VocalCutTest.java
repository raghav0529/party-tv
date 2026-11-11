package com.raghav.karaoke.core;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class VocalCutTest {
    private static final int SR = 48000;

    private static ByteBuffer tone(double hz, int frames, int amp) {
        ByteBuffer b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            short v = (short) (amp * Math.sin(2 * Math.PI * hz * i / SR));
            b.putShort(v).putShort(v);
        }
        b.flip();
        return b;
    }

    private static double rms(ByteBuffer b, int fromFrame, int frames) {
        ByteBuffer d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        double sum = 0;
        int n = 0;
        for (int i = fromFrame; i < frames; i++) {
            double l = d.getShort(i * 4), r = d.getShort(i * 4 + 2);
            sum += l * l + r * r;
            n += 2;
        }
        return Math.sqrt(sum / n);
    }

    private static ByteBuffer run(VocalCut v, ByteBuffer in) {
        ByteBuffer out = ByteBuffer.allocate(in.remaining());
        v.process(in, out);
        assertEquals(0, in.remaining());
        assertEquals(out.capacity(), out.position());
        return out;
    }

    @Test
    public void identicalChannelsCancelAboveBass() {
        VocalCut v = new VocalCut(SR);
        v.setEnabled(true);
        v.reset();
        int frames = SR / 2;
        ByteBuffer out = run(v, tone(1000, frames, 20000));
        assertTrue("attenuation too weak", rms(out, SR / 10, frames) < 0.15 * 20000 / Math.sqrt(2));
    }

    @Test
    public void bassSurvives() {
        VocalCut v = new VocalCut(SR);
        v.setEnabled(true);
        v.reset();
        int frames = SR / 2;
        ByteBuffer out = run(v, tone(50, frames, 20000));
        assertTrue(rms(out, SR / 10, frames) > 0.8 * 20000 / Math.sqrt(2));
    }

    @Test
    public void sideSignalIsKept() {
        VocalCut v = new VocalCut(SR);
        v.setEnabled(true);
        v.reset();
        ByteBuffer in = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        in.putShort((short) 1000).putShort((short) -1000).flip();
        ByteBuffer out = run(v, in).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1200, out.getShort(0));
        assertEquals(-1200, out.getShort(2));
    }

    @Test
    public void offIsIdenticalBytes() {
        VocalCut v = new VocalCut(SR);
        ByteBuffer in = tone(1000, 4800, 20000);
        byte[] expected = new byte[in.remaining()];
        in.duplicate().get(expected);
        ByteBuffer out = run(v, in);
        assertArrayEquals(expected, out.array());
    }

    @Test
    public void passthroughLeavesInputLimitAlone() {
        VocalCut v = new VocalCut(SR);
        ByteBuffer in = tone(1000, 100, 1000);
        int limit = in.limit();
        ByteBuffer out = ByteBuffer.allocate(40);
        v.process(in, out);
        assertEquals(limit, in.limit());
        assertEquals(40, in.position());
    }

    @Test
    public void turningOffReturnsToIdenticalBytesAfterRamp() {
        VocalCut v = new VocalCut(SR);
        v.setEnabled(true);
        v.reset();
        run(v, tone(1000, 4800, 20000));
        v.setEnabled(false);
        run(v, tone(1000, 4800, 20000)); // 100 ms, longer than the 50 ms ramp
        ByteBuffer in = tone(1000, 480, 20000);
        byte[] expected = new byte[in.remaining()];
        in.duplicate().get(expected);
        assertArrayEquals(expected, run(v, in).array());
    }

    @Test
    public void clampsAtExtremes() {
        VocalCut v = new VocalCut(SR);
        v.setEnabled(true);
        v.reset();
        ByteBuffer in = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        in.putShort(Short.MAX_VALUE).putShort(Short.MIN_VALUE).flip();
        ByteBuffer out = run(v, in).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(Short.MAX_VALUE, out.getShort(0));
        assertEquals(Short.MIN_VALUE, out.getShort(2));
    }

    @Test
    public void rampHasNoJumpWhenTurnedOn() {
        VocalCut v = new VocalCut(SR);
        run(v, tone(100, 480, 10000));
        v.setEnabled(true);
        ByteBuffer out = run(v, tone(100, 2400, 10000)).order(ByteOrder.LITTLE_ENDIAN);
        int maxStep = 0;
        for (int i = 1; i < 2400; i++) {
            maxStep = Math.max(maxStep, Math.abs(out.getShort(i * 4) - out.getShort((i - 1) * 4)));
        }
        assertTrue("step " + maxStep, maxStep < 400);
    }

    @Test
    public void partialOutputStopsAtWholeFrames() {
        VocalCut v = new VocalCut(SR);
        v.setEnabled(true);
        ByteBuffer in = tone(1000, 100, 1000);
        ByteBuffer out = ByteBuffer.allocate(42);
        v.process(in, out);
        assertEquals(40, out.position());
        assertEquals(40, in.position());
    }
}
