package eu.lixko.jarband.dsp.channelizer;

public final class ChannelizedFrame {
    private static final ChannelizedFrame POISON = new ChannelizedFrame(new float[0], 0, true);

    private final float[] interleavedIqByBin;
    private final int branchCount;
    private final boolean poison;
    private long sequence;
    private long sourceSampleIndex;
    private long capturedNanos;

    ChannelizedFrame(float[] interleavedIqByBin, int branchCount) {
        this(interleavedIqByBin, branchCount, false);
    }

    private ChannelizedFrame(float[] interleavedIqByBin, int branchCount, boolean poison) {
        this.interleavedIqByBin = interleavedIqByBin;
        this.branchCount = branchCount;
        this.poison = poison;
    }

    public static ChannelizedFrame poison() {
        return POISON;
    }

    public boolean isPoison() {
        return poison;
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
