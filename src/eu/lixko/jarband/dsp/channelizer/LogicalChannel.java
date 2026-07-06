package eu.lixko.jarband.dsp.channelizer;

import java.util.Arrays;

public record LogicalChannel(int id, double frequencyHz, int[] pfbBins, String name) {
    public LogicalChannel {
        pfbBins = Arrays.copyOf(pfbBins, pfbBins.length);
    }

    @Override
    public int[] pfbBins() {
        // This is called for every logical channel on every channelized frame.
        // The constructor keeps ownership of the input array, and the recorder
        // treats the returned bins as immutable to avoid allocation in hot loops.
        return pfbBins;
    }

    public boolean merged25kHz() {
        return pfbBins.length == 3;
    }
}
