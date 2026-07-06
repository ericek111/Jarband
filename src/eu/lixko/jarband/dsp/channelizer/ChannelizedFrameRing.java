package eu.lixko.jarband.dsp.channelizer;

public final class ChannelizedFrameRing {
    private final float[][] frames;
    private final long[] capturedNanos;
    private final int branchCount;
    private long nextSequence;
    private int size;

    public ChannelizedFrameRing(int capacityFrames, int branchCount) {
        if (capacityFrames <= 0) {
            throw new IllegalArgumentException("capacityFrames must be positive");
        }
        this.frames = new float[capacityFrames][branchCount * 2];
        this.capturedNanos = new long[capacityFrames];
        this.branchCount = branchCount;
    }

    public long append(ChannelizedFrame frame) {
        if (frame.branchCount() != branchCount) {
            throw new IllegalArgumentException("Frame branch count changed from " + branchCount + " to " + frame.branchCount());
        }
        int slot = slot(nextSequence);
        System.arraycopy(frame.interleavedIqByBin(), 0, frames[slot], 0, frames[slot].length);
        capturedNanos[slot] = frame.capturedNanos();
        long sequence = nextSequence++;
        size = Math.min(size + 1, frames.length);
        return sequence;
    }

    public long oldestSequence() {
        return nextSequence - size;
    }

    public void replay(LogicalChannel channel, long firstSequence, long lastSequenceExclusive, ReplaySink sink) {
        long first = Math.max(firstSequence, oldestSequence());
        long last = Math.min(lastSequenceExclusive, nextSequence);
        for (long sequence = first; sequence < last; sequence++) {
            int slot = slot(sequence);
            float[] frame = frames[slot];
            float i = 0.0f;
            float q = 0.0f;
            int[] bins = channel.pfbBins();
            for (int bin : bins) {
                i += frame[bin * 2];
                q += frame[bin * 2 + 1];
            }
            sink.accept(i / bins.length, q / bins.length, capturedNanos[slot]);
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
