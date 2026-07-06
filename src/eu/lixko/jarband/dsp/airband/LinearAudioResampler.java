package eu.lixko.jarband.dsp.airband;

public final class LinearAudioResampler {
    private final float[] previous;
    private final double[] phase;
    private final boolean[] primed;
    private final double inputSamplesPerOutput;

    public LinearAudioResampler(int channels, double inputRate, int outputRate) {
        this.previous = new float[channels];
        this.phase = new double[channels];
        this.primed = new boolean[channels];
        this.inputSamplesPerOutput = inputRate / outputRate;
    }

    public int process(int channel, float[] input, int count, float[] output) {
        int produced = 0;
        for (int n = 0; n < count; n++) {
            float current = input[n];
            if (!primed[channel]) {
                previous[channel] = current;
                phase[channel] = 0.0;
                primed[channel] = true;
                continue;
            }

            while (phase[channel] <= 1.0 && produced < output.length) {
                float t = (float) phase[channel];
                output[produced++] = previous[channel] + (current - previous[channel]) * t;
                phase[channel] += inputSamplesPerOutput;
            }
            phase[channel] -= 1.0;
            previous[channel] = current;
        }
        return produced;
    }

    public void reset(int channel) {
        previous[channel] = 0.0f;
        phase[channel] = 0.0;
        primed[channel] = false;
    }
}
