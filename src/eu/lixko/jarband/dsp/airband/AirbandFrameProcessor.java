package eu.lixko.jarband.dsp.airband;

import java.time.Clock;
import java.util.Arrays;

import eu.lixko.jarband.dsp.channelizer.ChannelPlan;
import eu.lixko.jarband.dsp.channelizer.ChannelizedFrame;
import eu.lixko.jarband.dsp.channelizer.ChannelizedFrameRing;
import eu.lixko.jarband.dsp.channelizer.LogicalChannel;

public final class AirbandFrameProcessor {
    // Do not open the recorder for single-frame spikes. A real AM voice
    // transmission has a carrier that stays up; short RF bursts are discarded.
    private static final int OPEN_CONFIRM_MILLIS = 30;
    // We intentionally delay writing by this much so the recorder can see the
    // carrier drop and trim the tail instead of committing post-squelch noise.
    private static final int CLOSE_LOOKAHEAD_MILLIS = 100;
    private static final float POWER_ALPHA = 0.005f;

    private final ChannelPlan plan;
    private final ChannelStatus status;
    private final float squelchOpenDb;
    private final float squelchCloseDb;
    private final ChannelizedFrameRing preroll;
    private final int prerollFrames;
    private final int openConfirmFrames;
    private final int closeLookaheadFrames;
    private final ChannelRuntimeState channels;
    private final ChannelAudioPipeline audio;
    private final Clock clock;

    public AirbandFrameProcessor(ChannelPlan plan, ChannelizedFrameRing preroll, int prerollFrames,
                                 double channelSampleRate, int audioSampleRate,
                                 float squelchOpenDb, float squelchCloseDb, Clock clock) {
        this.plan = plan;
        this.status = new ChannelStatus(plan.size());
        this.squelchOpenDb = squelchOpenDb;
        this.squelchCloseDb = squelchCloseDb;
        this.preroll = preroll;
        this.prerollFrames = prerollFrames;
        this.openConfirmFrames = Math.max(1, (int) Math.ceil(channelSampleRate * OPEN_CONFIRM_MILLIS / 1000.0));
        this.closeLookaheadFrames = Math.max(1, (int) Math.ceil(channelSampleRate * CLOSE_LOOKAHEAD_MILLIS / 1000.0));
        this.channels = new ChannelRuntimeState(plan.size());
        this.audio = new ChannelAudioPipeline(plan.size(), channelSampleRate, audioSampleRate);
        this.clock = clock;
    }

    public ChannelStatus status() {
        return status;
    }

    public int process(ChannelizedFrame frame, float[] activeAudioScratch, AudioSink sink) {
        long now = clock.millis();
        long sequence = frame.sequence();
        // First pass extracts one complex sample per logical channel and measures
        // power for the entire band. The median becomes our per-frame noise floor.
        for (LogicalChannel channel : plan.channels()) {
            int channelId = channel.id();
            float i = 0.0f;
            float q = 0.0f;
            int[] bins = channel.pfbBins();
            for (int bin : bins) {
                i += frame.i(bin);
                q += frame.q(bin);
            }
            channels.i[channelId] = i / bins.length;
            channels.q[channelId] = q / bins.length;
            channels.power[channelId] = measurePower(channelId, channels.i[channelId], channels.q[channelId]);
        }

        int active = 0;
        float bandNoiseFloorDb = median(channels.power, channels.medianScratch);
        for (LogicalChannel channel : plan.channels()) {
            int channelId = channel.id();
            status.noiseFloor[channelId] = bandNoiseFloorDb;
            if (updateGate(channel, sequence, frame.capturedNanos(), now, bandNoiseFloorDb, activeAudioScratch, sink)) {
                active++;
            }
        }
        return active;
    }

    private boolean updateGate(LogicalChannel channel, long sequence, long currentNanos, long nowMillis,
                               float bandNoiseFloorDb, float[] activeAudioScratch,
                               AudioSink sink) {
        int channelId = channel.id();
        float marginDb = channels.power[channelId] - bandNoiseFloorDb;
        boolean aboveOpen = marginDb >= squelchOpenDb;
        boolean aboveClose = marginDb >= squelchCloseDb;

        if (!channels.gateOpen[channelId]) {
            status.squelchState[channelId] = ChannelStatus.CLOSED;
            if (aboveOpen) {
                if (channels.openRunFrames[channelId] == 0) {
                    channels.candidateStartSequence[channelId] = sequence;
                }
                channels.openRunFrames[channelId]++;
                if (channels.openRunFrames[channelId] >= openConfirmFrames) {
                    // The signal survived the confirmation window. Start from
                    // the original carrier rise, with only a small pre-roll to
                    // cover detector/PFB latency instead of saving idle noise.
                    openGate(channelId, sequence, nowMillis);
                }
            } else {
                channels.openRunFrames[channelId] = 0;
                channels.candidateStartSequence[channelId] = -1L;
            }
            return false;
        }

        status.squelchState[channelId] = ChannelStatus.OPEN;
        if (aboveClose) {
            channels.lastSignalSequence[channelId] = sequence;
        }

        // Only frames older than the lookahead window are safe to write. Newer
        // frames might turn out to be tail noise if the carrier does not return.
        long safeEndExclusive = Math.min(channels.lastSignalSequence[channelId] + 1,
                sequence - closeLookaheadFrames + 1);
        emitRange(channel, channels.nextEmitSequence[channelId], safeEndExclusive, currentNanos, nowMillis, sink);

        if (!aboveClose && sequence - channels.lastSignalSequence[channelId] >= closeLookaheadFrames) {
            // The carrier has been gone for the whole lookahead window. Flush up
            // to the last carrier-positive frame and discard everything after it.
            emitRange(channel, channels.nextEmitSequence[channelId], channels.lastSignalSequence[channelId] + 1,
                    currentNanos, nowMillis, sink);
            closeGate(channelId, nowMillis, activeAudioScratch, sink);
            return false;
        }
        return true;
    }

    private void openGate(int channelId, long sequence, long nowMillis) {
        channels.gateOpen[channelId] = true;
        status.squelchState[channelId] = ChannelStatus.OPEN;
        channels.nextEmitSequence[channelId] = Math.max(channels.candidateStartSequence[channelId] - prerollFrames,
                channels.emittedThroughSequence[channelId] + 1);
        channels.lastSignalSequence[channelId] = sequence;
        channels.openRunFrames[channelId] = 0;
    }

    private void closeGate(int channelId, long nowMillis, float[] activeAudioScratch, AudioSink sink) {
        audio.flush(channelId, nowMillis, sink);
        audio.reset(channelId);
        channels.gateOpen[channelId] = false;
        status.squelchState[channelId] = ChannelStatus.CLOSED;
        channels.openRunFrames[channelId] = 0;
        channels.candidateStartSequence[channelId] = -1L;
        channels.lastSignalSequence[channelId] = -1L;
        channels.nextEmitSequence[channelId] = -1L;
        sink.accept(channelId, nowMillis, activeAudioScratch, 0);
    }

    private void emitRange(LogicalChannel channel, long firstSequence, long lastSequenceExclusive,
                           long currentNanos, long nowMillis, AudioSink sink) {
        int channelId = channel.id();
        if (firstSequence < 0 || lastSequenceExclusive <= firstSequence) {
            return;
        }
        long first = Math.max(firstSequence, channels.emittedThroughSequence[channelId] + 1);
        if (lastSequenceExclusive <= first) {
            return;
        }
        // Replay from the shared channelized-spectrum ring. We do not store audio
        // in the ring; demodulation happens only after a range is proven useful.
        preroll.replay(channel, first, lastSequenceExclusive, (i, q, capturedNanos) -> {
            long sampleMillis = nowMillis - Math.max(0L, currentNanos - capturedNanos) / 1_000_000L;
            audio.acceptIq(channelId, sampleMillis, i, q, sink);
        });
        channels.emittedThroughSequence[channelId] = lastSequenceExclusive - 1;
        channels.nextEmitSequence[channelId] = lastSequenceExclusive;
    }

    private float measurePower(int channel, float i, float q) {
        float linear = i * i + q * q + 1.0e-20f;
        float instantDb = 10.0f * (float) Math.log10(linear);
        if (!Float.isFinite(status.power[channel])) {
            status.power[channel] = instantDb;
        } else {
            status.power[channel] += POWER_ALPHA * (instantDb - status.power[channel]);
        }
        return instantDb;
    }

    @FunctionalInterface
    public interface AudioSink {
        void accept(int channelId, long unixMillis, float[] audio, int length);
    }

    public static final class ChannelStatus {
        public static final byte CLOSED = 0;
        public static final byte OPEN = 1;

        public final float[] power;
        public final float[] noiseFloor;
        public final byte[] squelchState;

        private ChannelStatus(int channels) {
            this.power = new float[channels];
            this.noiseFloor = new float[channels];
            this.squelchState = new byte[channels];
            Arrays.fill(power, Float.NaN);
            Arrays.fill(noiseFloor, Float.NaN);
        }
    }

    private static final class ChannelRuntimeState {
        final boolean[] gateOpen;
        final int[] openRunFrames;
        // First frame in the current above-threshold run, before the gate is proven.
        final long[] candidateStartSequence;
        // Last frame that still looked like real carrier, not just hang/noise.
        final long[] lastSignalSequence;
        // First frame not yet written for each channel. This lets the delayed gate
        // emit ranges exactly once even when the squelch state changes.
        final long[] nextEmitSequence;
        // Absolute guard against replaying the same channelized frame after flaps.
        final long[] emittedThroughSequence;
        final float[] i;
        final float[] q;
        final float[] power;
        final float[] medianScratch;

        ChannelRuntimeState(int channelCount) {
            gateOpen = new boolean[channelCount];
            openRunFrames = new int[channelCount];
            candidateStartSequence = new long[channelCount];
            lastSignalSequence = new long[channelCount];
            nextEmitSequence = new long[channelCount];
            emittedThroughSequence = new long[channelCount];
            i = new float[channelCount];
            q = new float[channelCount];
            power = new float[channelCount];
            medianScratch = new float[channelCount];
            Arrays.fill(candidateStartSequence, -1L);
            Arrays.fill(lastSignalSequence, -1L);
            Arrays.fill(nextEmitSequence, -1L);
            Arrays.fill(emittedThroughSequence, -1L);
        }
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
}
