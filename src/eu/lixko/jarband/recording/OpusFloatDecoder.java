package eu.lixko.jarband.recording;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jarband.fft.Opus;

public final class OpusFloatDecoder implements AutoCloseable {
    private static final int MAX_FRAME_SAMPLES_48K = 5760;

    private final Arena arena = Arena.ofShared();
    private final MemorySegment decoder;
    private final MemorySegment packet;
    private final MemorySegment pcm;
    private final int maxPacketBytes;

    public OpusFloatDecoder(int sampleRate) {
        this.maxPacketBytes = 4096;
        MemorySegment error = Opus.allocError(arena);
        this.decoder = Opus.opus_decoder_create(sampleRate, 1, error);
        int rc = Opus.readError(error);
        if (rc != Opus.OPUS_OK) {
            throw new IllegalStateException("opus_decoder_create failed: " + rc);
        }
        this.packet = arena.allocate(maxPacketBytes);
        this.pcm = arena.allocate((long) MAX_FRAME_SAMPLES_48K * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
    }

    public int decode(byte[] encoded) {
        if (encoded.length > maxPacketBytes) {
            throw new IllegalArgumentException("Opus packet too large: " + encoded.length);
        }
        for (int i = 0; i < encoded.length; i++) {
            packet.set(ValueLayout.JAVA_BYTE, i, encoded[i]);
        }
        int samples = Opus.opus_decode_float(decoder, packet, encoded.length, pcm, MAX_FRAME_SAMPLES_48K, 0);
        if (samples < 0) {
            throw new IllegalStateException("opus_decode_float failed: " + samples);
        }
        return samples;
    }

    @Override
    public void close() {
        Opus.opus_decoder_destroy(decoder);
        arena.close();
    }
}
