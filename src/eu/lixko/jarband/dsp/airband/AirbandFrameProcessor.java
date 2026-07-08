package eu.lixko.jarband.dsp.airband;

import java.util.Arrays;

import eu.lixko.jarband.dsp.channelizer.ChannelPlan;
import eu.lixko.jarband.dsp.channelizer.ChannelizedFrame;
import eu.lixko.jarband.dsp.channelizer.ChannelizedFrameRing;
import eu.lixko.jarband.dsp.channelizer.LogicalChannel;

public final class AirbandFrameProcessor implements AutoCloseable {
    private static final float POWER_ALPHA = 0.005f;
    private static final int STATUS_DB_UPDATE_MASK = 0x7f;
    private static final int NOISE_FLOOR_UPDATE_MASK = 0x0f;
    private static final int SAMPLED_NOISE_STRIDE = 4;

    private final ChannelPlan plan;
    private final ChannelStatus status;
    private final float squelchOpenRatio;
    private final float squelchCloseRatio;
    private final ChannelizedFrameRing preroll;
    private final int prerollFrames;
    private final int openConfirmFrames;
    private final int closeLookaheadFrames;
    private final ChannelRuntimeState channels;
    private final ChannelAudioPipeline audio;
    private final long wallClockAnchorMillis;
    private final long nanoTimeAnchor;

    public AirbandFrameProcessor(ChannelPlan plan, ChannelizedFrameRing preroll, int prerollFrames,
                                 double channelSampleRate, int audioSampleRate,
                                 float squelchOpenDb, float squelchCloseDb,
                                 int openConfirmMillis, int closeLookaheadMillis) {
        this.plan = plan;
        this.status = new ChannelStatus(plan.size());
        this.squelchOpenRatio = dbToPowerRatio(squelchOpenDb);
        this.squelchCloseRatio = dbToPowerRatio(squelchCloseDb);
        this.preroll = preroll;
        this.prerollFrames = prerollFrames;
        this.openConfirmFrames = Math.max(1, (int) Math.ceil(channelSampleRate * openConfirmMillis / 1000.0));
        this.closeLookaheadFrames = Math.max(1, (int) Math.ceil(channelSampleRate * closeLookaheadMillis / 1000.0));
        this.channels = new ChannelRuntimeState(plan.size());
        this.audio = new ChannelAudioPipeline(plan.size(), channelSampleRate, audioSampleRate);
        this.wallClockAnchorMillis = System.currentTimeMillis();
        this.nanoTimeAnchor = System.nanoTime();
    }

    public ChannelStatus status() {
        return status;
    }

    @Override
    public void close() {
    }

    public int process(ChannelizedFrame frame, AudioSink sink) {
        long sequence = frame.sequence();
        // First pass extracts one complex sample per logical channel and measures
        // power for the entire band. Squelch still uses per-frame channel power,
        // but the band noise floor is intentionally updated at a lower rate.
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
            channels.power[channelId] = measurePower(channels.i[channelId], channels.q[channelId]);
        }

        int active = 0;
        float bandNoiseFloor = channels.bandNoiseFloor;
        if (!Float.isFinite(bandNoiseFloor) || (sequence & NOISE_FLOOR_UPDATE_MASK) == 0) {
            bandNoiseFloor = median(channels.power, channels.noiseScratch);
            channels.bandNoiseFloor = bandNoiseFloor;
        }
        boolean updateStatusDb = (sequence & STATUS_DB_UPDATE_MASK) == 0;
        for (LogicalChannel channel : plan.channels()) {
            int channelId = channel.id();
            if (updateStatusDb) {
                updateStatusPower(channelId, bandNoiseFloor);
            }
            if (updateGate(channel, sequence, frame.capturedNanos(), bandNoiseFloor, sink)) {
                active++;
            }
        }
        return active;
    }

    private boolean updateGate(LogicalChannel channel, long sequence, long currentNanos,
                               float bandNoiseFloor, AudioSink sink) {
        int channelId = channel.id();
        float thresholdBase = Math.max(bandNoiseFloor, 1.0e-20f);
        boolean aboveOpen = channels.power[channelId] >= thresholdBase * squelchOpenRatio;
        boolean aboveClose = channels.power[channelId] >= thresholdBase * squelchCloseRatio;

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
                    openGate(channelId, sequence);
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
        channels.snrSumDb[channelId] += powerToDb(channels.power[channelId]) - powerToDb(thresholdBase);
        channels.snrFrames[channelId]++;

        if (!aboveClose && sequence - channels.lastSignalSequence[channelId] >= closeLookaheadFrames) {
            // The carrier has been gone for the whole lookahead window. Emit up
            // to the last carrier-positive frame and discard only samples that
            // remained buffered after that trusted range.
            emitRange(channel, channels.nextEmitSequence[channelId], channels.lastSignalSequence[channelId] + 1,
                    sink);
            closeGate(channelId, unixMillisForNanos(currentNanos), sink);
            return false;
        }

        // Only frames older than the lookahead window are safe to write. Newer
        // frames might turn out to be tail noise if the carrier does not return.
        long safeEndExclusive = Math.min(channels.lastSignalSequence[channelId] + 1,
                sequence - closeLookaheadFrames + 1);
        emitRange(channel, channels.nextEmitSequence[channelId], safeEndExclusive, sink);
        return true;
    }

    private void openGate(int channelId, long sequence) {
        channels.gateOpen[channelId] = true;
        status.squelchState[channelId] = ChannelStatus.OPEN;
        channels.nextEmitSequence[channelId] = Math.max(channels.candidateStartSequence[channelId] - prerollFrames,
                channels.emittedThroughSequence[channelId] + 1);
        channels.lastSignalSequence[channelId] = sequence;
        channels.openRunFrames[channelId] = 0;
        channels.snrSumDb[channelId] = 0.0f;
        channels.snrFrames[channelId] = 0;
    }

    private void closeGate(int channelId, long unixMillis, AudioSink sink) {
        // Do not flush the final partial DSP batch here. At close time the
        // delayed gate has already emitted every frame it trusts; any samples
        // still buffered are exactly the edge material users hear as squelch
        // tail, so drop them.
        audio.reset(channelId);
        channels.gateOpen[channelId] = false;
        status.squelchState[channelId] = ChannelStatus.CLOSED;
        channels.openRunFrames[channelId] = 0;
        channels.candidateStartSequence[channelId] = -1L;
        channels.lastSignalSequence[channelId] = -1L;
        channels.nextEmitSequence[channelId] = -1L;
        float averageSnrDb = channels.snrFrames[channelId] > 0
                ? channels.snrSumDb[channelId] / channels.snrFrames[channelId]
                : Float.NaN;
        channels.snrSumDb[channelId] = 0.0f;
        channels.snrFrames[channelId] = 0;
        sink.closeUtterance(channelId, unixMillis, averageSnrDb);
    }

    private void emitRange(LogicalChannel channel, long firstSequence, long lastSequenceExclusive,
                           AudioSink sink) {
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
            long sampleMillis = unixMillisForNanos(capturedNanos);
            audio.acceptIq(channelId, sampleMillis, i, q, sink);
        });
        channels.emittedThroughSequence[channelId] = lastSequenceExclusive - 1;
        channels.nextEmitSequence[channelId] = lastSequenceExclusive;
    }

    private static float measurePower(float i, float q) {
        return i * i + q * q + 1.0e-20f;
    }

    private void updateStatusPower(int channel, float bandNoiseFloor) {
        float instantDb = powerToDb(channels.power[channel]);
        if (!Float.isFinite(status.power[channel])) {
            status.power[channel] = instantDb;
        } else {
            status.power[channel] += POWER_ALPHA * (instantDb - status.power[channel]);
        }
        status.noiseFloor[channel] = powerToDb(bandNoiseFloor);
    }

    private static float dbToPowerRatio(float db) {
        return (float) Math.pow(10.0, db / 10.0);
    }

    private static float powerToDb(float power) {
        return 10.0f * (float) Math.log10(Math.max(power, 1.0e-20f));
    }

    private long unixMillisForNanos(long capturedNanos) {
        return wallClockAnchorMillis + (capturedNanos - nanoTimeAnchor) / 1_000_000L;
    }

    public interface AudioSink {
        void audio(int channelId, long unixMillis, float[] audio, int length);

        void closeUtterance(int channelId, long unixMillis, float averageSnrDb);
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
        final float[] snrSumDb;
        final int[] snrFrames;
        final float[] noiseScratch;
        float bandNoiseFloor = Float.NaN;

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
            snrSumDb = new float[channelCount];
            snrFrames = new int[channelCount];
            noiseScratch = new float[channelCount];
            Arrays.fill(candidateStartSequence, -1L);
            Arrays.fill(lastSignalSequence, -1L);
            Arrays.fill(nextEmitSequence, -1L);
            Arrays.fill(emittedThroughSequence, -1L);
        }
    }

    private static float median(float[] values, float[] scratch) {
        // measurePower() always writes finite positive values, so avoid a
        // per-channel isFinite branch in this hot path.
        System.arraycopy(values, 0, scratch, 0, values.length);
        return select(scratch, 0, values.length - 1, values.length / 2);
    }

    @SuppressWarnings("unused")
    private static float sampledMedian(float[] values, float[] scratch, long sequence) {
        if (values.length == 0) {
            return 1.0e-20f;
        }
        // An exact median of every channel is robust but costs a full-array copy
        // plus quickselect. Sampling every fourth channel keeps the same
        // carrier-resistant behavior for sparse airband activity at lower cost.
        // Noise updates happen every 16 frames, so rotate the sampled phase using
        // higher sequence bits; the low four bits are zero on every update.
        int stride = values.length >= 512 ? SAMPLED_NOISE_STRIDE : 2;
        int offset = (int) ((sequence >>> 4) & (stride - 1));
        int count = 0;
        for (int i = offset; i < values.length; i += stride) {
            scratch[count++] = values[i];
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
