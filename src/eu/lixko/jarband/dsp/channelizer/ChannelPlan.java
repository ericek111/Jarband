package eu.lixko.jarband.dsp.channelizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public record ChannelPlan(PfbConfig pfb, List<LogicalChannel> channels) {
    private static final double AIRBAND_LOW_HZ = 118_000_000.0;
    private static final double AIRBAND_HIGH_HZ = 137_000_000.0;
    private static final double AIRBAND_RASTER_HZ = 25_000.0 / 3.0;

    public static ChannelPlan visibleAirband(PfbConfig pfb, List<Double> merge25kHzFrequencies) {
        return visibleAirband(pfb, merge25kHzFrequencies, List.of());
    }

    public static ChannelPlan visibleAirband(PfbConfig pfb, List<Double> merge25kHzFrequencies,
                                             List<Double> skipFrequencies) {
        double receiverLowHz = pfb.centerFrequency() - pfb.sampleRate() / 2.0;
        double receiverHighHz = pfb.centerFrequency() + pfb.sampleRate() / 2.0;
        double firstChannelHz = alignUp(Math.max(AIRBAND_LOW_HZ, receiverLowHz), AIRBAND_RASTER_HZ);
        double lastChannelHz = alignDown(Math.min(AIRBAND_HIGH_HZ, Math.nextDown(receiverHighHz)), AIRBAND_RASTER_HZ);

        if (firstChannelHz > lastChannelHz) {
            throw new IllegalStateException("SDR passband does not overlap the configured airband range");
        }

        return airband(pfb, firstChannelHz, lastChannelHz, merge25kHzFrequencies, skipFrequencies);
    }

    private static ChannelPlan airband(PfbConfig pfb, double firstChannelHz, double lastChannelHz,
                                       List<Double> merge25kHzFrequencies, List<Double> skipFrequencies) {
        var mergeBins = new HashSet<Integer>();
        for (double f : merge25kHzFrequencies) {
            mergeBins.add(binForFrequency(pfb, f));
        }
        var skipBins = new HashSet<Integer>();
        for (double f : skipFrequencies) {
            skipBins.add(binForFrequency(pfb, f));
        }

        var channels = new ArrayList<LogicalChannel>();
        int id = 0;
        for (double f = firstChannelHz; f <= lastChannelHz + AIRBAND_RASTER_HZ * 0.25; f += AIRBAND_RASTER_HZ) {
            int bin = binForFrequency(pfb, f);
            if (bin < 0 || bin >= pfb.branches()) {
                continue;
            }
            if (skipBins.contains(bin)) {
                continue;
            }
            int[] bins = mergeBins.contains(bin)
                    ? new int[] { wrapBin(bin - 1, pfb.branches()), bin, wrapBin(bin + 1, pfb.branches()) }
                    : new int[] { bin };
            channels.add(new LogicalChannel(id++, f, bins, channelName(f, bins.length == 3)));
        }
        return new ChannelPlan(pfb, List.copyOf(channels));
    }

    public int size() {
        return channels.size();
    }

    public static int binForFrequency(PfbConfig pfb, double frequencyHz) {
        double offsetHz = frequencyHz - pfb.centerFrequency();
        int signedBin = (int) Math.round(offsetHz / pfb.branchSpacingHz());
        return wrapBin(signedBin, pfb.branches());
    }

    public static double frequencyForBin(PfbConfig pfb, int bin) {
        int signedBin = bin <= pfb.branches() / 2 ? bin : bin - pfb.branches();
        return pfb.centerFrequency() + signedBin * pfb.branchSpacingHz();
    }

    private static String channelName(double frequencyHz, boolean merged25kHz) {
        if (merged25kHz) {
            return String.format(java.util.Locale.ROOT, "%.3f", frequencyHz / 1_000_000.0);
        }

        long hz = Math.round(frequencyHz);
        long base25kHz = Math.floorDiv(hz, 25_000L) * 25_000L;
        int offset = (int) Math.round((hz - base25kHz) / AIRBAND_RASTER_HZ);
        long identifierHz = base25kHz + switch (Math.floorMod(offset, 3)) {
            case 0 -> 5_000L;
            case 1 -> 10_000L;
            default -> 15_000L;
        };
        return String.format(java.util.Locale.ROOT, "%.3f", identifierHz / 1_000_000.0);
    }

    private static double alignUp(double frequencyHz, double rasterHz) {
        return Math.ceil(frequencyHz / rasterHz) * rasterHz;
    }

    private static double alignDown(double frequencyHz, double rasterHz) {
        return Math.floor(frequencyHz / rasterHz) * rasterHz;
    }

    private static int wrapBin(int bin, int branches) {
        return Math.floorMod(bin, branches);
    }
}
