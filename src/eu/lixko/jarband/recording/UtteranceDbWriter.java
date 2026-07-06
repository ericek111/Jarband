package eu.lixko.jarband.recording;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class UtteranceDbWriter implements AutoCloseable {
    private final FileChannel channel;
    private final ByteBuffer record = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);

    public UtteranceDbWriter(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    public synchronized void append(long unixMillisStart, int opusFileByteOffset) throws IOException {
        record.clear();
        record.putLong(unixMillisStart);
        record.putInt(opusFileByteOffset);
        record.flip();
        while (record.hasRemaining()) {
            channel.write(record);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
