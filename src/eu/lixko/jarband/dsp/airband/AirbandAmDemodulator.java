package eu.lixko.jarband.dsp.airband;

public final class AirbandAmDemodulator {
    private final float carrierAlpha;
    private final float levelAlpha;
    private final Biquad highPass;
    private final Biquad lowPass1;
    private final Biquad lowPass2;
    private float carrier;
    private float level = 0.02f;

    public AirbandAmDemodulator(double sampleRate) {
        this.carrierAlpha = onePoleAlpha(sampleRate, 8.0);
        this.levelAlpha = onePoleAlpha(sampleRate, 3.0);
        this.highPass = Biquad.highPass(sampleRate, 120.0, 0.707);
        this.lowPass1 = Biquad.lowPass(sampleRate, 2_350.0, 0.707);
        this.lowPass2 = Biquad.lowPass(sampleRate, 2_350.0, 0.707);
    }

    public float demodulate(float i, float q) {
        float envelope = (float) Math.sqrt(i * i + q * q);
        carrier += carrierAlpha * (envelope - carrier);
        float normalized = carrier > 1.0e-6f ? envelope / carrier - 1.0f : 0.0f;

        float audio = highPass.process(normalized);
        audio = lowPass1.process(audio);
        audio = lowPass2.process(audio);

        level += levelAlpha * (Math.abs(audio) - level);
        float gain = 0.18f / Math.max(level, 0.002f);
        return clamp(audio * gain, -1.0f, 1.0f);
    }

    private static float onePoleAlpha(double sampleRate, double cutoffHz) {
        double dt = 1.0 / sampleRate;
        double rc = 1.0 / (2.0 * Math.PI * cutoffHz);
        return (float) (dt / (rc + dt));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Biquad {
        private final float b0;
        private final float b1;
        private final float b2;
        private final float a1;
        private final float a2;
        private float z1;
        private float z2;

        private Biquad(float b0, float b1, float b2, float a1, float a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        static Biquad lowPass(double sampleRate, double cutoffHz, double q) {
            double w0 = 2.0 * Math.PI * cutoffHz / sampleRate;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double b0 = (1.0 - cos) * 0.5;
            double b1 = 1.0 - cos;
            double b2 = (1.0 - cos) * 0.5;
            double a0 = 1.0 + alpha;
            double a1 = -2.0 * cos;
            double a2 = 1.0 - alpha;
            return normalized(b0, b1, b2, a0, a1, a2);
        }

        static Biquad highPass(double sampleRate, double cutoffHz, double q) {
            double w0 = 2.0 * Math.PI * cutoffHz / sampleRate;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double b0 = (1.0 + cos) * 0.5;
            double b1 = -(1.0 + cos);
            double b2 = (1.0 + cos) * 0.5;
            double a0 = 1.0 + alpha;
            double a1 = -2.0 * cos;
            double a2 = 1.0 - alpha;
            return normalized(b0, b1, b2, a0, a1, a2);
        }

        private static Biquad normalized(double b0, double b1, double b2, double a0, double a1, double a2) {
            return new Biquad((float) (b0 / a0), (float) (b1 / a0), (float) (b2 / a0),
                    (float) (a1 / a0), (float) (a2 / a0));
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }
}
