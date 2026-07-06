package eu.lixko.jarband.recording;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jarband.fft.Opus;

public final class OpusFloatEncoder implements AutoCloseable {
    private final Arena arena = Arena.ofShared();
    private final MemorySegment encoder;
    private final MemorySegment pcm;
    private final MemorySegment packet;
    private final int frameSamples;
    private final int maxPacketBytes;

    public OpusFloatEncoder(int sampleRate, int bitrate, int frameMillis, int complexity) {
        this.frameSamples = sampleRate * frameMillis / 1000;
        this.maxPacketBytes = 4096;
        MemorySegment error = Opus.allocError(arena);
        this.encoder = Opus.opus_encoder_create(sampleRate, 1, Opus.OPUS_APPLICATION_VOIP, error);
        int rc = Opus.readError(error);
        if (rc != Opus.OPUS_OK) {
            throw new IllegalStateException("opus_encoder_create failed: " + rc);
        }
        Opus.setEncoderBitrate(encoder, bitrate);
        Opus.setEncoderVbr(encoder, 1);
        Opus.setEncoderSignal(encoder, Opus.OPUS_SIGNAL_VOICE);
        Opus.setEncoderComplexity(encoder, complexity);
        this.pcm = arena.allocate((long) frameSamples * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
        this.packet = arena.allocate(maxPacketBytes);
    }

    public int frameSamples() {
        return frameSamples;
    }

    public int encode(float[] samples, byte[] output) {
        if (samples.length < frameSamples) {
            throw new IllegalArgumentException("Need " + frameSamples + " samples");
        }
        for (int i = 0; i < frameSamples; i++) {
            pcm.setAtIndex(ValueLayout.JAVA_FLOAT, i, samples[i]);
        }
        int bytes = Opus.opus_encode_float(encoder, pcm, frameSamples, packet, maxPacketBytes);
        if (bytes < 0) {
            throw new IllegalStateException("opus_encode_float failed: " + bytes);
        }
        for (int i = 0; i < bytes; i++) {
            output[i] = packet.get(ValueLayout.JAVA_BYTE, i);
        }
        return bytes;
    }

    @Override
    public void close() {
        Opus.opus_encoder_destroy(encoder);
        arena.close();
    }
}
