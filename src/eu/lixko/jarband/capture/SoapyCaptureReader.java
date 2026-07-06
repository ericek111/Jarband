package eu.lixko.jarband.capture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import eu.lixko.jsoapy.soapy.Converters;
import eu.lixko.jsoapy.soapy.Errors_h;
import eu.lixko.jsoapy.soapy.SoapySDRDevice;
import eu.lixko.jsoapy.soapy.SoapySDRDeviceDirection;
import eu.lixko.jsoapy.soapy.SoapySDRStream;
import eu.lixko.jsoapy.soapy.StreamFormat;
import eu.lixko.jsoapy.util.NativeUtils;

public final class SoapyCaptureReader implements Runnable {
    private static final int OUTPUT_BLOCK_SAMPLES = 65_280;

    private final Settings settings;
    private final BlockingQueue<NativeSampleBlock> output;
    private volatile boolean running = true;
    private long droppedBlocks;
    private float[] pendingSamples = new float[OUTPUT_BLOCK_SAMPLES * 2];
    private int pendingSampleCount;
    private long pendingFirstSampleIndex;
    private long pendingCapturedNanos;

    public SoapyCaptureReader(Settings settings, BlockingQueue<NativeSampleBlock> output) {
        this.settings = settings;
        this.output = output;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        NativeUtils.loadLibrary();
        SoapySDRDevice device = SoapySDRDevice.makeStrArgs(settings.args());
        device.setSampleRate(SoapySDRDeviceDirection.RX, 0, settings.sampleRateHz());
        device.setFrequency(SoapySDRDeviceDirection.RX, 0, settings.centerFrequencyHz());
        for (var gain : settings.gains().entrySet()) {
            device.setGain(SoapySDRDeviceDirection.RX, 0, gain.getKey(), gain.getValue());
        }

        SoapySDRStream stream = device.setupStream(SoapySDRDeviceDirection.RX, StreamFormat.CS16, null, null);
        var converter = Converters.getFunction(stream.getNativeFormat(0).format(), StreamFormat.CF32);
        stream.activateStream();
        long firstSample = 0;
        pendingFirstSampleIndex = firstSample;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment converted = MemorySegment.NULL;
            int convertedCapacitySamples = 0;
            while (running) {
                int read = stream.acquireReadBuffer(1_000_000);
                if (read <= 0) {
                    if (read < 0) {
                        System.out.println("SDR read error: " + Errors_h.fromCode(read).name());
                    }
                    continue;
                }
                try {
                    if (read > convertedCapacitySamples) {
                        converted = arena.allocate((long) read * 2L * Float.BYTES,
                                ValueLayout.JAVA_FLOAT.byteAlignment());
                        convertedCapacitySamples = read;
                    }
                    converter.invokeLongs(stream.getDirectBuffer(0).address(), converted.address(), read, 1.0);
                    appendConverted(converted, read, firstSample, System.nanoTime());
                    firstSample += read;
                } finally {
                    stream.releaseReadBuffer();
                }
            }
        } finally {
            if (pendingSampleCount > 0) {
                emitPendingBlock(true);
            }
            output.offer(NativeSampleBlock.POISON);
        }
    }

    private void appendConverted(MemorySegment samples, int sampleCount, long firstSampleIndex, long capturedNanos) {
        int consumed = 0;
        while (consumed < sampleCount) {
            if (pendingSampleCount == 0) {
                pendingFirstSampleIndex = firstSampleIndex + consumed;
            }
            int copied = Math.min(OUTPUT_BLOCK_SAMPLES - pendingSampleCount, sampleCount - consumed);
            MemorySegment target = MemorySegment.ofArray(pendingSamples);
            target.asSlice((long) pendingSampleCount * 2L * Float.BYTES,
                    (long) copied * 2L * Float.BYTES)
                    .copyFrom(samples.asSlice((long) consumed * 2L * Float.BYTES,
                            (long) copied * 2L * Float.BYTES));
            pendingSampleCount += copied;
            consumed += copied;
            pendingCapturedNanos = capturedNanos;
            if (pendingSampleCount == OUTPUT_BLOCK_SAMPLES) {
                emitPendingBlock(false);
            }
        }
    }

    private void emitPendingBlock(boolean partial) {
        float[] blockSamples;
        if (!partial && pendingSampleCount == OUTPUT_BLOCK_SAMPLES) {
            blockSamples = pendingSamples;
            pendingSamples = new float[OUTPUT_BLOCK_SAMPLES * 2];
        } else {
            blockSamples = java.util.Arrays.copyOf(pendingSamples, pendingSampleCount * 2);
        }
        NativeSampleBlock block = new NativeSampleBlock(
                MemorySegment.ofArray(blockSamples),
                pendingSampleCount,
                pendingFirstSampleIndex,
                pendingCapturedNanos);
        if (!output.offer(block)) {
            output.poll();
            droppedBlocks++;
            if (!output.offer(block)) {
                droppedBlocks++;
            }
        }
        if (droppedBlocks > 0 && (droppedBlocks & 0x3f) == 0) {
            System.out.printf("Capture dropped %,d stale aggregated blocks because processing is behind%n", droppedBlocks);
        }
        pendingSampleCount = 0;
    }

    public record Settings(String args, double sampleRateHz, double centerFrequencyHz, Map<String, Double> gains) {}
}
