package eu.lixko.jarband.dsp.airband;

public final class ChannelAudioPipeline {
    private static final int INPUT_BATCH = 128;
    private static final int OUTPUT_BATCH = 256;

    private final AirbandAmDemodulator[] demods;
    private final LinearAudioResampler resampler;
    private final float[][] inputBatches;
    private final float[][] outputBatches;
    private final int[] inputFill;

    public ChannelAudioPipeline(int channels, double inputRate, int outputRate) {
        this.demods = new AirbandAmDemodulator[channels];
        this.resampler = new LinearAudioResampler(channels, inputRate, outputRate);
        this.inputBatches = new float[channels][INPUT_BATCH];
        this.outputBatches = new float[channels][OUTPUT_BATCH];
        this.inputFill = new int[channels];
        for (int channel = 0; channel < channels; channel++) {
            demods[channel] = new AirbandAmDemodulator(inputRate);
        }
    }

    public void acceptIq(int channel, long unixMillis, float i, float q, AudioSink sink) {
        acceptAudio(channel, unixMillis, demods[channel].demodulate(i, q), sink);
    }

    public void flush(int channel, long unixMillis, AudioSink sink) {
        int count = inputFill[channel];
        if (count == 0) {
            return;
        }
        int produced = resampler.process(channel, inputBatches[channel], count, outputBatches[channel]);
        inputFill[channel] = 0;
        if (produced > 0) {
            sink.accept(channel, unixMillis, outputBatches[channel], produced);
        }
    }

    public void reset(int channel) {
        inputFill[channel] = 0;
        resampler.reset(channel);
    }

    private void acceptAudio(int channel, long unixMillis, float audio, AudioSink sink) {
        float[] input = inputBatches[channel];
        input[inputFill[channel]++] = audio;
        if (inputFill[channel] == input.length) {
            flush(channel, unixMillis, sink);
        }
    }

    @FunctionalInterface
    public interface AudioSink {
        void accept(int channelId, long unixMillis, float[] audio, int length);
    }
}
