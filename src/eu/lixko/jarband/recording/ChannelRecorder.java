package eu.lixko.jarband.recording;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import eu.lixko.jarband.dsp.channelizer.LogicalChannel;

public final class ChannelRecorder implements AutoCloseable {
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
    private int frameFill;
    private OggOpusFile opusFile;
    private UtteranceDbWriter dbFile;
    private String rotationKey = "";
    private boolean utteranceOpen;

    public ChannelRecorder(Path outputDir, LogicalChannel channel, boolean weeklyRotation,
                           int opusSampleRate, int bitrate, int frameMillis, int complexity) {
        this.outputDir = outputDir;
        this.channel = channel;
        this.weeklyRotation = weeklyRotation;
        this.opusSampleRate = opusSampleRate;
        this.opusFrameMillis = frameMillis;
        this.encoder = new OpusFloatEncoder(opusSampleRate, bitrate, frameMillis, complexity);
        this.frameBuffer = new float[encoder.frameSamples()];
    }

    public synchronized void accept(long unixMillis, float[] audio, int length, boolean open) throws IOException {
        rotateIfNeeded(unixMillis);
        if (open && !utteranceOpen) {
            dbFile.append(unixMillis, opusFile.offsetForNextPacket());
            utteranceOpen = true;
        } else if (!open) {
            utteranceOpen = false;
            // Keep utterances independent and avoid carrying a partial Opus
            // frame, often containing squelch-edge noise, into the next one.
            frameFill = 0;
        }
        if (!open) {
            return;
        }
        for (int i = 0; i < length; i++) {
            frameBuffer[frameFill++] = audio[i];
            if (frameFill == frameBuffer.length) {
                int bytes = encoder.encode(frameBuffer, packet);
                opusFile.writeAudioPacket(packet, bytes);
                frameFill = 0;
            }
        }
    }

    public synchronized void closeUtterance(long unixMillis) throws IOException {
        rotateIfNeeded(unixMillis);
        utteranceOpen = false;
        // Keep utterances independent and avoid carrying a partial Opus frame,
        // often containing squelch-edge noise, into the next one.
        frameFill = 0;
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
