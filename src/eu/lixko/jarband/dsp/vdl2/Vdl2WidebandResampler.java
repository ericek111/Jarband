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
    private final double inputCenterFrequencyHz;
    private final double outputCenterFrequencyHz;
    private final boolean offsetTuning;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment nco;
    private final MemorySegment resampler;
    private final MemorySegment outputCount;
    private MemorySegment nativeInput;
    private MemorySegment input;
    private MemorySegment output;
    private int inputCapacity;
    private int outputCapacity;

    public Vdl2WidebandResampler(double inputRateHz, double inputCenterFrequencyHz, double outputCenterFrequencyHz) {
        this.inputRateHz = inputRateHz;
        this.inputCenterFrequencyHz = inputCenterFrequencyHz;
        this.outputCenterFrequencyHz = outputCenterFrequencyHz;
        this.offsetTuning = Math.round(inputCenterFrequencyHz) != Math.round(outputCenterFrequencyHz);
        this.nco = LiquidDsp.nco_crcf_create(0);
        LiquidDsp.nco_crcf_set_frequency(nco,
                (float) (2.0 * Math.PI * (inputCenterFrequencyHz - outputCenterFrequencyHz) / inputRateHz));
        this.resampler = LiquidDsp.msresamp_crcf_create(
                (float) (OUTPUT_RATE / inputRateHz),
                RESAMPLER_STOPBAND_ATTENUATION);
        this.outputCount = arena.allocate(ValueLayout.JAVA_INT);
    }

    public Result process(NativeSampleBlock block) {
        int inputSamples = block.availableSampleCount();
        ensureCapacity(inputSamples);
        if (offsetTuning) {
            nativeInput.asSlice(0, (long) inputSamples * 2L * Float.BYTES)
                    .copyFrom(block.samples().asSlice(0, (long) inputSamples * 2L * Float.BYTES));
            int mixRc = LiquidDsp.nco_crcf_mix_block_up(nco, nativeInput, input, inputSamples);
            if (mixRc != 0) {
                throw new IllegalStateException("nco_crcf_mix_block_up failed: " + mixRc);
            }
        } else {
            input.asSlice(0, (long) inputSamples * 2L * Float.BYTES)
                    .copyFrom(block.samples().asSlice(0, (long) inputSamples * 2L * Float.BYTES));
        }
        int rc = LiquidDsp.msresamp_crcf_execute(
                resampler, input, inputSamples, output, outputCount);
        if (rc != 0) {
            throw new IllegalStateException("msresamp_crcf_execute failed: " + rc);
        }
        int produced = outputCount.get(ValueLayout.JAVA_INT, 0);
        long blockStartNanos = block.capturedNanos()
                - Math.round(inputSamples / inputRateHz * 1_000_000_000.0);
        return new Result(output, produced, blockStartNanos);
    }

    public double inputCenterFrequencyHz() {
        return inputCenterFrequencyHz;
    }

    public double outputCenterFrequencyHz() {
        return outputCenterFrequencyHz;
    }

    private void ensureCapacity(int inputSamples) {
        if (inputSamples > inputCapacity) {
            nativeInput = arena.allocate((long) inputSamples * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
            input = arena.allocate((long) inputSamples * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
            inputCapacity = inputSamples;
        }
        int needed = (int) Math.ceil(inputSamples * (OUTPUT_RATE / inputRateHz)) + 512;
        if (needed > outputCapacity) {
            output = arena.allocate((long) needed * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
            outputCapacity = needed;
        }
    }

    @Override
    public void close() {
        LiquidDsp.msresamp_crcf_destroy(resampler);
        LiquidDsp.nco_crcf_destroy(nco);
        arena.close();
    }

    public record Result(MemorySegment samples, int sampleCount, long blockStartNanos) {
    }
}
