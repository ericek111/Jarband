package eu.lixko.jarband.dsp.airband;

import java.io.IOException;
import java.nio.file.Path;

import eu.lixko.jarband.dsp.channelizer.ChannelizedFrame;
import eu.lixko.jarband.dsp.channelizer.LogicalChannel;
import eu.lixko.jarband.recording.OggOpusFile;
import eu.lixko.jarband.recording.OpusFloatEncoder;
import eu.lixko.jarband.recording.WavFloatWriter;

public final class ForcedChannelDemod implements AutoCloseable {
    private final LogicalChannel channel;
    private final AirbandAmDemodulator demod;
    private final OpusFloatEncoder encoder;
    private final OggOpusFile output;
    private final WavFloatWriter wav;
    private final float[] frameBuffer;
    private final byte[] packet = new byte[4096];
    private final double inputRate;
    private final int outputRate;
    private double resamplePosition;
    private float previousAudio;
    private int frameFill;
    private long samples;
    private double sumSquares;
    private float peak;
    private int strongestRelativeBin;
    private float strongestRelativePowerDb;

    public ForcedChannelDemod(LogicalChannel channel, double inputRate, Path opusPath, Path wavPath,
                              int opusSampleRate, int opusBitrate, int opusFrameMillis, int opusComplexity)
            throws IOException {
        this.channel = channel;
        this.inputRate = inputRate;
        this.outputRate = opusSampleRate;
        this.demod = new AirbandAmDemodulator(inputRate);
        this.encoder = new OpusFloatEncoder(opusSampleRate, opusBitrate, opusFrameMillis, opusComplexity);
        this.output = new OggOpusFile(opusPath, opusSampleRate, opusFrameMillis);
        this.wav = new WavFloatWriter(wavPath, opusSampleRate);
        this.frameBuffer = new float[encoder.frameSamples()];
    }

    public void accept(ChannelizedFrame frame) throws IOException {
        updatePowerScan(frame);
        float i = 0.0f;
        float q = 0.0f;
        int[] bins = channel.pfbBins();
        for (int bin : bins) {
            i += frame.i(bin);
            q += frame.q(bin);
        }
        i /= bins.length;
        q /= bins.length;
        float audio = demod.demodulate(i, q);
        writeResampled(audio);
        previousAudio = audio;
    }

    private void writeResampled(float audio) throws IOException {
        resamplePosition += outputRate / inputRate;
        while (resamplePosition >= 1.0) {
            resamplePosition -= 1.0;
            float t = (float) resamplePosition;
            writeAudio(previousAudio + (audio - previousAudio) * t);
        }
    }

    private void writeAudio(float audio) throws IOException {
        samples++;
        sumSquares += audio * audio;
        peak = Math.max(peak, Math.abs(audio));
        wav.write(audio);

        frameBuffer[frameFill++] = audio;
        if (frameFill == frameBuffer.length) {
            int bytes = encoder.encode(frameBuffer, packet);
            output.writeAudioPacket(packet, bytes);
            frameFill = 0;
        }
    }

    public String statusAndReset() {
        double rms = samples == 0 ? 0.0 : Math.sqrt(sumSquares / samples);
        String status = String.format(java.util.Locale.ROOT,
                "debug %.6f MHz bin %d audio rms %.4f peak %.4f strongest %+d bins %.1f dB",
                channel.frequencyHz() / 1_000_000.0,
                channel.pfbBins()[channel.pfbBins().length / 2],
                rms,
                peak,
                strongestRelativeBin,
                strongestRelativePowerDb);
        samples = 0;
        sumSquares = 0.0;
        peak = 0.0f;
        strongestRelativeBin = 0;
        strongestRelativePowerDb = Float.NEGATIVE_INFINITY;
        return status;
    }

    private void updatePowerScan(ChannelizedFrame frame) {
        int center = channel.pfbBins()[channel.pfbBins().length / 2];
        for (int rel = -8; rel <= 8; rel++) {
            int bin = Math.floorMod(center + rel, frame.branchCount());
            float i = frame.i(bin);
            float q = frame.q(bin);
            float db = 10.0f * (float) Math.log10(i * i + q * q + 1.0e-20f);
            if (db > strongestRelativePowerDb) {
                strongestRelativePowerDb = db;
                strongestRelativeBin = rel;
            }
        }
    }

    @Override
    public void close() throws IOException {
        IOException thrown = null;
        try {
            output.close();
        } catch (IOException e) {
            thrown = e;
        }
        try {
            wav.close();
        } catch (IOException e) {
            if (thrown == null) thrown = e;
            else thrown.addSuppressed(e);
        }
        encoder.close();
        if (thrown != null) throw thrown;
    }
}
