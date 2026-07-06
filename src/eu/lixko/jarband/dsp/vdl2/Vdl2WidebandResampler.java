package eu.lixko.jarband.dsp.vdl2;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jarband.capture.NativeSampleBlock;
import eu.lixko.jarband.fft.LiquidDsp;

public final class Vdl2WidebandResampler implements AutoCloseable {
    public static final int OUTPUT_RATE = Vdl2Demodulator.SAMPLE_RATE * 10;
    private static final float RESAMPLER_STOPBAND_ATTENUATION = 80.0f;

    private final double inputRateHz;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment resampler;
    private final MemorySegment outputCount;
    private MemorySegment output;
    private int outputCapacity;

    public Vdl2WidebandResampler(double inputRateHz) {
        this.inputRateHz = inputRateHz;
        this.resampler = LiquidDsp.msresamp_crcf_create(
                (float) (OUTPUT_RATE / inputRateHz),
                RESAMPLER_STOPBAND_ATTENUATION);
        this.outputCount = arena.allocate(ValueLayout.JAVA_INT);
    }

    public Result process(NativeSampleBlock block) {
        int inputSamples = block.availableSampleCount();
        ensureCapacity(inputSamples);
        int rc = LiquidDsp.msresamp_crcf_execute(
                resampler, block.samples(), inputSamples, output, outputCount);
        if (rc != 0) {
            throw new IllegalStateException("msresamp_crcf_execute failed: " + rc);
        }
        int produced = outputCount.get(ValueLayout.JAVA_INT, 0);
        long blockStartNanos = block.capturedNanos()
                - Math.round(inputSamples / inputRateHz * 1_000_000_000.0);
        return new Result(output, produced, blockStartNanos);
    }

    private void ensureCapacity(int inputSamples) {
        int needed = (int) Math.ceil(inputSamples * (OUTPUT_RATE / inputRateHz)) + 512;
        if (needed > outputCapacity) {
            output = arena.allocate((long) needed * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
            outputCapacity = needed;
        }
    }

    @Override
    public void close() {
        LiquidDsp.msresamp_crcf_destroy(resampler);
        arena.close();
    }

    public record Result(MemorySegment samples, int sampleCount, long blockStartNanos) {
    }
}
