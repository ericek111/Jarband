package eu.lixko.jarband.recording;

import java.util.Arrays;

public record EncodedOpusFrame(int channelId, String channelName, long unixMillis,
                               int durationMillis, int sampleRateHz, long sequence,
                               byte[] packet, String streamKey) {
    public EncodedOpusFrame(int channelId, String channelName, long unixMillis,
                            int durationMillis, int sampleRateHz, long sequence,
                            byte[] packet) {
        this(channelId, channelName, unixMillis, durationMillis, sampleRateHz, sequence, packet, channelName);
    }

    public EncodedOpusFrame {
        packet = Arrays.copyOf(packet, packet.length);
        if (streamKey == null || streamKey.isBlank()) {
            streamKey = channelName;
        }
    }

    @Override
    public byte[] packet() {
        return packet;
    }

    public EncodedOpusFrame withStreamKey(String streamKey) {
        return new EncodedOpusFrame(channelId, channelName, unixMillis, durationMillis,
                sampleRateHz, sequence, packet, streamKey);
    }
}
