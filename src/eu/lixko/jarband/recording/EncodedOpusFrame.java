package eu.lixko.jarband.recording;

import java.util.Arrays;

public record EncodedOpusFrame(int channelId, String channelName, long unixMillis,
                               int durationMillis, int sampleRateHz, long sequence,
                               byte[] packet) {
    public EncodedOpusFrame {
        packet = Arrays.copyOf(packet, packet.length);
    }

    @Override
    public byte[] packet() {
        return packet;
    }
}
