package eu.lixko.jarband.dsp.vdl2;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import eu.lixko.jarband.capture.NativeSampleBlock;

public final class Vdl2Processor implements AutoCloseable {
    private static final double VDL2_CHANNEL_GUARD_HZ = 15_000.0;

    public interface IqSink {
        void accept(MemorySegment samples, int sampleCount);
    }

    private final Vdl2WidebandResampler wideband;
    private final Vdl2SymbolSocket sink;
    private final List<Vdl2ChannelProcessor> channels;
    private final IqSink iqSink;
    private final WindowPlan windowPlan;

    public Vdl2Processor(double inputSampleRateHz, double centerFrequencyHz, List<Integer> frequenciesHz,
                         InetSocketAddress output, IqSink iqSink) throws IOException {
        this.windowPlan = WindowPlan.select(centerFrequencyHz, inputSampleRateHz, frequenciesHz);
        this.wideband = new Vdl2WidebandResampler(inputSampleRateHz, windowPlan.outputRateHz(),
                centerFrequencyHz, windowPlan.centerHz());
        this.sink = new Vdl2SymbolSocket(output);
        this.channels = new ArrayList<>(frequenciesHz.size());
        this.iqSink = iqSink;
        for (int frequencyHz : frequenciesHz) {
            channels.add(new Vdl2ChannelProcessor(frequencyHz, windowPlan.centerHz(),
                    windowPlan.outputRateHz(), sink));
        }
        System.out.print(windowPlan.describe());
    }

    public synchronized void process(NativeSampleBlock block) {
        Vdl2WidebandResampler.Result widebandBlock = wideband.process(block);
        if (iqSink != null) {
            iqSink.accept(widebandBlock.samples(), widebandBlock.sampleCount());
        }
        for (Vdl2ChannelProcessor channel : channels) {
            channel.process(widebandBlock.samples(), widebandBlock.sampleCount(), widebandBlock.blockStartNanos());
        }
    }

    public synchronized String statusAndReset() {
        Vdl2SymbolSocket.Counters counters = sink.countersAndReset();
        StringBuilder status = new StringBuilder(256);
        status.append(String.format(Locale.ROOT,
                "VDL2 out: %,d batches, %,d symbols, dropped %,d/%,d, reconnects %,d, failures %,d, %s",
                counters.batches(), counters.symbols(),
                counters.droppedBatches(), counters.droppedSymbols(),
                counters.reconnects(), counters.connectionFailures(),
                counters.connected() ? "connected" : "offline"));
        for (Vdl2ChannelProcessor channel : channels) {
            Vdl2Demodulator.Stats stats = channel.statusAndReset();
            if (stats.syncs() == 0 && stats.symbols() == 0
                    && !Float.isFinite(stats.bestSyncError())) {
                continue;
            }
            status.append(String.format(Locale.ROOT,
                    "%n  %.3f: sync %,d, sym %,d, SNR %.1f dB, searchSNR %.1f dB, ppm %.1f, "
                            + "syncErr last %.2f best %.2f, hdr %s",
                    channel.frequencyHz() / 1_000_000.0,
                    stats.syncs(),
                    stats.symbols(),
                    stats.snrDb(),
                    stats.searchSnrDb(),
                    stats.ppmError(),
                    stats.lastSyncError(),
                    stats.bestSyncError(),
                    stats.headerSymbols().isEmpty() ? "-" : stats.headerSymbols()));
        }
        return status.toString();
    }

    @Override
    public synchronized void close() throws Exception {
        Exception failure = null;
        for (Vdl2ChannelProcessor channel : channels) {
            try {
                channel.close();
            } catch (Exception e) {
                failure = e;
            }
        }
        try {
            wideband.close();
        } catch (Exception e) {
            failure = e;
        }
        try {
            sink.close();
        } catch (Exception e) {
            failure = e;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record WindowPlan(double centerHz, double sdrLowHz, double sdrHighHz,
                              double outputLowHz, double outputHighHz,
                              int outputRateHz, int channelDecimationFactor,
                              int configuredChannels, int coveredChannels,
                              List<Integer> cutOffFrequenciesHz) {
        static WindowPlan select(double sdrCenterHz, double sdrSampleRateHz, List<Integer> frequenciesHz) {
            double sdrLow = sdrCenterHz - sdrSampleRateHz / 2.0;
            double sdrHigh = sdrCenterHz + sdrSampleRateHz / 2.0;
            int channelDecimationFactor = channelDecimationFactor(sdrSampleRateHz, frequenciesHz);
            int outputRateHz = channelDecimationFactor * Vdl2Demodulator.SAMPLE_RATE;
            double halfOutput = outputRateHz / 2.0;
            double allowedLow = sdrLow + halfOutput;
            double allowedHigh = sdrHigh - halfOutput;
            if (allowedLow > allowedHigh) {
                allowedLow = allowedHigh = sdrCenterHz;
            }

            List<Double> candidates = new ArrayList<>();
            candidates.add((allowedLow + allowedHigh) / 2.0);
            if (!frequenciesHz.isEmpty()) {
                double min = frequenciesHz.stream().mapToDouble(Integer::doubleValue).min().orElse(sdrCenterHz);
                double max = frequenciesHz.stream().mapToDouble(Integer::doubleValue).max().orElse(sdrCenterHz);
                candidates.add(clamp((min + max) / 2.0, allowedLow, allowedHigh));
            }
            for (int frequencyHz : frequenciesHz) {
                candidates.add(clamp(frequencyHz, allowedLow, allowedHigh));
                candidates.add(clamp(frequencyHz - halfOutput, allowedLow, allowedHigh));
                candidates.add(clamp(frequencyHz + halfOutput, allowedLow, allowedHigh));
            }

            double bestCenter = candidates.getFirst();
            int bestCount = -1;
            double bestMaxOffset = Double.POSITIVE_INFINITY;
            for (double candidate : candidates) {
                int count = 0;
                double maxOffset = 0.0;
                for (int frequencyHz : frequenciesHz) {
                    if (fits(frequencyHz, candidate, sdrLow, sdrHigh, halfOutput)) {
                        count++;
                        maxOffset = Math.max(maxOffset, Math.abs(frequencyHz - candidate));
                    }
                }
                if (count > bestCount || (count == bestCount && maxOffset < bestMaxOffset)) {
                    bestCenter = candidate;
                    bestCount = count;
                    bestMaxOffset = maxOffset;
                }
            }

            double outputLow = bestCenter - halfOutput;
            double outputHigh = bestCenter + halfOutput;
            List<Integer> cutOff = new ArrayList<>();
            for (int frequencyHz : frequenciesHz) {
                if (!fitsWithGuard(frequencyHz, bestCenter, sdrLow, sdrHigh, halfOutput)) {
                    cutOff.add(frequencyHz);
                }
            }
            return new WindowPlan(bestCenter, sdrLow, sdrHigh, outputLow, outputHigh,
                    outputRateHz, channelDecimationFactor,
                    frequenciesHz.size(), bestCount, List.copyOf(cutOff));
        }

        private static int channelDecimationFactor(double sdrSampleRateHz, List<Integer> frequenciesHz) {
            double requiredSpan = Vdl2Demodulator.SAMPLE_RATE * 2.0;
            if (!frequenciesHz.isEmpty()) {
                double min = frequenciesHz.stream().mapToDouble(Integer::doubleValue).min().orElse(0.0);
                double max = frequenciesHz.stream().mapToDouble(Integer::doubleValue).max().orElse(0.0);
                requiredSpan = Math.max(requiredSpan, max - min + 2.0 * VDL2_CHANNEL_GUARD_HZ);
            }
            int factor = Math.max(2, (int) Math.ceil(requiredSpan / Vdl2Demodulator.SAMPLE_RATE));
            int maxFactor = Math.max(2, (int) Math.floor(sdrSampleRateHz / Vdl2Demodulator.SAMPLE_RATE));
            return Math.min(factor, maxFactor);
        }

        private static boolean fits(int frequencyHz, double centerHz, double sdrLowHz,
                                    double sdrHighHz, double halfOutputHz) {
            return frequencyHz >= sdrLowHz
                    && frequencyHz <= sdrHighHz
                    && Math.abs(frequencyHz - centerHz) <= halfOutputHz;
        }

        private static boolean fitsWithGuard(int frequencyHz, double centerHz, double sdrLowHz,
                                             double sdrHighHz, double halfOutputHz) {
            return frequencyHz - VDL2_CHANNEL_GUARD_HZ >= sdrLowHz
                    && frequencyHz + VDL2_CHANNEL_GUARD_HZ <= sdrHighHz
                    && Math.abs(frequencyHz - centerHz) + VDL2_CHANNEL_GUARD_HZ <= halfOutputHz;
        }

        private static double clamp(double value, double low, double high) {
            return Math.max(low, Math.min(high, value));
        }

        String describe() {
            StringBuilder message = new StringBuilder();
            message.append(String.format(Locale.ROOT,
                    "VDL2 resampler window: center %.6f MHz, span %.6f-%.6f MHz, rate %.0f ksps, channel decim %,d, SDR span %.6f-%.6f MHz, covers %,d/%,d configured channels%n",
                    centerHz / 1_000_000.0,
                    outputLowHz / 1_000_000.0,
                    outputHighHz / 1_000_000.0,
                    outputRateHz / 1_000.0,
                    channelDecimationFactor,
                    sdrLowHz / 1_000_000.0,
                    sdrHighHz / 1_000_000.0,
                    coveredChannels,
                    configuredChannels));
            if (!cutOffFrequenciesHz.isEmpty()) {
                message.append("WARNING: VDL2 channels outside the usable resampled/SDR bandwidth and likely cut off:");
                for (int frequencyHz : cutOffFrequenciesHz) {
                    message.append(String.format(Locale.ROOT, " %.3f", frequencyHz / 1_000_000.0));
                }
                message.append(" MHz\n");
            }
            return message.toString();
        }
    }
}
