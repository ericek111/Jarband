package eu.lixko.jarband.capture;

import java.lang.foreign.MemorySegment;

public record NativeSampleBlock(MemorySegment samples, int sampleCount, long firstSampleIndex, long capturedNanos) {
    public static final NativeSampleBlock POISON = new NativeSampleBlock(MemorySegment.NULL, -1, -1, -1);

    public boolean isPoison() {
        return sampleCount < 0;
    }
}
