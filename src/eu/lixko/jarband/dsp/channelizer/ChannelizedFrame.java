package eu.lixko.jarband.dsp.channelizer;

public record ChannelizedFrame(float[] interleavedIqByBin, int branchCount, long sourceSampleIndex, long capturedNanos) {
    public float i(int bin) {
        return interleavedIqByBin[bin * 2];
    }

    public float q(int bin) {
        return interleavedIqByBin[bin * 2 + 1];
    }
}
