package eu.lixko.jarband.recording;

@FunctionalInterface
public interface OpusFrameSink {
    void accept(EncodedOpusFrame frame);

    default void closeUtterance(int channelId, long unixMillis) {
    }
}
