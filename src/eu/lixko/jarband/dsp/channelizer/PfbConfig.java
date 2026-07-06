package eu.lixko.jarband.dsp.channelizer;

public record PfbConfig(int branches, int semiLength, float stopbandAttenuation,
                        double sampleRate, double centerFrequency) {
    private static final double AIRBAND_CHANNEL_RASTER_HZ = 25_000.0 / 3.0;

    public static PfbConfig forAirband(double sampleRate, double centerFrequency) {
        int branches = Math.max(1, (int) Math.round(sampleRate / AIRBAND_CHANNEL_RASTER_HZ));
        return new PfbConfig(branches, 16, 80.0f, sampleRate, centerFrequency);
    }

    public double branchSpacingHz() {
        return sampleRate / branches;
    }

    public double channelOutputRateHz() {
        return branchSpacingHz();
    }
}
