package eu.lixko.jarband.app;

import java.awt.BorderLayout;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import eu.lixko.jarband.FFT;
import eu.lixko.jarband.capture.NativeSampleBlock;
import eu.lixko.jarband.capture.SoapyCaptureReader;
import eu.lixko.jarband.dsp.airband.AirbandFrameProcessor;
import eu.lixko.jarband.dsp.airband.AirbandFrameProcessor.ChannelStatus;
import eu.lixko.jarband.dsp.channelizer.ChannelPlan;
import eu.lixko.jarband.dsp.channelizer.ChannelizedFrameRing;
import eu.lixko.jarband.dsp.channelizer.ChannelizedFrame;
import eu.lixko.jarband.dsp.channelizer.LiquidPfbAnalyzer;
import eu.lixko.jarband.dsp.channelizer.LogicalChannel;
import eu.lixko.jarband.dsp.channelizer.PfbConfig;
import eu.lixko.jarband.gui.ChannelActivityPanel;
import eu.lixko.jarband.gui.WaterfallPanel;
import eu.lixko.jarband.recording.RecorderBank;
import eu.lixko.jsoapy.util.NativeUtils;

public final class AirbandRecorder {
    private static final int WATERFALL_FFT_SIZE = 262_144;
    private static final int CHANNELIZED_QUEUE_CAPACITY = 64;

    private AirbandRecorder() {}

    public static void main(String[] args) throws Exception {
        NativeUtils.loadLibrary();

        Path configPath = args.length == 0 ? AirbandConfig.DEFAULT_PATH : Path.of(args[0]);
        AirbandConfig config = AirbandConfig.load(configPath);

        PfbConfig pfb = PfbConfig.forAirband(config.sampleRateHz(), config.centerFrequencyHz());
        ChannelPlan plan = ChannelPlan.visibleAirband(
                pfb,
                config.merge25kHzFrequencies(),
                config.skipFrequencies());

        System.out.printf("Jarband airband recorder: %,d logical channels, %.3f Hz PFB spacing%n",
                plan.size(), pfb.branchSpacingHz());
        System.out.printf("PFB channel output rate: %.3f Hz%n", pfb.channelOutputRateHz());
        System.out.printf("SDR: center %.6f MHz, bandwidth %.3f MHz%n",
                config.centerFrequencyHz() / 1_000_000.0,
                config.sampleRateHz() / 1_000_000.0);
        printChannelMap(pfb, plan);

        var captureQueue = new ArrayBlockingQueue<NativeSampleBlock>(8);
        var capture = new SoapyCaptureReader(
                new SoapyCaptureReader.Settings(config.soapyArgs(), config.sampleRateHz(),
                        config.centerFrequencyHz(), config.gains()),
                captureQueue);
        Thread captureThread = null;
        Thread channelizerThread = null;

        int prerollFrames = Math.max(1,
                (int) Math.ceil(pfb.channelOutputRateHz() * config.squelchPrerollMillis() / 1000.0));
        int closeLookaheadFrames = Math.max(1,
                (int) Math.ceil(pfb.channelOutputRateHz() * config.squelchCloseLookaheadMillis() / 1000.0));
        int ringFrames = prerollFrames + closeLookaheadFrames + CHANNELIZED_QUEUE_CAPACITY + 16;
        long ringBytes = (long) ringFrames * pfb.branches() * 2L * Float.BYTES;
        long maxHeap = Runtime.getRuntime().maxMemory();
        if (ringBytes > maxHeap / 2) {
            throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                    "Channelized replay ring would need %.1f MiB, more than half of the %.1f MiB max heap. "
                            + "Reduce sample rate/PFB branches or increase -Xmx.",
                    ringBytes / 1024.0 / 1024.0, maxHeap / 1024.0 / 1024.0));
        }
        System.out.printf("Channelized replay ring: %,d frames, %.1f MiB%n",
                ringFrames, ringBytes / 1024.0 / 1024.0);
        ChannelizedFrameRing preroll = new ChannelizedFrameRing(ringFrames, pfb.branches());
        AirbandFrameProcessor processor = new AirbandFrameProcessor(
                plan, preroll, prerollFrames, pfb.channelOutputRateHz(), config.opusSampleRateHz(),
                config.squelchOpenDb(), config.squelchCloseDb(),
                config.squelchOpenConfirmMillis(), config.squelchCloseLookaheadMillis());
        ChannelStatus status = processor.status();
        DebugWindow debug = DebugWindow.open(plan, pfb, status, config.waterfall(), config.channelWaterfall());
        var channelizedQueue = new ArrayBlockingQueue<ChannelizedFrame>(CHANNELIZED_QUEUE_CAPACITY);
        CaptureStats captureStats = new CaptureStats();
        ChannelizerWorker channelizer = new ChannelizerWorker(
                captureQueue, channelizedQueue, pfb, preroll, debug, captureStats);

        try (AirbandFrameProcessor frameProcessor = processor;
             DebugWindow debugWindow = debug;
             RecorderBank recorders = new RecorderBank(config.outputDirectory(), plan, true,
                     config.opusSampleRateHz(), config.opusBitrateBps(),
                     config.opusFrameMillis(), config.opusComplexity())) {
            long lastStatusNanos = System.nanoTime();
            long lastRepaintNanos = 0L;
            captureThread = Thread.ofPlatform().name("jarband-capture").start(capture);
            channelizerThread = Thread.ofPlatform().name("jarband-channelizer").start(channelizer);
            while (!Thread.currentThread().isInterrupted()) {
                ChannelizedFrame channelized = channelizedQueue.take();
                if (channelized.isPoison()) {
                    channelizer.throwIfFailed();
                    break;
                }
                frameProcessor.process(channelized, recorders);
                long now = System.nanoTime();
                if (now - lastRepaintNanos > 100_000_000L) {
                    lastRepaintNanos = now;
                    debugWindow.repaintActivity();
                }
                if (now - lastStatusNanos > 1_000_000_000L) {
                    lastStatusNanos = now;
                    channelizer.throwIfFailed();
                    System.out.println(captureStats.statusAndReset());
                    System.out.printf("Squelch open: %,d / %,d channels, above threshold: %,d, max margin: %.1f dB%n",
                            countOpen(status), plan.size(), countAboveMargin(status, config.squelchOpenDb()),
                            maxMargin(status));
                }
            }
        } finally {
            capture.stop();
            if (captureThread != null) {
                captureThread.interrupt();
                captureThread.join(1000);
            }
            if (channelizerThread != null) {
                channelizerThread.interrupt();
                channelizerThread.join(1000);
            }
        }
    }

    private static int updateWaterfall(NativeSampleBlock block, FFT fft, WaterfallPanel waterfall, int fill) {
        int consumed = 0;
        int availableSamples = block.availableSampleCount();
        int fftSize = (int) fft.getSize();
        while (consumed < availableSamples) {
            int copied = Math.min(fftSize - fill, availableSamples - consumed);
            MemorySegment source = block.samples().asSlice((long) consumed * 2L * Float.BYTES,
                    (long) copied * 2L * Float.BYTES);
            MemorySegment target = fft.fft_in.asSlice((long) fill * 2L * Float.BYTES,
                    (long) copied * 2L * Float.BYTES);
            target.copyFrom(source);
            fill += copied;
            consumed += copied;

            if (fill == fftSize) {
                fft.execute();
                waterfall.addLine(fft);
                fill = 0;
            }
        }
        return fill;
    }

    private static void printChannelMap(PfbConfig pfb, ChannelPlan plan) {
        if (plan.channels().isEmpty()) {
            return;
        }
        printChannelMapLine("first", pfb, plan.channels().getFirst());
        printChannelMapLine("middle", pfb, plan.channels().get(plan.channels().size() / 2));
        printChannelMapLine("last", pfb, plan.channels().getLast());
    }

    private static void printChannelMapLine(String label, PfbConfig pfb, eu.lixko.jarband.dsp.channelizer.LogicalChannel channel) {
        int bin = channel.pfbBins()[channel.pfbBins().length / 2];
        System.out.printf("Channel map %s: %.6f MHz -> bin %,d (%.6f MHz)%n",
                label,
                channel.frequencyHz() / 1_000_000.0,
                bin,
                ChannelPlan.frequencyForBin(pfb, bin) / 1_000_000.0);
    }

    private static int countOpen(ChannelStatus status) {
        int open = 0;
        for (byte squelch : status.squelchState) {
            if (squelch == ChannelStatus.OPEN) {
                open++;
            }
        }
        return open;
    }

    private static float maxMargin(ChannelStatus status) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < status.power.length; i++) {
            if (Float.isFinite(status.power[i]) && Float.isFinite(status.noiseFloor[i])) {
                max = Math.max(max, status.power[i] - status.noiseFloor[i]);
            }
        }
        return Float.isFinite(max) ? max : Float.NaN;
    }

    private static int countAboveMargin(ChannelStatus status, float marginDb) {
        int count = 0;
        for (int i = 0; i < status.power.length; i++) {
            if (Float.isFinite(status.power[i]) && Float.isFinite(status.noiseFloor[i])
                    && status.power[i] - status.noiseFloor[i] >= marginDb) {
                count++;
            }
        }
        return count;
    }

    private record DebugWindow(WaterfallPanel waterfall, ChannelActivityPanel activity,
                               ChannelSpectrumWaterfall channelSpectrum) implements AutoCloseable {
        static DebugWindow open(ChannelPlan plan, PfbConfig pfb, ChannelStatus status,
                                boolean waterfallEnabled, boolean channelWaterfallEnabled) throws Exception {
            if (!waterfallEnabled) {
                return new DebugWindow(null, null, null);
            }
            WaterfallPanel waterfall = new WaterfallPanel(1800, 720);
            waterfall.enableMouse();
            waterfall.setMinValue(-110.0f);
            waterfall.setMaxValue(-45.0f);
            waterfall.setZoomLevel(1.0);

            JLabel statusLabel = new JLabel("Click a channel activity segment");
            ChannelActivityPanel activity = new ChannelActivityPanel(plan, pfb, status, waterfall, statusLabel);
            ChannelSpectrumWaterfall channelSpectrum = channelWaterfallEnabled
                    ? new ChannelSpectrumWaterfall(activity)
                    : null;
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame("Jarband Airband Debug");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                JPanel bottom = new JPanel(new BorderLayout());
                bottom.add(activity, BorderLayout.CENTER);
                bottom.add(statusLabel, BorderLayout.SOUTH);
                frame.add(waterfall, BorderLayout.CENTER);
                frame.add(bottom, BorderLayout.SOUTH);
                frame.pack();
                frame.setVisible(true);

                if (channelSpectrum != null) {
                    JFrame channelFrame = new JFrame("Jarband Selected Channel Spectrum");
                    channelFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                    channelFrame.add(channelSpectrum.waterfall(), BorderLayout.CENTER);
                    channelFrame.pack();
                    channelFrame.setVisible(true);
                }
            });
            return new DebugWindow(waterfall, activity, channelSpectrum);
        }

        void repaintActivity() {
            if (activity != null) {
                SwingUtilities.invokeLater(activity::repaint);
            }
        }

        @Override
        public void close() throws Exception {
            if (channelSpectrum != null) {
                channelSpectrum.close();
            }
        }
    }

    private static final class ChannelizerWorker implements Runnable {
        private final ArrayBlockingQueue<NativeSampleBlock> input;
        private final ArrayBlockingQueue<ChannelizedFrame> output;
        private final PfbConfig pfb;
        private final ChannelizedFrameRing preroll;
        private final DebugWindow debug;
        private final CaptureStats captureStats;
        private volatile Throwable failure;

        ChannelizerWorker(ArrayBlockingQueue<NativeSampleBlock> input,
                          ArrayBlockingQueue<ChannelizedFrame> output,
                          PfbConfig pfb,
                          ChannelizedFrameRing preroll,
                          DebugWindow debug,
                          CaptureStats captureStats) {
            this.input = input;
            this.output = output;
            this.pfb = pfb;
            this.preroll = preroll;
            this.debug = debug;
            this.captureStats = captureStats;
        }

        @Override
        public void run() {
            try (LiquidPfbAnalyzer analyzer = new LiquidPfbAnalyzer(pfb)) {
                FFT waterfallFft = debug.waterfall() == null ? null : new FFT(WATERFALL_FFT_SIZE);
                int waterfallFill = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    NativeSampleBlock block = input.take();
                    if (block.isPoison()) {
                        break;
                    }
                    captureStats.accept(block);
                    if (waterfallFft != null) {
                        waterfallFill = updateWaterfall(block, waterfallFft, debug.waterfall(), waterfallFill);
                    }
                    int frames = analyzer.framesIn(block);
                    for (int i = 0; i < frames; i++) {
                        ChannelizedFrame channelized = analyzer.execute(block, i, preroll);
                        if (debug.channelSpectrum() != null) {
                            debug.channelSpectrum().accept(channelized);
                        }
                        output.put(channelized);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                failure = t;
            } finally {
                signalDone();
            }
        }

        void throwIfFailed() {
            if (failure != null) {
                throw new IllegalStateException("Channelizer thread failed", failure);
            }
        }

        private void signalDone() {
            while (!output.offer(ChannelizedFrame.poison())) {
                output.poll();
            }
        }
    }

    private static final class ChannelSpectrumWaterfall implements AutoCloseable {
        private static final int NEIGHBOR_BINS = 129;

        private final ChannelActivityPanel activity;
        private final WaterfallPanel waterfall = new WaterfallPanel(900, 360);
        private final float[] line = new float[NEIGHBOR_BINS];
        private int channelId = -1;

        ChannelSpectrumWaterfall(ChannelActivityPanel activity) {
            this.activity = activity;
            waterfall.setMinValue(-80.0f);
            waterfall.setMaxValue(20.0f);
            waterfall.setZoomLevel(1.0);
        }

        WaterfallPanel waterfall() {
            return waterfall;
        }

        void accept(ChannelizedFrame frame) {
            LogicalChannel channel = activity.selectedChannel();
            if (channel == null) {
                return;
            }
            if (channel.id() != channelId) {
                channelId = channel.id();
            }

            int centerBin = channel.pfbBins()[channel.pfbBins().length / 2];
            int half = NEIGHBOR_BINS / 2;
            for (int x = 0; x < line.length; x++) {
                int relativeBin = x - half;
                int bin = Math.floorMod(centerBin + relativeBin, frame.branchCount());
                float i = frame.i(bin);
                float q = frame.q(bin);
                line[x] = 10.0f * (float) Math.log10(i * i + q * q + 1.0e-20f);
            }
            waterfall.addLine(line);
        }

        @Override
        public void close() {
        }
    }

    private static final class CaptureStats {
        private static final int SAMPLE_STRIDE = 16;
        private static final float NEAR_FULL_SCALE_SQUARED = 0.98f * 0.98f;

        private long samples;
        private long nearFullScale;
        private double sumSquares;
        private float peak;

        synchronized void accept(NativeSampleBlock block) {
            MemorySegment samplesSegment = block.samples();
            int availableSamples = block.availableSampleCount();
            for (int n = 0; n < availableSamples; n += SAMPLE_STRIDE) {
                float i = samplesSegment.getAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, n * 2L);
                float q = samplesSegment.getAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, n * 2L + 1L);
                float magnitudeSquared = i * i + q * q;
                samples++;
                sumSquares += magnitudeSquared;
                peak = Math.max(peak, Math.max(Math.abs(i), Math.abs(q)));
                if (Math.abs(i) >= 0.98f || Math.abs(q) >= 0.98f
                        || magnitudeSquared >= NEAR_FULL_SCALE_SQUARED) {
                    nearFullScale++;
                }
            }
        }

        synchronized String statusAndReset() {
            double rms = samples == 0 ? 0.0 : Math.sqrt(sumSquares / samples);
            double clippedPercent = samples == 0 ? 0.0 : nearFullScale * 100.0 / samples;
            String status = String.format(java.util.Locale.ROOT,
                    "RF input sampled-rms %.4f peak %.4f sampled-near-full-scale %.3f%%",
                    rms, peak, clippedPercent);
            samples = 0;
            nearFullScale = 0;
            sumSquares = 0.0;
            peak = 0.0f;
            return status;
        }
    }
}
