package eu.lixko.jarband.dsp.channelizer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public final class ChannelizedFrameRing {
    private final ChannelizedFrame[] frames;
    private final int branchCount;
    private long nextSequence;
    private int size;

    public ChannelizedFrameRing(int capacityFrames, int branchCount) {
        if (capacityFrames <= 0) {
            throw new IllegalArgumentException("capacityFrames must be positive");
        }
        this.branchCount = branchCount;
        this.frames = new ChannelizedFrame[capacityFrames];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new ChannelizedFrame(new float[branchCount * 2], branchCount);
        }
    }

    public ChannelizedFrame append(MemorySegment interleavedIqByBin, long sourceSampleIndex, long capturedNanos) {
        int slot = slot(nextSequence);
        ChannelizedFrame frame = frames[slot];
        MemorySegment.copy(interleavedIqByBin, ValueLayout.JAVA_FLOAT, 0,
                frame.interleavedIqByBin(), 0, branchCount * 2);
        long sequence = nextSequence++;
        frame.reset(sequence, sourceSampleIndex, capturedNanos);
        size = Math.min(size + 1, frames.length);
        return frame;
    }

    public long oldestSequence() {
        return nextSequence - size;
    }

    public void replay(LogicalChannel channel, long firstSequence, long lastSequenceExclusive, ReplaySink sink) {
        long first = Math.max(firstSequence, oldestSequence());
        long last = Math.min(lastSequenceExclusive, nextSequence);
        for (long sequence = first; sequence < last; sequence++) {
            int slot = slot(sequence);
            ChannelizedFrame frame = frames[slot];
            float[] iq = frame.interleavedIqByBin();
            float i = 0.0f;
            float q = 0.0f;
            int[] bins = channel.pfbBins();
            for (int bin : bins) {
                i += iq[bin * 2];
                q += iq[bin * 2 + 1];
            }
            sink.accept(i / bins.length, q / bins.length, frame.capturedNanos());
        }
    }

    private int slot(long sequence) {
        return (int) Math.floorMod(sequence, frames.length);
    }

    @FunctionalInterface
    public interface ReplaySink {
        void accept(float i, float q, long capturedNanos);
    }
}
