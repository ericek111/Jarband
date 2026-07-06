package eu.lixko.jarband.dsp.airband;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jarband.fft.LiquidDsp;

public final class AudioResampler implements AutoCloseable {
    private final Arena arena = Arena.ofShared();
    private final MemorySegment resampler;
    private final MemorySegment in;
    private final MemorySegment out;
    private final MemorySegment outCount;
    private final int maxInput;
    private final int maxOutput;

    public AudioResampler(double inputRate, int outputRate, int maxInputSamples) {
        this.maxInput = maxInputSamples;
        float ratio = (float) (outputRate / inputRate);
        this.maxOutput = Math.max(32, (int) Math.ceil(maxInputSamples * ratio) + 64);
        this.resampler = LiquidDsp.msresamp_rrrf_create(ratio, 70.0f);
        this.in = arena.allocate((long) maxInput * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
        this.out = arena.allocate((long) maxOutput * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
        this.outCount = arena.allocate(ValueLayout.JAVA_INT);
    }

    public float[] process(float[] samples, int count) {
        if (count > maxInput) {
            throw new IllegalArgumentException("Too many input samples: " + count);
        }
        for (int i = 0; i < count; i++) {
            in.setAtIndex(ValueLayout.JAVA_FLOAT, i, samples[i]);
        }
        int rc = LiquidDsp.msresamp_rrrf_execute(resampler, in, count, out, outCount);
        if (rc != 0) {
            throw new IllegalStateException("msresamp_rrrf_execute failed: " + rc);
        }
        int produced = outCount.get(ValueLayout.JAVA_INT, 0);
        produced = Math.min(produced, maxOutput);
        float[] result = new float[produced];
        for (int i = 0; i < produced; i++) {
            result[i] = out.getAtIndex(ValueLayout.JAVA_FLOAT, i);
        }
        return result;
    }

    @Override
    public void close() {
        LiquidDsp.msresamp_rrrf_destroy(resampler);
        arena.close();
    }
}
