package eu.lixko.jarband.dsp.vdl2;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jarband.fft.LiquidDsp;

public final class Vdl2ChannelProcessor implements AutoCloseable {
    private static final int CHANNEL_FILTER_TAPS = 127;
    private static final float CHANNEL_FILTER_CUTOFF_HZ = 7_000.0f;
    private static final float CHANNEL_FILTER_STOPBAND_DB = 80.0f;
    private static final int OVERSAMPLE = Vdl2WidebandResampler.OUTPUT_RATE / Vdl2Demodulator.SAMPLE_RATE;
    private static final float TWO_PI = (float) (2.0 * Math.PI);
    private static final float[] SIN_LUT = new float[257];
    private static final float[] COS_LUT = new float[257];

    static {
        for (int i = 0; i < 256; i++) {
            float phase = TWO_PI * i / 256.0f;
            SIN_LUT[i] = (float) Math.sin(phase);
            COS_LUT[i] = (float) Math.cos(phase);
        }
        SIN_LUT[256] = SIN_LUT[0];
        COS_LUT[256] = COS_LUT[0];
    }

    private final int frequencyHz;
    private final Vdl2Demodulator demodulator;
    private final boolean offsetTuning;
    private final int downmixDphi;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment channelFilter = LiquidDsp.firfilt_crcf_create_kaiser(
            CHANNEL_FILTER_TAPS,
            CHANNEL_FILTER_CUTOFF_HZ / Vdl2WidebandResampler.OUTPUT_RATE,
            CHANNEL_FILTER_STOPBAND_DB,
            0.0f);
    private MemorySegment mixed;
    private MemorySegment filtered;
    private int capacity;
    private int downmixPhi;
    private int decimator;
    private long unixMillisBase = System.currentTimeMillis();
    private long nanosBase = System.nanoTime();

    public Vdl2ChannelProcessor(int frequencyHz, double centerFrequencyHz, Vdl2SymbolSink sink) {
        this.frequencyHz = frequencyHz;
        this.offsetTuning = Math.round(centerFrequencyHz) != frequencyHz;
        this.downmixDphi = (int) (((float) centerFrequencyHz - frequencyHz)
                / Vdl2WidebandResampler.OUTPUT_RATE * 256.0f * 65_536.0f);
        this.demodulator = new Vdl2Demodulator(frequencyHz, sink);
    }

    public int frequencyHz() {
        return frequencyHz;
    }

    Vdl2Demodulator.Stats statusAndReset() {
        return demodulator.statusAndReset();
    }

    public void process(MemorySegment samples, int sampleCount, long blockStartNanos) {
        ensureCapacity(sampleCount);
        mixBlock(samples, sampleCount);
        int rc = LiquidDsp.firfilt_crcf_execute_block(channelFilter, mixed, sampleCount, filtered);
        if (rc != 0) {
            throw new IllegalStateException("firfilt_crcf_execute_block failed: " + rc);
        }

        for (int n = 0; n < sampleCount; n++) {
            float filteredRe = filtered.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L);
            float filteredIm = filtered.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L + 1L);
            if (++decimator == OVERSAMPLE) {
                decimator = 0;
                long nanos = blockStartNanos
                        + Math.round(n / (double) Vdl2WidebandResampler.OUTPUT_RATE * 1_000_000_000.0);
                demodulator.accept(filteredRe, filteredIm, unixMillisForNanos(nanos));
            }
        }
    }

    private void ensureCapacity(int sampleCount) {
        if (sampleCount <= capacity) {
            return;
        }
        mixed = arena.allocate((long) sampleCount * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
        filtered = arena.allocate((long) sampleCount * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
        capacity = sampleCount;
    }

    private void mixBlock(MemorySegment samples, int sampleCount) {
        if (!offsetTuning) {
            mixed.asSlice(0, (long) sampleCount * 2L * Float.BYTES)
                    .copyFrom(samples.asSlice(0, (long) sampleCount * 2L * Float.BYTES));
            return;
        }
        int phi = downmixPhi;
        for (int n = 0; n < sampleCount; n++) {
            float re = samples.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L);
            float im = samples.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L + 1L);
            float sine = sinLut(phi);
            float cosine = cosLut(phi);
            mixed.setAtIndex(ValueLayout.JAVA_FLOAT, n * 2L, re * cosine - im * sine);
            mixed.setAtIndex(ValueLayout.JAVA_FLOAT, n * 2L + 1L, im * cosine + re * sine);
            phi = (phi + downmixDphi) & 0x00ff_ffff;
        }
        downmixPhi = phi;
    }

    private long unixMillisForNanos(long nanos) {
        long elapsedNanos = nanos - nanosBase;
        return unixMillisBase + Math.round(elapsedNanos / 1_000_000.0);
    }

    private static float sinLut(int phi) {
        int idx = phi >>> 16;
        float fract = (phi & 0xffff) / 65_536.0f;
        return SIN_LUT[idx] + (SIN_LUT[idx + 1] - SIN_LUT[idx]) * fract;
    }

    private static float cosLut(int phi) {
        int idx = phi >>> 16;
        float fract = (phi & 0xffff) / 65_536.0f;
        return COS_LUT[idx] + (COS_LUT[idx + 1] - COS_LUT[idx]) * fract;
    }

    @Override
    public void close() {
        LiquidDsp.firfilt_crcf_destroy(channelFilter);
        arena.close();
    }
}
