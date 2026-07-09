package eu.lixko.jarband.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import eu.lixko.jarband.dsp.channelizer.LogicalChannel;
import eu.lixko.jarband.recording.EncodedOpusFrame;
import eu.lixko.jarband.recording.UtteranceDbWriter;

final class HistoricalRecordings {
    private static final DateTimeFormatter WEEKLY_ROTATION_FORMAT =
            DateTimeFormatter.ofPattern("YYYY-'w'ww", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Path outputDir;
    private final LiveAudioHub hub;
    private final int opusSampleRateHz;
    private final int opusFrameMillis;

    HistoricalRecordings(Path outputDir, LiveAudioHub hub, int opusSampleRateHz, int opusFrameMillis) {
        this.outputDir = outputDir;
        this.hub = hub;
        this.opusSampleRateHz = opusSampleRateHz;
        this.opusFrameMillis = opusFrameMillis;
    }

    String indexJson(List<String> channelNames, long fromMillis, long toMillis) throws IOException {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\"type\":\"history_index\",\"utterances\":[");
        int count = 0;
        for (String name : channelNames) {
            LogicalChannel channel = hub.channelByName(name);
            if (channel == null) {
                continue;
            }
            for (Utterance utterance : utterances(channel, fromMillis, toMillis)) {
                if (count++ > 0) json.append(',');
                json.append('{')
                        .append("\"channel\":\"").append(LiveAudioHub.escape(channel.name())).append("\",")
                        .append("\"frequencyHz\":").append(Math.round(channel.frequencyHz())).append(',')
                        .append("\"startMillis\":").append(utterance.startMillis).append(',')
                        .append("\"endMillis\":").append(utterance.endMillis).append(',')
                        .append("\"durationMillis\":").append(durationMillis(utterance)).append(',')
                        .append("\"averageSnrDb\":").append(snrDb(utterance.averageSnrQ8_8))
                        .append('}');
            }
        }
        json.append("]}");
        return json.toString();
    }

    String recentJson(List<String> channelNames, long beforeMillis, int limit) throws IOException {
        int safeLimit = Math.clamp(limit, 1, 100);
        long safeBeforeMillis = beforeMillis <= 0 ? Long.MAX_VALUE : beforeMillis;
        var utterances = new ArrayList<Utterance>();
        if (channelNames.isEmpty()) {
            for (LogicalChannel channel : hub.channels()) {
                utterances.addAll(utterances(channel, 0, safeBeforeMillis - 1));
            }
        } else {
            for (String name : channelNames) {
                LogicalChannel channel = hub.channelByName(name);
                if (channel != null) {
                    utterances.addAll(utterances(channel, 0, safeBeforeMillis - 1));
                }
            }
        }
        utterances.removeIf(utterance -> utterance.startMillis >= safeBeforeMillis);
        utterances.sort(Comparator.comparingLong(Utterance::startMillis).reversed());
        boolean hasOlder = utterances.size() > safeLimit;
        int to = Math.min(utterances.size(), safeLimit);
        long newestMillis = to > 0 ? utterances.getFirst().startMillis : 0;
        long oldestMillis = to > 0 ? utterances.get(to - 1).startMillis : 0;

        StringBuilder json = new StringBuilder(4096);
        json.append("{\"type\":\"recent_history\",\"beforeMillis\":")
                .append(beforeMillis == Long.MAX_VALUE ? 0 : beforeMillis)
                .append(",\"limit\":").append(safeLimit)
                .append(",\"newestMillis\":").append(newestMillis)
                .append(",\"oldestMillis\":").append(oldestMillis)
                .append(",\"hasOlder\":").append(hasOlder)
                .append(",\"utterances\":[");
        for (int i = 0; i < to; i++) {
            if (i > 0) json.append(',');
            Utterance utterance = utterances.get(i);
            json.append('{')
                    .append("\"channel\":\"").append(LiveAudioHub.escape(utterance.channel.name())).append("\",")
                    .append("\"frequencyHz\":").append(Math.round(utterance.channel.frequencyHz())).append(',')
                    .append("\"startMillis\":").append(utterance.startMillis).append(',')
                    .append("\"endMillis\":").append(utterance.endMillis).append(',')
                    .append("\"durationMillis\":").append(durationMillis(utterance)).append(',')
                    .append("\"averageSnrDb\":").append(snrDb(utterance.averageSnrQ8_8))
                    .append('}');
        }
        json.append("]}");
        return json.toString();
    }

    List<EncodedOpusFrame> packets(List<String> channelNames, long fromMillis, long toMillis) throws IOException {
        var all = new ArrayList<EncodedOpusFrame>();
        for (String name : channelNames) {
            LogicalChannel channel = hub.channelByName(name);
            if (channel == null) {
                continue;
            }
            for (Utterance utterance : utterances(channel, fromMillis, toMillis)) {
                readPackets(channel, utterance, fromMillis, toMillis, all);
            }
        }
        all.sort(Comparator.comparingLong(EncodedOpusFrame::unixMillis)
                .thenComparingInt(EncodedOpusFrame::channelId)
                .thenComparingLong(EncodedOpusFrame::sequence));
        return all;
    }

    long[] latestUtteranceMillis(String channelName) throws IOException {
        LogicalChannel channel = hub.channelByName(channelName);
        if (channel == null) {
            return null;
        }
        return utterances(channel, 0, Long.MAX_VALUE).stream()
                .max(Comparator.comparingLong(Utterance::startMillis))
                .map(utterance -> new long[] { utterance.startMillis, utterance.endMillis })
                .orElse(null);
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
                latest = Math.max(latest, endMillis(record, null,
                        (int) Math.min(Integer.MAX_VALUE, Files.size(opus))));
            }
        }
        return latest;
    }

    private List<Utterance> utterances(LogicalChannel channel, long fromMillis, long toMillis) throws IOException {
        var result = new ArrayList<Utterance>();
        Path channelDir = outputDir.resolve(channel.name());
        if (!Files.isDirectory(channelDir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(channelDir)) {
            for (Path db : files.filter(path -> path.getFileName().toString().endsWith(".udb")).toList()) {
                String base = db.getFileName().toString().replaceFirst("\\.udb$", "");
                Path opus = channelDir.resolve(base + ".opus");
                if (!Files.isRegularFile(opus)) {
                    continue;
                }
                List<UdbRecord> records = readDb(db);
                long opusSize = Files.size(opus);
                for (int i = 0; i < records.size(); i++) {
                    UdbRecord current = records.get(i);
                    UdbRecord next = i + 1 < records.size() ? records.get(i + 1) : null;
                    int endOffset = i + 1 < records.size()
                            ? next.opusOffset
                            : (int) Math.min(Integer.MAX_VALUE, opusSize);
                    long endMillis = endMillis(current, next, endOffset);
                    if (endMillis >= fromMillis && current.startMillis <= toMillis) {
                        result.add(new Utterance(channel, opus, current.startMillis, endMillis,
                                current.opusOffset, endOffset, current.durationUnits, current.averageSnrQ8_8));
                    }
                }
            }
        }
        result.sort(Comparator.comparingLong(Utterance::startMillis));
        return result;
    }

    private void readPackets(LogicalChannel channel, Utterance utterance, long fromMillis, long toMillis,
                             List<EncodedOpusFrame> out) throws IOException {
        long packetMillis = utterance.startMillis;
        long sequence = 0;
        try (OggOpusPacketReader reader = new OggOpusPacketReader(utterance.opusPath,
                utterance.startOffset, utterance.endOffset)) {
            while (packetMillis <= toMillis) {
                List<byte[]> packets = reader.readPagePackets();
                if (packets.isEmpty()) {
                    break;
                }
                for (byte[] packet : packets) {
                    if (packetMillis >= fromMillis && packetMillis <= toMillis) {
                        out.add(new EncodedOpusFrame(channel.id(), channel.name(), packetMillis,
                                opusFrameMillis, opusSampleRateHz, sequence, packet));
                    }
                    packetMillis += opusFrameMillis;
                    sequence++;
                    if (packetMillis > toMillis) {
                        break;
                    }
                }
            }
        }
    }

    private static List<UdbRecord> readDb(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return readDb(channel);
        }
    }

    private static List<UdbRecord> readDb(FileChannel channel) throws IOException {
        var records = new ArrayList<UdbRecord>();
        ByteBuffer record = ByteBuffer.allocate(UtteranceDbWriter.RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(UtteranceDbWriter.HEADER_BYTES);
        while (true) {
            record.clear();
            int read = 0;
            while (record.hasRemaining()) {
                int n = channel.read(record);
                if (n < 0) break;
                read += n;
            }
            if (read == 0) break;
            if (read < UtteranceDbWriter.RECORD_BYTES) break;
            record.flip();
            long startMillis = record.getLong();
            int opusOffset = record.getInt();
            int durationUnits = record.getShort() & 0xffff;
            short averageSnrQ8_8 = record.getShort();
            records.add(new UdbRecord(startMillis, opusOffset, durationUnits, averageSnrQ8_8));
        }
        return records;
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

    private long endMillis(UdbRecord record, UdbRecord next, int endOffset) {
        if (record.durationUnits != UtteranceDbWriter.DURATION_INDEFINITE) {
            return record.startMillis + record.durationUnits * (long) UtteranceDbWriter.DURATION_UNIT_MILLIS;
        }
        if (next != null) {
            return next.startMillis;
        }
        return estimateEndMillis(record.startMillis, record.opusOffset, endOffset);
    }

    private static long durationMillis(Utterance utterance) {
        return utterance.durationUnits == UtteranceDbWriter.DURATION_INDEFINITE
                ? -1
                : utterance.durationUnits * (long) UtteranceDbWriter.DURATION_UNIT_MILLIS;
    }

    private long estimateEndMillis(long startMillis, int startOffset, int endOffset) {
        int bytes = Math.max(0, endOffset - startOffset);
        int estimatedPackets = Math.max(1, bytes / 80);
        return startMillis + (long) estimatedPackets * opusFrameMillis;
    }

    private static float snrDb(short q8_8) {
        return q8_8 / 256.0f;
    }

    @SuppressWarnings("unused")
    private static String rotationKey(long unixMillis) {
        return WEEKLY_ROTATION_FORMAT.format(Instant.ofEpochMilli(unixMillis));
    }

    private record UdbRecord(long startMillis, int opusOffset, int durationUnits, short averageSnrQ8_8) {}

    private record Utterance(LogicalChannel channel, Path opusPath, long startMillis, long endMillis,
                             int startOffset, int endOffset, int durationUnits, short averageSnrQ8_8) {}
}
