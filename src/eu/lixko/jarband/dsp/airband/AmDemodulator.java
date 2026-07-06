package eu.lixko.jarband.dsp.airband;

public final class AmDemodulator {
    public float demodulate(float i, float q, int channel, ChannelStateArrays state) {
        float magnitude = (float) Math.sqrt(i * i + q * q);
        float gain = state.agcGain[channel];
        float y = (magnitude - 0.25f) * gain;
        float target = magnitude > 1.0e-6f ? 0.35f / magnitude : gain;
        state.agcGain[channel] = clamp(gain + 0.0025f * (target - gain), 0.05f, 50.0f);
        return clamp(y, -1.0f, 1.0f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
