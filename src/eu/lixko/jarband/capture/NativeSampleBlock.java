package eu.lixko.jarband.capture;

import java.lang.foreign.MemorySegment;

public record NativeSampleBlock(MemorySegment samples, int sampleCount, long firstSampleIndex, long capturedNanos) {
    public static final NativeSampleBlock POISON = new NativeSampleBlock(MemorySegment.NULL, -1, -1, -1);

    public boolean isPoison() {
        return sampleCount < 0;
    }

    public int availableSampleCount() {
        if (isPoison()) {
            return sampleCount;
        }
        long completeComplexSamples = samples.byteSize() / (2L * Float.BYTES);
        return (int) Math.min(sampleCount, completeComplexSamples);
    }
}
