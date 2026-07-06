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

    private final ChannelPlan plan;
    private final ChannelStateArrays state;
    private final PowerSquelch squelch;
    private final ChannelizedFrameRing preroll;
    private final int prerollFrames;
    private final int openConfirmFrames;
    private final int closeLookaheadFrames;
    private final boolean[] gateOpen;
    private final int[] openRunFrames;
    // First frame in the current above-threshold run, before the gate is proven.
    private final long[] candidateStartSequence;
    // Last frame that still looked like real carrier, not just hang/noise.
    private final long[] lastSignalSequence;
    // First frame not yet written for each channel. This lets the delayed gate
    // emit ranges exactly once even when the squelch state changes.
    private final long[] nextEmitSequence;
    // Absolute guard against replaying the same channelized frame after flaps.
    private final long[] emittedThroughSequence;
    private final float[] channelI;
    private final float[] channelQ;
    private final float[] channelPower;
    private final float[] medianScratch;
    private final ChannelAudioPipeline audio;
    private final Clock clock;

    public AirbandFrameProcessor(ChannelPlan plan, ChannelStateArrays state, PowerSquelch squelch,
                                 ChannelizedFrameRing preroll, int prerollFrames,
                                 double channelSampleRate, int audioSampleRate, Clock clock) {
        this.plan = plan;
        this.state = state;
        this.squelch = squelch;
        this.preroll = preroll;
        this.prerollFrames = prerollFrames;
        this.openConfirmFrames = Math.max(1, (int) Math.ceil(channelSampleRate * OPEN_CONFIRM_MILLIS / 1000.0));
        this.closeLookaheadFrames = Math.max(1, (int) Math.ceil(channelSampleRate * CLOSE_LOOKAHEAD_MILLIS / 1000.0));
        this.gateOpen = new boolean[plan.size()];
        this.openRunFrames = new int[plan.size()];
        this.candidateStartSequence = new long[plan.size()];
        this.lastSignalSequence = new long[plan.size()];
        this.nextEmitSequence = new long[plan.size()];
        this.emittedThroughSequence = new long[plan.size()];
        this.channelI = new float[plan.size()];
        this.channelQ = new float[plan.size()];
        this.channelPower = new float[plan.size()];
        this.medianScratch = new float[plan.size()];
        this.audio = new ChannelAudioPipeline(plan.size(), channelSampleRate, audioSampleRate);
        Arrays.fill(candidateStartSequence, -1L);
        Arrays.fill(lastSignalSequence, -1L);
        Arrays.fill(nextEmitSequence, -1L);
        Arrays.fill(emittedThroughSequence, -1L);
        this.clock = clock;
    }

    public int process(ChannelizedFrame frame, float[] activeAudioScratch, ChannelAudioPipeline.AudioSink sink) {
        long now = clock.millis();
        long sequence = preroll.append(frame);
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
            channelI[channelId] = i / bins.length;
            channelQ[channelId] = q / bins.length;
            channelPower[channelId] = squelch.measure(channelId, channelI[channelId], channelQ[channelId]);
        }

        int active = 0;
        float bandNoiseFloorDb = median(channelPower, medianScratch);
        for (LogicalChannel channel : plan.channels()) {
            int channelId = channel.id();
            state.noiseFloor[channelId] = bandNoiseFloorDb;
            state.noiseSamples[channelId]++;
            if (updateGate(channel, sequence, frame.capturedNanos(), now, bandNoiseFloorDb, activeAudioScratch, sink)) {
                active++;
            }
        }
        return active;
    }

    private boolean updateGate(LogicalChannel channel, long sequence, long currentNanos, long nowMillis,
                               float bandNoiseFloorDb, float[] activeAudioScratch,
                               ChannelAudioPipeline.AudioSink sink) {
        int channelId = channel.id();
        boolean aboveOpen = squelch.aboveOpen(channelPower[channelId], bandNoiseFloorDb);
        boolean aboveClose = squelch.aboveClose(channelPower[channelId], bandNoiseFloorDb);

        if (!gateOpen[channelId]) {
            state.squelchState[channelId] = ChannelStateArrays.CLOSED;
            if (aboveOpen) {
                if (openRunFrames[channelId] == 0) {
                    candidateStartSequence[channelId] = sequence;
                }
                openRunFrames[channelId]++;
                if (openRunFrames[channelId] >= openConfirmFrames) {
                    // The signal survived the confirmation window: open from the
                    // original rise edge, plus configured pre-roll.
                    openGate(channelId, sequence, nowMillis);
                }
            } else {
                openRunFrames[channelId] = 0;
                candidateStartSequence[channelId] = -1L;
            }
            return false;
        }

        state.squelchState[channelId] = ChannelStateArrays.OPEN;
        if (aboveClose) {
            lastSignalSequence[channelId] = sequence;
            state.lastOpenMillis[channelId] = nowMillis;
        }

        // Only frames older than the lookahead window are safe to write. Newer
        // frames might turn out to be tail noise if the carrier does not return.
        long safeEndExclusive = Math.min(lastSignalSequence[channelId] + 1,
                sequence - closeLookaheadFrames + 1);
        emitRange(channel, nextEmitSequence[channelId], safeEndExclusive, currentNanos, nowMillis, sink);

        if (!aboveClose && sequence - lastSignalSequence[channelId] >= closeLookaheadFrames) {
            // The carrier has been gone for the whole lookahead window. Flush up
            // to the last carrier-positive frame and discard everything after it.
            emitRange(channel, nextEmitSequence[channelId], lastSignalSequence[channelId] + 1,
                    currentNanos, nowMillis, sink);
            closeGate(channelId, nowMillis, activeAudioScratch, sink);
            return false;
        }
        return true;
    }

    private void openGate(int channelId, long sequence, long nowMillis) {
        gateOpen[channelId] = true;
        state.squelchState[channelId] = ChannelStateArrays.OPEN;
        state.utteranceStartMillis[channelId] = nowMillis;
        state.lastOpenMillis[channelId] = nowMillis;
        nextEmitSequence[channelId] = Math.max(candidateStartSequence[channelId] - prerollFrames,
                emittedThroughSequence[channelId] + 1);
        lastSignalSequence[channelId] = sequence;
        openRunFrames[channelId] = 0;
    }

    private void closeGate(int channelId, long nowMillis, float[] activeAudioScratch,
                           ChannelAudioPipeline.AudioSink sink) {
        audio.flush(channelId, nowMillis, sink);
        audio.reset(channelId);
        gateOpen[channelId] = false;
        state.squelchState[channelId] = ChannelStateArrays.CLOSED;
        openRunFrames[channelId] = 0;
        candidateStartSequence[channelId] = -1L;
        lastSignalSequence[channelId] = -1L;
        nextEmitSequence[channelId] = -1L;
        sink.accept(channelId, nowMillis, activeAudioScratch, 0);
    }

    private void emitRange(LogicalChannel channel, long firstSequence, long lastSequenceExclusive,
                           long currentNanos, long nowMillis, ChannelAudioPipeline.AudioSink sink) {
        int channelId = channel.id();
        if (firstSequence < 0 || lastSequenceExclusive <= firstSequence) {
            return;
        }
        long first = Math.max(firstSequence, emittedThroughSequence[channelId] + 1);
        if (lastSequenceExclusive <= first) {
            return;
        }
        // Replay from the shared channelized-spectrum ring. We do not store audio
        // in the ring; demodulation happens only after a range is proven useful.
        preroll.replay(channel, first, lastSequenceExclusive, (i, q, capturedNanos) -> {
            long sampleMillis = nowMillis - Math.max(0L, currentNanos - capturedNanos) / 1_000_000L;
            audio.acceptIq(channelId, sampleMillis, i, q, sink);
        });
        emittedThroughSequence[channelId] = lastSequenceExclusive - 1;
        nextEmitSequence[channelId] = lastSequenceExclusive;
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
