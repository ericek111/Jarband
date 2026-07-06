package eu.lixko.jarband.dsp.airband;

public final class AirbandAmDemodulator {
    public static final double DEFAULT_IF_SAMPLE_RATE = 15_000.0;
    public static final double DEFAULT_BANDWIDTH = 10_000.0;
    private static final float OUTPUT_GAIN = 0.4f;
    private static final float OUTPUT_LIMIT = 0.8f;
    private static final boolean CARRIER_AGC = true;

    private final ComplexAgc carrierAgc;
    private final DcBlocker dcBlock;
    private final AudioAgc audioAgc;
    private final FirFilter lowPass;

    public AirbandAmDemodulator(double sampleRate) {
        this(sampleRate, Math.min(DEFAULT_BANDWIDTH, sampleRate * 0.9));
    }

    public AirbandAmDemodulator(double sampleRate, double bandwidth) {
        double usableBandwidth = Math.min(bandwidth, sampleRate * 0.9);
        this.carrierAgc = new ComplexAgc(120.0 / sampleRate, 20.0 / sampleRate);
        this.dcBlock = new DcBlocker(100.0 / sampleRate);
        this.audioAgc = new AudioAgc(120.0 / sampleRate, 20.0 / sampleRate);
        // SDR++ builds lowPass(bandwidth / 2, (bandwidth / 2) * 0.1, sampleRate).
        // That is a narrow post-envelope filter, so keep the same transition width here.
        this.lowPass = FirFilter.lowPass(usableBandwidth * 0.5, usableBandwidth * 0.05, sampleRate);
    }

    public int demodulateBlock(float[] interleavedIq, int count, float[] audioOut) {
        // Stage 1: normalize the complex carrier before envelope detection.
        // SDR++ skips post-envelope audio AGC when carrier AGC is enabled.
        if (CARRIER_AGC) {
            carrierAgc.apply(interleavedIq, count);
            dcBlock.envelopeToAudio(interleavedIq, count, audioOut);
        } else {
            dcBlock.envelopeToAudio(interleavedIq, count, audioOut);
            audioAgc.apply(audioOut, count);
        }

        // Stage 2: remove high-frequency envelope artifacts before resampling.
        lowPass.process(audioOut, count);

        // Stage 3: keep Opus input comfortably below full-scale.
        for (int n = 0; n < count; n++) {
            float scaled = audioOut[n] * OUTPUT_GAIN;
            audioOut[n] = scaled > OUTPUT_LIMIT ? OUTPUT_LIMIT : Math.max(scaled, -OUTPUT_LIMIT);
        }
        return count;
    }

    public void reset() {
    }

    private static final class DcBlocker {
        private final float rate;
        private float average;

        DcBlocker(double rate) {
            this.rate = (float) rate;
        }

        void envelopeToAudio(float[] interleavedIq, int count, float[] audioOut) {
            float average = this.average;
            float rate = this.rate;
            for (int n = 0; n < count; n++) {
                float i = interleavedIq[n * 2];
                float q = interleavedIq[n * 2 + 1];
                float envelope = (float) Math.sqrt(i * i + q * q);
                float corrected = envelope - average;
                // SDR++ integrates the corrected output, not the raw envelope error.
                average += corrected * rate;
                audioOut[n] = corrected;
            }
            this.average = average;
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

        void apply(float[] samples, int count) {
            // Keep these in locals for the block and write back once; this keeps
            // the loop readable without turning each sample into tiny method calls.
            float amp = this.amp;
            float attack = this.attack;
            float invAttack = this.invAttack;
            float decay = this.decay;
            float invDecay = this.invDecay;
            for (int n = 0; n < count; n++) {
                float sample = samples[n];
                float inAmp = Math.abs(sample);
                float gain = 1.0f;
                if (inAmp != 0.0f) {
                    amp = inAmp > amp
                            ? amp * invAttack + inAmp * attack
                            : amp * invDecay + inAmp * decay;
                    gain = Math.min(SET_POINT / amp, MAX_GAIN);
                }
                if (inAmp * gain > MAX_OUTPUT_AMP) {
                    amp = inAmp;
                    gain = Math.min(SET_POINT / amp, MAX_GAIN);
                }
                samples[n] = sample * gain;
            }
            this.amp = amp;
        }
    }

    private static final class ComplexAgc {
        private static final float SET_POINT = 1.0f;
        private static final float MAX_GAIN = 10_000_000.0f;
        private static final float MAX_OUTPUT_AMP = 10.0f;

        private final float attack;
        private final float invAttack;
        private final float decay;
        private final float invDecay;
        private float amp;

        ComplexAgc(double attack, double decay) {
            this.attack = (float) attack;
            this.invAttack = 1.0f - this.attack;
            this.decay = (float) decay;
            this.invDecay = 1.0f - this.decay;
        }

        void apply(float[] interleavedIq, int count) {
            // Carrier AGC runs before envelope detection. It normalizes carrier
            // level while preserving AM envelope enough for voice demodulation.
            float amp = this.amp;
            float attack = this.attack;
            float invAttack = this.invAttack;
            float decay = this.decay;
            float invDecay = this.invDecay;
            for (int n = 0; n < count; n++) {
                int offset = n * 2;
                float i = interleavedIq[offset];
                float q = interleavedIq[offset + 1];
                float inAmp = (float) Math.sqrt(i * i + q * q);
                if (inAmp == 0.0f) {
                    continue;
                }
                if (amp == 0.0f) {
                    amp = inAmp;
                } else {
                    amp = inAmp > amp
                            ? amp * invAttack + inAmp * attack
                            : amp * invDecay + inAmp * decay;
                }
                float gain = Math.min(SET_POINT / amp, MAX_GAIN);
                if (inAmp * gain > MAX_OUTPUT_AMP) {
                    amp = inAmp;
                    gain = Math.min(SET_POINT / amp, MAX_GAIN);
                }
                interleavedIq[offset] = i * gain;
                interleavedIq[offset + 1] = q * gain;
            }
            this.amp = amp;
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

        void process(float[] samples, int count) {
            float[] taps = this.taps;
            float[] delay = this.delay;
            int pos = this.pos;
            int delayLength = delay.length;
            for (int n = 0; n < count; n++) {
                delay[pos] = samples[n];
                float acc = 0.0f;
                int idx = pos;
                for (float tap : taps) {
                    acc += tap * delay[idx];
                    if (--idx < 0) {
                        idx = delayLength - 1;
                    }
                }
                samples[n] = acc;
                if (++pos == delayLength) {
                    pos = 0;
                }
            }
            this.pos = pos;
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
