package eu.lixko.jarband.recording;

@FunctionalInterface
public interface OpusFrameSink {
    void accept(EncodedOpusFrame frame);

    default void closeUtterance(int channelId, long unixMillis, UtteranceClosed utterance) {
    }

    record UtteranceClosed(int channelId, String channelName, long startMillis, long endMillis,
                           long durationMillis, float averageSnrDb, String opusFileName,
                           int startOffset, int endOffset, int sampleRateHz, int frameMillis) {
    }
}
