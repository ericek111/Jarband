package eu.lixko.jarband.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import eu.lixko.jarband.recording.OpusFloatDecoder;
import eu.lixko.jarband.recording.UtteranceDbWriter;

public final class UtteranceDbConverter {
    private static final int OPUS_DECODE_RATE_HZ = 48_000;
    private static final float MAX_SNR_DB = UtteranceDbWriter.SNR_MAX_Q8_8 / 256.0f;

    private UtteranceDbConverter() {}

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of("recordings");
        int converted = convertTree(root);
        System.out.printf("Converted %,d utterance DB files under %s%n", converted, root);
    }

    public static int convertTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int converted = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path db : files.filter(path -> path.getFileName().toString().endsWith(".udb")).toList()) {
                if (convertFile(db)) {
                    converted++;
                }
            }
        }
        return converted;
    }

    private static boolean convertFile(Path db) throws IOException {
        if (isV2(db)) {
            return false;
        }
        Path opus = db.resolveSibling(db.getFileName().toString().replaceFirst("\\.udb$", ".opus"));
        if (!Files.isRegularFile(opus)) {
            System.err.println("Skipping " + db + ": missing " + opus.getFileName());
            return false;
        }
        List<OldRecord> oldRecords = readOldRecords(db);
        if (oldRecords.isEmpty()) {
            return false;
        }

        Path backup = db.resolveSibling(db.getFileName() + ".v1");
        Path tmp = db.resolveSibling(db.getFileName() + ".v2tmp");
        Files.deleteIfExists(tmp);
        try (UtteranceDbWriter writer = new UtteranceDbWriter(tmp)) {
            long opusSize = Files.size(opus);
            for (int i = 0; i < oldRecords.size(); i++) {
                OldRecord record = oldRecords.get(i);
                int endOffset = i + 1 < oldRecords.size()
                        ? oldRecords.get(i + 1).opusOffset
                        : (int) Math.min(Integer.MAX_VALUE, opusSize);
                long durationMillis = decodeDurationMillis(opus, record.opusOffset, endOffset);
                writer.append(record.startMillis, record.opusOffset, durationMillis, MAX_SNR_DB);
            }
        }
        if (!Files.exists(backup)) {
            Files.move(db, backup);
        } else {
            Files.delete(db);
        }
        Files.move(tmp, db, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Converted " + db + " (backup: " + backup.getFileName() + ")");
        return true;
    }

    private static long decodeDurationMillis(Path opus, int startOffset, int endOffset) throws IOException {
        long samples = 0;
        try (OggOpusPacketReader reader = new OggOpusPacketReader(opus, startOffset, endOffset);
             OpusFloatDecoder decoder = new OpusFloatDecoder(OPUS_DECODE_RATE_HZ)) {
            while (true) {
                List<byte[]> packets = reader.readPagePackets();
                if (packets.isEmpty()) {
                    break;
                }
                for (byte[] packet : packets) {
                    samples += decoder.decode(packet);
                }
            }
        }
        return Math.round(samples * 1000.0 / OPUS_DECODE_RATE_HZ);
    }

    private static List<OldRecord> readOldRecords(Path path) throws IOException {
        var records = new ArrayList<OldRecord>();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer record = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            while (true) {
                record.clear();
                int read = 0;
                while (record.hasRemaining()) {
                    int n = channel.read(record);
                    if (n < 0) break;
                    read += n;
                }
                if (read == 0) break;
                if (read < 12) {
                    throw new IOException("Short v1 record in " + path);
                }
                record.flip();
                records.add(new OldRecord(record.getLong(), record.getInt()));
            }
        }
        return records;
    }

    private static boolean isV2(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (channel.size() < UtteranceDbWriter.HEADER_BYTES) {
                return false;
            }
            ByteBuffer header = ByteBuffer.allocate(UtteranceDbWriter.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) {
                    return false;
                }
            }
            header.flip();
            return header.getInt() == UtteranceDbWriter.FORMAT_VERSION
                    && (channel.size() - UtteranceDbWriter.HEADER_BYTES) % UtteranceDbWriter.RECORD_BYTES == 0;
        }
    }

    private record OldRecord(long startMillis, int opusOffset) {}
}
