package eu.lixko.jarband.recording;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WavFloatWriter implements AutoCloseable {
    private final FileChannel channel;
    private final int sampleRate;
    private final ByteBuffer sampleBuffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
    private int samples;

    public WavFloatWriter(Path path, int sampleRate) throws IOException {
        this.sampleRate = sampleRate;
        Files.createDirectories(path.getParent());
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        writeHeader(sampleRate, 0);
    }

    public void write(float sample) throws IOException {
        short pcm = (short) Math.round(Math.max(-1.0f, Math.min(1.0f, sample)) * 32767.0f);
        sampleBuffer.clear();
        sampleBuffer.putShort(pcm).flip();
        channel.write(sampleBuffer);
        samples++;
        if (samples % sampleRate == 0) {
            writeHeader(sampleRate, samples * 2);
        }
    }

    private void writeHeader(int sampleRate, int dataBytes) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(36 + dataBytes);
        header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(dataBytes);
        header.flip();
        channel.position(0);
        channel.write(header);
        channel.position(44L + dataBytes);
    }

    @Override
    public void close() throws IOException {
        int dataBytes = samples * 2;
        writeHeader(sampleRate, dataBytes);
        channel.close();
    }
}
