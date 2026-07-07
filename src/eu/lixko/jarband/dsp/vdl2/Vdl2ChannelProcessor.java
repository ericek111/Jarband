package eu.lixko.jarband.dsp.vdl2;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jarband.fft.LiquidDsp;

public final class Vdl2ChannelProcessor implements AutoCloseable {
    private static final int CHANNEL_FILTER_TAPS = 127;
    private static final float CHANNEL_FILTER_CUTOFF_HZ = 7_000.0f;
    private static final float CHANNEL_FILTER_STOPBAND_DB = 80.0f;

    private final int frequencyHz;
    private final int inputRateHz;
    private final int decimationFactor;
    private final Vdl2Demodulator demodulator;
    private final boolean offsetTuning;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment nco;
    private final MemorySegment channelDecimator;
    private final MemorySegment residual;
    private MemorySegment decimInput;
    private MemorySegment decimated;
    private int capacity;
    private int outputCapacity;
    private int residualSamples;
    private long residualStartNanos;
    private long unixMillisBase = System.currentTimeMillis();
    private long nanosBase = System.nanoTime();

    public Vdl2ChannelProcessor(int frequencyHz, double centerFrequencyHz, int inputRateHz, Vdl2SymbolSink sink) {
        this.frequencyHz = frequencyHz;
        this.inputRateHz = inputRateHz;
        this.decimationFactor = Math.max(2, inputRateHz / Vdl2Demodulator.SAMPLE_RATE);
        this.offsetTuning = Math.round(centerFrequencyHz) != frequencyHz;
        this.nco = LiquidDsp.nco_crcf_create(0);
        LiquidDsp.nco_crcf_set_frequency(nco,
                (float) (2.0 * Math.PI * (centerFrequencyHz - frequencyHz) / inputRateHz));
        MemorySegment taps = arena.allocate((long) CHANNEL_FILTER_TAPS * Float.BYTES,
                ValueLayout.JAVA_FLOAT.byteAlignment());
        LiquidDsp.liquid_firdes_kaiser(taps,
                CHANNEL_FILTER_CUTOFF_HZ / inputRateHz,
                CHANNEL_FILTER_STOPBAND_DB,
                0.0f);
        this.channelDecimator = LiquidDsp.firdecim_crcf_create(decimationFactor, taps, CHANNEL_FILTER_TAPS);
        this.residual = arena.allocate((long) decimationFactor * 2L * Float.BYTES,
                ValueLayout.JAVA_FLOAT.byteAlignment());
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
        int combinedSamples = residualSamples + sampleCount;
        long combinedStartNanos = residualSamples == 0 ? blockStartNanos : residualStartNanos;
        if (residualSamples > 0) {
            decimInput.asSlice(0, (long) residualSamples * 2L * Float.BYTES)
                    .copyFrom(residual.asSlice(0, (long) residualSamples * 2L * Float.BYTES));
        }
        mixBlock(samples, sampleCount, residualSamples);

        int outputSamples = combinedSamples / decimationFactor;
        if (outputSamples == 0) {
            saveResidual(0, combinedSamples, combinedStartNanos);
            return;
        }
        int rc = LiquidDsp.firdecim_crcf_execute_block(channelDecimator, decimInput, outputSamples, decimated);
        if (rc != 0) {
            throw new IllegalStateException("firdecim_crcf_execute_block failed: " + rc);
        }

        for (int n = 0; n < outputSamples; n++) {
            float filteredRe = decimated.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L);
            float filteredIm = decimated.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L + 1L);
            long nanos = combinedStartNanos
                    + Math.round(((long) n * decimationFactor + (decimationFactor - 1))
                    / (double) inputRateHz * 1_000_000_000.0);
            demodulator.accept(filteredRe, filteredIm, unixMillisForNanos(nanos));
        }
        saveResidual(outputSamples * decimationFactor, combinedSamples - outputSamples * decimationFactor,
                combinedStartNanos + Math.round(outputSamples * decimationFactor
                / (double) inputRateHz * 1_000_000_000.0));
    }

    private void ensureCapacity(int sampleCount) {
        int needed = sampleCount + decimationFactor;
        if (needed > capacity) {
            decimInput = arena.allocate((long) needed * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
            capacity = needed;
        }
        int outputNeeded = (needed + decimationFactor - 1) / decimationFactor;
        if (outputNeeded > outputCapacity) {
            decimated = arena.allocate((long) outputNeeded * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
            outputCapacity = outputNeeded;
        }
    }

    private void mixBlock(MemorySegment samples, int sampleCount, int outputOffsetSamples) {
        MemorySegment target = decimInput.asSlice((long) outputOffsetSamples * 2L * Float.BYTES,
                (long) sampleCount * 2L * Float.BYTES);
        if (!offsetTuning) {
            target.copyFrom(samples.asSlice(0, (long) sampleCount * 2L * Float.BYTES));
            return;
        }
        int rc = LiquidDsp.nco_crcf_mix_block_up(nco, samples, target, sampleCount);
        if (rc != 0) {
            throw new IllegalStateException("nco_crcf_mix_block_up failed: " + rc);
        }
    }

    private void saveResidual(int sourceOffsetSamples, int newResidualSamples, long newResidualStartNanos) {
        residualSamples = newResidualSamples;
        residualStartNanos = newResidualStartNanos;
        if (residualSamples > 0) {
            residual.asSlice(0, (long) residualSamples * 2L * Float.BYTES)
                    .copyFrom(decimInput.asSlice((long) sourceOffsetSamples * 2L * Float.BYTES,
                            (long) residualSamples * 2L * Float.BYTES));
        }
    }

    private long unixMillisForNanos(long nanos) {
        long elapsedNanos = nanos - nanosBase;
        return unixMillisBase + Math.round(elapsedNanos / 1_000_000.0);
    }

    @Override
    public void close() {
        LiquidDsp.firdecim_crcf_destroy(channelDecimator);
        LiquidDsp.nco_crcf_destroy(nco);
        arena.close();
    }
}
