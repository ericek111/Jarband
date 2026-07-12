package eu.lixko.jarband.recording;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import eu.lixko.jarband.dsp.channelizer.LogicalChannel;

public final class ChannelRecorder implements AutoCloseable {
    private static final long MIN_STORED_UTTERANCE_MILLIS = 100;
    private static final DateTimeFormatter WEEKLY_ROTATION_FORMAT =
            DateTimeFormatter.ofPattern("YYYY-'w'ww", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Path outputDir;
    private final LogicalChannel channel;
    private final boolean weeklyRotation;
    private final OpusFloatEncoder encoder;
    private final float[] frameBuffer;
    private final byte[] packet = new byte[4096];
    private final int opusSampleRate;
    private final int opusFrameMillis;
    private final OpusFrameSink frameSink;
    private int frameFill;
    private long frameStartMillis;
    private long packetSequence;
    private OggOpusFile opusFile;
    private UtteranceDbWriter dbFile;
    private String rotationKey = "";
    private boolean utteranceOpen;
    private long utteranceStartMillis;
    private int utteranceStartOffset;
    private long utteranceDbOffset;

    public ChannelRecorder(Path outputDir, LogicalChannel channel, boolean weeklyRotation,
                           int opusSampleRate, int bitrate, int frameMillis, int complexity,
                           OpusFrameSink frameSink) {
        this.outputDir = outputDir;
        this.channel = channel;
        this.weeklyRotation = weeklyRotation;
        this.opusSampleRate = opusSampleRate;
        this.opusFrameMillis = frameMillis;
        this.frameSink = frameSink;
        this.encoder = new OpusFloatEncoder(opusSampleRate, bitrate, frameMillis, complexity);
        this.frameBuffer = new float[encoder.frameSamples()];
    }

    public synchronized void accept(long unixMillis, float[] audio, int length) throws IOException {
        rotateIfNeeded(unixMillis);
        if (!utteranceOpen) {
            utteranceStartMillis = unixMillis;
            utteranceStartOffset = opusFile.offsetForNextPacket();
            UtteranceDbWriter.WrittenRange dbRange = dbFile.appendOpen(utteranceStartMillis, utteranceStartOffset);
            utteranceDbOffset = dbRange.offset();
            publishDbRecordingBytes();
            utteranceOpen = true;
        }
        for (int i = 0; i < length; i++) {
            if (frameFill == 0) {
                frameStartMillis = unixMillis + Math.round(i * 1000.0 / opusSampleRate);
            }
            frameBuffer[frameFill++] = audio[i];
            if (frameFill == frameBuffer.length) {
                int bytes = encoder.encode(frameBuffer, packet);
                opusFile.writeAudioPacket(packet, bytes);
                publishRecordingBytes();
                publishFrame(bytes);
                frameFill = 0;
            }
        }
    }

    public synchronized OpusFrameSink.UtteranceClosed closeUtterance(long unixMillis, float averageSnrDb) throws IOException {
        if (opusFile == null || dbFile == null) {
            rotateIfNeeded(unixMillis);
        }
        OpusFrameSink.UtteranceClosed closed = null;
        if (utteranceOpen) {
            long durationMillis = Math.max(0, unixMillis - utteranceStartMillis);
            // Drop the unfinished encoder frame at the squelch edge, then flush
            // complete Opus packets so the live history event can carry a
            // playable byte range immediately.
            frameFill = 0;
            opusFile.flushPendingAudio();
            publishRecordingBytes();
            int utteranceEndOffset = opusFile.offsetForNextPacket();
            if (durationMillis < MIN_STORED_UTTERANCE_MILLIS || utteranceEndOffset <= utteranceStartOffset) {
                dbFile.truncateFrom(utteranceDbOffset);
                publishRecordingBytes(rotationKey + ".udb", Math.toIntExact(utteranceDbOffset), new byte[0]);
            } else {
                dbFile.update(utteranceDbOffset, utteranceStartMillis, utteranceStartOffset,
                        durationMillis, averageSnrDb);
                publishDbRecordingBytes();
                closed = new OpusFrameSink.UtteranceClosed(channel.id(), channel.name(),
                        utteranceStartMillis, unixMillis, durationMillis, averageSnrDb,
                        rotationKey + ".opus", utteranceStartOffset, utteranceEndOffset,
                        opusSampleRate, opusFrameMillis);
            }
        }
        utteranceOpen = false;
        // Keep utterances independent and avoid carrying a partial Opus frame,
        // often containing squelch-edge noise, into the next one.
        frameFill = 0;
        if (opusFile != null && closed == null) {
            opusFile.flushPendingAudio();
            publishRecordingBytes();
        }
        return closed;
    }

    private void publishFrame(int bytes) {
        if (frameSink != null) {
            frameSink.accept(new EncodedOpusFrame(channel.id(), channel.name(), frameStartMillis,
                    opusFrameMillis, opusSampleRate, packetSequence++,
                    java.util.Arrays.copyOf(packet, bytes)));
        }
    }

    private void publishRecordingBytes() {
        if (frameSink == null || opusFile == null) {
            return;
        }
        String opusFileName = rotationKey + ".opus";
        for (OggOpusFile.WrittenRange range : opusFile.drainWrittenRanges()) {
            publishRecordingBytes(opusFileName, range.offset(), range.bytes());
        }
    }

    private void publishDbRecordingBytes() {
        if (frameSink == null || dbFile == null) {
            return;
        }
        String dbFileName = rotationKey + ".udb";
        for (UtteranceDbWriter.WrittenRange range : dbFile.drainWrittenRanges()) {
            publishRecordingBytes(dbFileName, range.offset(), range.bytes());
        }
    }

    private void publishRecordingBytes(String fileName, int offset, byte[] bytes) {
        if (frameSink != null) {
            frameSink.recordingBytes(new OpusFrameSink.RecordingBytes(channel.id(), channel.name(),
                    fileName, offset, bytes));
        }
    }

    private void rotateIfNeeded(long unixMillis) throws IOException {
        String next = weeklyRotation ? weeklyRotationKey(unixMillis) : "current";
        if (next.equals(rotationKey)) {
            return;
        }
        closeFiles();
        rotationKey = next;
        Path channelDir = outputDir.resolve(channel.name());
        opusFile = new OggOpusFile(channelDir.resolve(rotationKey + ".opus"), opusSampleRate, opusFrameMillis);
        dbFile = new UtteranceDbWriter(channelDir.resolve(rotationKey + ".udb"));
        utteranceOpen = false;
        utteranceStartMillis = 0;
        utteranceStartOffset = 0;
        utteranceDbOffset = 0;
        frameFill = 0;
    }

    private static String weeklyRotationKey(long unixMillis) {
        return WEEKLY_ROTATION_FORMAT.format(Instant.ofEpochMilli(unixMillis));
    }

    private void closeFiles() throws IOException {
        if (opusFile != null) opusFile.close();
        if (dbFile != null) dbFile.close();
    }

    @Override
    public void close() throws IOException {
        closeFiles();
        encoder.close();
    }
}
