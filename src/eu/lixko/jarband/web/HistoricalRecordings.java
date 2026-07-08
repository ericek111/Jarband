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
import java.util.PriorityQueue;
import java.util.stream.Stream;

import eu.lixko.jarband.dsp.channelizer.LogicalChannel;
import eu.lixko.jarband.recording.EncodedOpusFrame;

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
                        .append("\"endMillis\":").append(utterance.endMillis)
                        .append('}');
            }
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
                    int endOffset = i + 1 < records.size()
                            ? records.get(i + 1).opusOffset
                            : (int) Math.min(Integer.MAX_VALUE, opusSize);
                    long endMillis = i + 1 < records.size()
                            ? records.get(i + 1).startMillis
                            : estimateEndMillis(current.startMillis, current.opusOffset, endOffset);
                    if (endMillis >= fromMillis && current.startMillis <= toMillis) {
                        result.add(new Utterance(channel, opus, current.startMillis, endMillis,
                                current.opusOffset, endOffset));
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
        var records = new ArrayList<UdbRecord>();
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
                if (read < 12) break;
                record.flip();
                records.add(new UdbRecord(record.getLong(), record.getInt()));
            }
        }
        return records;
    }

    private long estimateEndMillis(long startMillis, int startOffset, int endOffset) {
        int bytes = Math.max(0, endOffset - startOffset);
        int estimatedPackets = Math.max(1, bytes / 80);
        return startMillis + (long) estimatedPackets * opusFrameMillis;
    }

    @SuppressWarnings("unused")
    private static String rotationKey(long unixMillis) {
        return WEEKLY_ROTATION_FORMAT.format(Instant.ofEpochMilli(unixMillis));
    }

    private record UdbRecord(long startMillis, int opusOffset) {}

    private record Utterance(LogicalChannel channel, Path opusPath, long startMillis, long endMillis,
                             int startOffset, int endOffset) {}
}
