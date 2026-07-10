package eu.lixko.jarband.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import eu.lixko.jarband.dsp.channelizer.LogicalChannel;
import eu.lixko.jarband.recording.UtteranceDbWriter;

final class HistoricalRecordings {
    private final Path outputDir;
    private final LiveAudioHub hub;
    private final int opusFrameMillis;

    HistoricalRecordings(Path outputDir, LiveAudioHub hub, int opusSampleRateHz, int opusFrameMillis) {
        this.outputDir = outputDir;
        this.hub = hub;
        this.opusFrameMillis = opusFrameMillis;
    }

    int seedLastActivityFromDbEnds() throws IOException {
        int seeded = 0;
        for (LogicalChannel channel : hub.channels()) {
            long lastActivityMillis = latestDbEndMillis(channel);
            if (lastActivityMillis > 0) {
                hub.seedLastActivity(channel.id(), lastActivityMillis);
                seeded++;
            }
        }
        return seeded;
    }

    private long latestDbEndMillis(LogicalChannel channel) throws IOException {
        Path channelDir = outputDir.resolve(channel.name());
        if (!Files.isDirectory(channelDir)) {
            return 0;
        }
        long latest = 0;
        try (Stream<Path> files = Files.list(channelDir)) {
            for (Path db : files.filter(path -> path.getFileName().toString().endsWith(".udb")).toList()) {
                UdbRecord record = readLastDbRecord(db);
                if (record == null) {
                    continue;
                }
                String base = db.getFileName().toString().replaceFirst("\\.udb$", "");
                Path opus = channelDir.resolve(base + ".opus");
                if (!Files.isRegularFile(opus)) {
                    continue;
                }
                latest = Math.max(latest, endMillis(record,
                        (int) Math.min(Integer.MAX_VALUE, Files.size(opus))));
            }
        }
        return latest;
    }

    private static UdbRecord readLastDbRecord(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long recordCount = (channel.size() - UtteranceDbWriter.HEADER_BYTES) / UtteranceDbWriter.RECORD_BYTES;
            if (recordCount <= 0) {
                return null;
            }
            ByteBuffer record = ByteBuffer.allocate(UtteranceDbWriter.RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            channel.position(UtteranceDbWriter.HEADER_BYTES + (recordCount - 1) * UtteranceDbWriter.RECORD_BYTES);
            while (record.hasRemaining()) {
                if (channel.read(record) < 0) {
                    return null;
                }
            }
            record.flip();
            long startMillis = record.getLong();
            int opusOffset = record.getInt();
            return new UdbRecord(startMillis, opusOffset, record.getShort() & 0xffff, record.getShort());
        }
    }

    private long endMillis(UdbRecord record, int endOffset) {
        if (record.durationUnits != UtteranceDbWriter.DURATION_INDEFINITE) {
            return record.startMillis + record.durationUnits * (long) UtteranceDbWriter.DURATION_UNIT_MILLIS;
        }
        return estimateEndMillis(record.startMillis, record.opusOffset, endOffset);
    }

    private long estimateEndMillis(long startMillis, int startOffset, int endOffset) {
        int bytes = Math.max(0, endOffset - startOffset);
        int estimatedPackets = Math.max(1, bytes / 80);
        return startMillis + (long) estimatedPackets * opusFrameMillis;
    }

    private record UdbRecord(long startMillis, int opusOffset, int durationUnits, short averageSnrQ8_8) {}
}
