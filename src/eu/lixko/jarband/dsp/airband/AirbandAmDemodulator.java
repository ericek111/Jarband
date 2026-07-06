package eu.lixko.jarband.dsp.airband;

public final class AirbandAmDemodulator {
    public static final double DEFAULT_IF_SAMPLE_RATE = 15_000.0;
    public static final double DEFAULT_BANDWIDTH = 10_000.0;
    private static final float OUTPUT_GAIN = 0.4f;
    private static final float OUTPUT_LIMIT = 0.8f;

    private final DcBlocker dcBlock;
    private final AudioAgc audioAgc;
    private final FirFilter lowPass;

    public AirbandAmDemodulator(double sampleRate) {
        this(sampleRate, Math.min(DEFAULT_BANDWIDTH, sampleRate * 0.9));
    }

    public AirbandAmDemodulator(double sampleRate, double bandwidth) {
        double usableBandwidth = Math.min(bandwidth, sampleRate * 0.9);
        this.dcBlock = new DcBlocker(100.0 / sampleRate);
        this.audioAgc = new AudioAgc(120.0 / sampleRate, 20.0 / sampleRate);
        // SDR++ builds lowPass(bandwidth / 2, (bandwidth / 2) * 0.1, sampleRate).
        // That is a narrow post-envelope filter, so keep the same transition width here.
        this.lowPass = FirFilter.lowPass(usableBandwidth * 0.5, usableBandwidth * 0.05, sampleRate);
    }

    public float demodulate(float i, float q) {
        // SDR++ AM with carrierAgc=false: magnitude -> DC blocker -> audio AGC -> LPF.
        float audio = (float) Math.sqrt(i * i + q * q);
        audio = dcBlock.process(audio);
        audio = audioAgc.process(audio);
        audio = lowPass.process(audio);
        return clamp(audio * OUTPUT_GAIN, -OUTPUT_LIMIT, OUTPUT_LIMIT);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class DcBlocker {
        private final float rate;
        private float average;

        DcBlocker(double rate) {
            this.rate = (float) rate;
        }

        float process(float sample) {
            float corrected = sample - average;
            // SDR++ integrates the corrected sample, not the raw input error.
            average += corrected * rate;
            return corrected;
        }
    }

    private static final class AudioAgc {
        private static final float SET_POINT = 0.65f;
        private static final float MAX_GAIN = 10_000_000.0f;
        private static final float MAX_OUTPUT_AMP = 0.8f;

        private final float attack;
        private final float invAttack;
        private final float decay;
        private final float invDecay;
        private float amp = 1.0f;

        AudioAgc(double attack, double decay) {
            this.attack = (float) attack;
            this.invAttack = 1.0f - this.attack;
            this.decay = (float) decay;
            this.invDecay = 1.0f - this.decay;
        }

        float process(float sample) {
            float inAmp = Math.abs(sample);
            float gain;
            if (inAmp != 0.0f) {
                amp = inAmp > amp
                        ? amp * invAttack + inAmp * attack
                        : amp * invDecay + inAmp * decay;
                gain = Math.min(SET_POINT / amp, MAX_GAIN);
            } else {
                gain = 1.0f;
            }

            // The C++ block can scan ahead inside a buffer before a clip. With one-sample
            // streaming we can only correct the current sample, but we preserve the limit.
            if (inAmp * gain > MAX_OUTPUT_AMP) {
                amp = inAmp;
                gain = Math.min(SET_POINT / amp, MAX_GAIN);
            }
            return sample * gain;
        }
    }

    private static final class FirFilter {
        private final float[] taps;
        private final float[] delay;
        private int pos;

        private FirFilter(float[] taps) {
            this.taps = taps;
            this.delay = new float[taps.length];
        }

        static FirFilter lowPass(double cutoff, double transitionWidth, double sampleRate) {
            int count = Math.max(31, (int) (3.8 * sampleRate / transitionWidth));
            float[] taps = new float[count];
            double omega = 2.0 * Math.PI * cutoff / sampleRate;
            double half = count / 2.0;
            double correction = omega / Math.PI;
            double sum = 0.0;
            for (int n = 0; n < count; n++) {
                // SDR++ centers even-length filters on a half-sample, so this is not
                // the usual integer-centered sinc used in many textbook FIR examples.
                double t = n - half + 0.5;
                double sinc = sinc(t * omega);
                double window = nuttall(t - half, count);
                taps[n] = (float) (sinc * window);
                taps[n] *= (float) correction;
                sum += taps[n];
            }
            for (int n = 0; n < count; n++) {
                taps[n] /= (float) sum;
            }
            return new FirFilter(taps);
        }

        float process(float sample) {
            delay[pos] = sample;
            float acc = 0.0f;
            int idx = pos;
            for (float tap : taps) {
                acc += tap * delay[idx];
                if (--idx < 0) {
                    idx = delay.length - 1;
                }
            }
            if (++pos == delay.length) {
                pos = 0;
            }
            return acc;
        }

        private static double sinc(double x) {
            return Math.abs(x) < 1.0e-12 ? 1.0 : Math.sin(x) / x;
        }

        private static double nuttall(double n, int count) {
            double a0 = 0.355768;
            double a1 = 0.487396;
            double a2 = 0.144232;
            double a3 = 0.012604;
            double x = 2.0 * Math.PI * n / count;
            return a0 - a1 * Math.cos(x) + a2 * Math.cos(2.0 * x) - a3 * Math.cos(3.0 * x);
        }
    }
}
