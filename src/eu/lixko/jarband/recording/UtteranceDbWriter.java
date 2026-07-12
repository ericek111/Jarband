package eu.lixko.jarband.recording;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class UtteranceDbWriter implements AutoCloseable {
    public static final int FORMAT_VERSION = 2;
    public static final int HEADER_BYTES = Integer.BYTES;
    public static final int RECORD_BYTES = Long.BYTES + Integer.BYTES + Short.BYTES + Short.BYTES;
    public static final int DURATION_UNIT_MILLIS = 100;
    public static final int DURATION_INDEFINITE = 0xffff;
    public static final short SNR_MAX_Q8_8 = Short.MAX_VALUE;

    private final FileChannel channel;
    private final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final ArrayList<WrittenRange> writtenRanges = new ArrayList<>();

    public UtteranceDbWriter(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        ensureHeader();
    }

    public synchronized WrittenRange append(long unixMillisStart, int opusFileByteOffset,
                                            long durationMillis, float averageSnrDb) throws IOException {
        return writeRecord(channel.size(), unixMillisStart, opusFileByteOffset, durationMillis, averageSnrDb);
    }

    public synchronized WrittenRange appendOpen(long unixMillisStart, int opusFileByteOffset) throws IOException {
        long recordOffset = channel.size();
        return writeRecord(recordOffset, unixMillisStart, opusFileByteOffset, 0, 0.0f);
    }

    public synchronized WrittenRange update(long recordOffset, long unixMillisStart, int opusFileByteOffset,
                                            long durationMillis, float averageSnrDb) throws IOException {
        return writeRecord(recordOffset, unixMillisStart, opusFileByteOffset, durationMillis, averageSnrDb);
    }

    public synchronized void truncateFrom(long recordOffset) throws IOException {
        channel.truncate(recordOffset);
    }

    public synchronized List<WrittenRange> drainWrittenRanges() {
        List<WrittenRange> ranges = List.copyOf(writtenRanges);
        writtenRanges.clear();
        return ranges;
    }

    private WrittenRange writeRecord(long recordOffset, long unixMillisStart, int opusFileByteOffset,
                                     long durationMillis, float averageSnrDb) throws IOException {
        byte[] bytes = recordBytes(unixMillisStart, opusFileByteOffset, durationMillis, averageSnrDb);
        ByteBuffer record = ByteBuffer.wrap(bytes);
        channel.position(recordOffset);
        while (record.hasRemaining()) {
            channel.write(record);
        }
        WrittenRange range = new WrittenRange(Math.toIntExact(recordOffset), bytes);
        writtenRanges.add(range);
        return range;
    }

    private byte[] recordBytes(long unixMillisStart, int opusFileByteOffset,
                               long durationMillis, float averageSnrDb) {
        ByteBuffer buffer = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(unixMillisStart);
        buffer.putInt(opusFileByteOffset);
        buffer.putShort((short) durationTensMillis(durationMillis));
        buffer.putShort(snrQ8_8(averageSnrDb));
        return buffer.array();
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
            writtenRanges.add(new WrittenRange(0, ByteBuffer.allocate(HEADER_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(FORMAT_VERSION)
                    .array()));
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

    public record WrittenRange(int offset, byte[] bytes) {
        public WrittenRange {
            bytes = java.util.Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return bytes;
        }
    }
}
