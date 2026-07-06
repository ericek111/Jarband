package eu.lixko.jarband.recording;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import eu.lixko.jarband.dsp.channelizer.ChannelPlan;

public final class RecorderBank implements AutoCloseable {
    private final List<ChannelRecorder> recorders;

    public RecorderBank(Path outputDir, ChannelPlan plan, boolean weeklyRotation,
                        int opusSampleRate, int opusBitrate, int opusFrameMillis, int opusComplexity) {
        var list = new ArrayList<ChannelRecorder>(plan.size());
        for (var channel : plan.channels()) {
            list.add(new ChannelRecorder(outputDir, channel, weeklyRotation,
                    opusSampleRate, opusBitrate, opusFrameMillis, opusComplexity));
        }
        this.recorders = List.copyOf(list);
    }

    public void accept(int channelId, long unixMillis, float[] audio, int length) {
        try {
            recorders.get(channelId).accept(unixMillis, audio, length, length > 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing channel " + channelId, e);
        }
    }

    @Override
    public void close() throws IOException {
        IOException thrown = null;
        for (ChannelRecorder recorder : recorders) {
            try {
                recorder.close();
            } catch (IOException e) {
                if (thrown == null) thrown = e;
                else thrown.addSuppressed(e);
            }
        }
        if (thrown != null) throw thrown;
    }
}
