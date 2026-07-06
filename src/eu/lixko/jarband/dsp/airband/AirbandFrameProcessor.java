package eu.lixko.jarband.dsp.airband;

import java.time.Clock;

import eu.lixko.jarband.dsp.channelizer.ChannelPlan;
import eu.lixko.jarband.dsp.channelizer.ChannelizedFrame;
import eu.lixko.jarband.dsp.channelizer.ChannelizedFrameRing;
import eu.lixko.jarband.dsp.channelizer.LogicalChannel;

public final class AirbandFrameProcessor {
    private final ChannelPlan plan;
    private final ChannelStateArrays state;
    private final PowerSquelch squelch;
    private final ChannelizedFrameRing preroll;
    private final int prerollFrames;
    private final int utteranceMergeMillis;
    private final boolean[] recordingOpen;
    private final long[] pendingCloseMillis;
    private final float[] channelI;
    private final float[] channelQ;
    private final float[] channelPower;
    private final float[] medianScratch;
    private final AirbandAmDemodulator[] demods;
    private final Clock clock;

    public AirbandFrameProcessor(ChannelPlan plan, ChannelStateArrays state, PowerSquelch squelch,
                                 ChannelizedFrameRing preroll, int prerollFrames,
                                 int utteranceMergeMillis, double channelSampleRate, Clock clock) {
        this.plan = plan;
        this.state = state;
        this.squelch = squelch;
        this.preroll = preroll;
        this.prerollFrames = prerollFrames;
        this.utteranceMergeMillis = utteranceMergeMillis;
        this.recordingOpen = new boolean[plan.size()];
        this.pendingCloseMillis = new long[plan.size()];
        this.channelI = new float[plan.size()];
        this.channelQ = new float[plan.size()];
        this.channelPower = new float[plan.size()];
        this.medianScratch = new float[plan.size()];
        this.demods = new AirbandAmDemodulator[plan.size()];
        for (int channel = 0; channel < demods.length; channel++) {
            demods[channel] = new AirbandAmDemodulator(channelSampleRate);
        }
        this.clock = clock;
    }

    public int process(ChannelizedFrame frame, float[] activeAudioScratch, ActiveAudioSink sink) {
        long now = clock.millis();
        long sequence = preroll.append(frame);
        int active = 0;
        for (LogicalChannel channel : plan.channels()) {
            int channelId = channel.id();
            float i = 0.0f;
            float q = 0.0f;
            int[] bins = channel.pfbBins();
            for (int bin : bins) {
                i += frame.i(bin);
                q += frame.q(bin);
            }
            i /= bins.length;
            q /= bins.length;
            channelI[channelId] = i;
            channelQ[channelId] = q;
            channelPower[channelId] = squelch.measure(channelId, i, q);
        }

        float bandNoiseFloorDb = median(channelPower, medianScratch);
        for (LogicalChannel channel : plan.channels()) {
            int channelId = channel.id();
            boolean isOpen = squelch.update(channelId, channelPower[channelId], bandNoiseFloorDb, now);
            if (isOpen) {
                if (!recordingOpen[channelId]) {
                    recordingOpen[channelId] = true;
                    replayPreroll(channel, sequence, frame.capturedNanos(), now, activeAudioScratch, sink);
                }
                pendingCloseMillis[channelId] = 0;
                activeAudioScratch[0] = demods[channelId].demodulate(channelI[channelId], channelQ[channelId]);
                sink.accept(channelId, now, activeAudioScratch, 1);
                active++;
            } else {
                maybeCloseRecording(channelId, now, activeAudioScratch, sink);
            }
        }
        return active;
    }

    private static float median(float[] values, float[] scratch) {
        int count = 0;
        for (float value : values) {
            if (Float.isFinite(value)) {
                scratch[count++] = value;
            }
        }
        if (count == 0) {
            return Float.NaN;
        }
        return select(scratch, 0, count - 1, count / 2);
    }

    private static float select(float[] values, int left, int right, int k) {
        while (left < right) {
            float pivot = values[(left + right) >>> 1];
            int i = left;
            int j = right;
            while (i <= j) {
                while (values[i] < pivot) i++;
                while (values[j] > pivot) j--;
                if (i <= j) {
                    float tmp = values[i];
                    values[i++] = values[j];
                    values[j--] = tmp;
                }
            }
            if (k <= j) {
                right = j;
            } else if (k >= i) {
                left = i;
            } else {
                return values[k];
            }
        }
        return values[left];
    }

    private void maybeCloseRecording(int channelId, long nowMillis, float[] activeAudioScratch, ActiveAudioSink sink) {
        if (!recordingOpen[channelId]) {
            return;
        }
        if (pendingCloseMillis[channelId] == 0) {
            pendingCloseMillis[channelId] = nowMillis;
            return;
        }
        if (nowMillis - pendingCloseMillis[channelId] >= utteranceMergeMillis) {
            recordingOpen[channelId] = false;
            pendingCloseMillis[channelId] = 0;
            sink.accept(channelId, nowMillis, activeAudioScratch, 0);
        }
    }

    private void replayPreroll(LogicalChannel channel, long currentSequence, long currentNanos, long nowMillis,
                               float[] activeAudioScratch, ActiveAudioSink sink) {
        long firstSequence = currentSequence - prerollFrames;
        preroll.replay(channel, firstSequence, currentSequence, (i, q, capturedNanos) -> {
            long sampleMillis = nowMillis - Math.max(0L, currentNanos - capturedNanos) / 1_000_000L;
            activeAudioScratch[0] = demods[channel.id()].demodulate(i, q);
            sink.accept(channel.id(), sampleMillis, activeAudioScratch, 1);
        });
    }

    @FunctionalInterface
    public interface ActiveAudioSink {
        void accept(int channelId, long unixMillis, float[] audio, int length);
    }
}
