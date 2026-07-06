package eu.lixko.jarband.dsp.channelizer;

public final class ChannelizedFrame {
    private final float[] interleavedIqByBin;
    private final int branchCount;
    private long sequence;
    private long sourceSampleIndex;
    private long capturedNanos;

    ChannelizedFrame(float[] interleavedIqByBin, int branchCount) {
        this.interleavedIqByBin = interleavedIqByBin;
        this.branchCount = branchCount;
    }

    void reset(long sequence, long sourceSampleIndex, long capturedNanos) {
        this.sequence = sequence;
        this.sourceSampleIndex = sourceSampleIndex;
        this.capturedNanos = capturedNanos;
    }

    public float[] interleavedIqByBin() {
        return interleavedIqByBin;
    }

    public int branchCount() {
        return branchCount;
    }

    public long sequence() {
        return sequence;
    }

    public long sourceSampleIndex() {
        return sourceSampleIndex;
    }

    public long capturedNanos() {
        return capturedNanos;
    }

    public float i(int bin) {
        return interleavedIqByBin[bin * 2];
    }

    public float q(int bin) {
        return interleavedIqByBin[bin * 2 + 1];
    }
}
