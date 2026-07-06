package eu.lixko.jarband.dsp.channelizer;

import java.util.Arrays;

public record LogicalChannel(int id, double frequencyHz, int[] pfbBins, String name) {
    public LogicalChannel {
        pfbBins = Arrays.copyOf(pfbBins, pfbBins.length);
    }

    @Override
    public int[] pfbBins() {
        return Arrays.copyOf(pfbBins, pfbBins.length);
    }

    public boolean merged25kHz() {
        return pfbBins.length == 3;
    }
}
