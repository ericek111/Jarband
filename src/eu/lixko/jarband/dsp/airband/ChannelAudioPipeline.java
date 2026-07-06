package eu.lixko.jarband.dsp.airband;

final class ChannelAudioPipeline {
    private static final int INPUT_BATCH = 128;
    private static final int OUTPUT_BATCH = 256;

    private final AirbandAmDemodulator[] demods;
    private final LinearAudioResampler resampler;
    private final double inputRate;
    private final float[][] iqBatches;
    private final float[][] audioBatches;
    private final float[][] outputBatches;
    private final int[] iqFill;

    public ChannelAudioPipeline(int channels, double inputRate, int outputRate) {
        this.inputRate = inputRate;
        this.demods = new AirbandAmDemodulator[channels];
        this.resampler = new LinearAudioResampler(channels, inputRate, outputRate);
        this.iqBatches = new float[channels][INPUT_BATCH * 2];
        this.audioBatches = new float[channels][INPUT_BATCH];
        this.outputBatches = new float[channels][OUTPUT_BATCH];
        this.iqFill = new int[channels];
    }

    public void acceptIq(int channel, long unixMillis, float i, float q, AirbandFrameProcessor.AudioSink sink) {
        float[] iq = iqBatches[channel];
        int offset = iqFill[channel] * 2;
        iq[offset] = i;
        iq[offset + 1] = q;
        if (++iqFill[channel] == INPUT_BATCH) {
            flush(channel, unixMillis, sink);
        }
    }

    public void flush(int channel, long unixMillis, AirbandFrameProcessor.AudioSink sink) {
        int count = iqFill[channel];
        if (count == 0) {
            return;
        }
        AirbandAmDemodulator demod = demodulator(channel);
        demod.demodulateBlock(iqBatches[channel], count, audioBatches[channel]);
        // Resample in small batches to keep the recorder path allocation-free and
        // avoid one native/resampler call per demodulated channel sample.
        int produced = resampler.process(channel, audioBatches[channel], count, outputBatches[channel]);
        iqFill[channel] = 0;
        if (produced > 0) {
            sink.audio(channel, unixMillis, outputBatches[channel], produced);
        }
    }

    public void reset(int channel) {
        // Called when squelch closes. Any unflushed input is tail/noise by then,
        // so drop it instead of writing a final noisy fragment.
        iqFill[channel] = 0;
        if (demods[channel] != null) {
            demods[channel].reset();
        }
        resampler.reset(channel);
    }

    private AirbandAmDemodulator demodulator(int channel) {
        AirbandAmDemodulator demod = demods[channel];
        if (demod == null) {
            demod = new AirbandAmDemodulator(inputRate);
            demods[channel] = demod;
        }
        return demod;
    }
}
