package eu.lixko.jarband.recording;

@FunctionalInterface
public interface OpusFrameSink {
    void accept(EncodedOpusFrame frame);

    default void closeUtterance(int channelId, long unixMillis, UtteranceClosed utterance) {
    }

    default void recordingBytes(RecordingBytes bytes) {
    }

    record UtteranceClosed(int channelId, String channelName, long startMillis, long endMillis,
                           long durationMillis, float averageSnrDb, String opusFileName,
                           int startOffset, int endOffset, int sampleRateHz, int frameMillis) {
    }

    record RecordingBytes(int channelId, String channelName, String fileName, int offset, byte[] bytes) {
        public RecordingBytes {
            bytes = java.util.Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return bytes;
        }
    }
}
