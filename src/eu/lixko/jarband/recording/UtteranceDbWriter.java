package eu.lixko.jarband.recording;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class UtteranceDbWriter implements AutoCloseable {
    public static final int FORMAT_VERSION = 2;
    public static final int HEADER_BYTES = Integer.BYTES;
    public static final int RECORD_BYTES = Long.BYTES + Integer.BYTES + Short.BYTES + Short.BYTES;
    public static final int DURATION_UNIT_MILLIS = 100;
    public static final int DURATION_INDEFINITE = 0xffff;
    public static final short SNR_MAX_Q8_8 = Short.MAX_VALUE;

    private final FileChannel channel;
    private final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

    public UtteranceDbWriter(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        ensureHeader();
    }

    public synchronized void append(long unixMillisStart, int opusFileByteOffset,
                                    long durationMillis, float averageSnrDb) throws IOException {
        writeRecord(channel.size(), unixMillisStart, opusFileByteOffset, durationMillis, averageSnrDb);
    }

    public synchronized long appendOpen(long unixMillisStart, int opusFileByteOffset) throws IOException {
        long recordOffset = channel.size();
        writeRecord(recordOffset, unixMillisStart, opusFileByteOffset, 0, 0.0f);
        return recordOffset;
    }

    public synchronized void update(long recordOffset, long unixMillisStart, int opusFileByteOffset,
                                    long durationMillis, float averageSnrDb) throws IOException {
        writeRecord(recordOffset, unixMillisStart, opusFileByteOffset, durationMillis, averageSnrDb);
    }

    public synchronized void truncateFrom(long recordOffset) throws IOException {
        channel.truncate(recordOffset);
    }

    private void writeRecord(long recordOffset, long unixMillisStart, int opusFileByteOffset,
                             long durationMillis, float averageSnrDb) throws IOException {
        record.clear();
        record.putLong(unixMillisStart);
        record.putInt(opusFileByteOffset);
        record.putShort((short) durationTensMillis(durationMillis));
        record.putShort(snrQ8_8(averageSnrDb));
        record.flip();
        channel.position(recordOffset);
        while (record.hasRemaining()) {
            channel.write(record);
        }
    }

    private void ensureHeader() throws IOException {
        long size = channel.size();
        if (size == 0) {
            header.clear();
            header.putInt(FORMAT_VERSION);
            header.flip();
            while (header.hasRemaining()) {
                channel.write(header);
            }
            return;
        }
    }

    private static int durationTensMillis(long durationMillis) {
        long tens = Math.max(0, Math.round((double) durationMillis / DURATION_UNIT_MILLIS));
        return (int) Math.min(DURATION_INDEFINITE, tens);
    }

    private static short snrQ8_8(float snrDb) {
        if (!Float.isFinite(snrDb)) {
            return 0;
        }
        int value = Math.round(snrDb * 256.0f);
        return (short) Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
