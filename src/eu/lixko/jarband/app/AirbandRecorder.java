package eu.lixko.jarband.app;

import java.awt.BorderLayout;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
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
import eu.lixko.jarband.dsp.vdl2.Vdl2Processor;
import eu.lixko.jarband.dsp.vdl2.Vdl2WidebandResampler;
import eu.lixko.jarband.gui.ChannelActivityPanel;
import eu.lixko.jarband.gui.WaterfallPanel;
import eu.lixko.jarband.recording.RecorderBank;
import eu.lixko.jsoapy.util.NativeUtils;

public final class AirbandRecorder {
    private static final int WATERFALL_FFT_SIZE = 262_144;
    private static final int VDL2_WATERFALL_FFT_SIZE = 32_768;
    private static final int CHANNELIZED_QUEUE_CAPACITY = 64;
    private static final int VDL2_QUEUE_CAPACITY = 4;

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
        Thread vdl2Thread = null;

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
        DebugWindow debug = DebugWindow.open(plan, pfb, status, config.waterfall(),
                config.channelWaterfall(), config.vdl2Waterfall(), config.vdl2FrequenciesHz());
        var channelizedQueue = new ArrayBlockingQueue<ChannelizedFrame>(CHANNELIZED_QUEUE_CAPACITY);
        CaptureStats captureStats = new CaptureStats();
        Vdl2Processor vdl2 = config.vdl2Enabled()
                ? new Vdl2Processor(config.sampleRateHz(), config.centerFrequencyHz(),
                        config.vdl2FrequenciesHz(), config.vdl2Output(), debug.vdl2IqSink())
                : null;
        Vdl2Worker vdl2Worker = vdl2 == null ? null : new Vdl2Worker(vdl2);
        if (vdl2 != null) {
            System.out.printf("VDL2 demod enabled: %,d channels -> %s:%d%n",
                    config.vdl2FrequenciesHz().size(),
                    config.vdl2Output().getHostString(),
                    config.vdl2Output().getPort());
        }
        ChannelizerWorker channelizer = new ChannelizerWorker(
                captureQueue, channelizedQueue, pfb, preroll, debug, captureStats, vdl2Worker);

        try (AirbandFrameProcessor frameProcessor = processor;
             DebugWindow debugWindow = debug;
             RecorderBank recorders = new RecorderBank(config.outputDirectory(), plan, true,
                     config.opusSampleRateHz(), config.opusBitrateBps(),
                     config.opusFrameMillis(), config.opusComplexity())) {
            long lastStatusNanos = System.nanoTime();
            long lastRepaintNanos = 0L;
            captureThread = Thread.ofPlatform().name("jarband-capture").start(capture);
            if (vdl2Worker != null) {
                vdl2Thread = Thread.ofPlatform().name("jarband-vdl2").start(vdl2Worker);
            }
            channelizerThread = Thread.ofPlatform().name("jarband-channelizer").start(channelizer);
            while (!Thread.currentThread().isInterrupted()) {
                ChannelizedFrame channelized = channelizedQueue.take();
                if (channelized.isPoison()) {
                    channelizer.throwIfFailed();
                    if (vdl2Worker != null) {
                        vdl2Worker.throwIfFailed();
                    }
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
                    if (vdl2Worker != null) {
                        vdl2Worker.throwIfFailed();
                        System.out.println(vdl2Worker.statusAndReset());
                    }
                }
            }
        } finally {
            capture.stop();
            if (vdl2Worker != null) {
                vdl2Worker.stop();
            }
            if (captureThread != null) {
                captureThread.interrupt();
                captureThread.join(1000);
            }
            if (channelizerThread != null) {
                channelizerThread.interrupt();
                channelizerThread.join(1000);
            }
            if (vdl2Thread != null) {
                vdl2Thread.interrupt();
                vdl2Thread.join(1000);
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
                               ChannelSpectrumWaterfall channelSpectrum,
                               Vdl2IqWaterfall vdl2Waterfall) implements AutoCloseable {
        static DebugWindow open(ChannelPlan plan, PfbConfig pfb, ChannelStatus status,
                                boolean waterfallEnabled, boolean channelWaterfallEnabled,
                                boolean vdl2WaterfallEnabled, List<Integer> vdl2FrequenciesHz) throws Exception {
            WaterfallPanel waterfall = null;
            ChannelActivityPanel activity = null;
            ChannelSpectrumWaterfall channelSpectrum = null;
            Vdl2IqWaterfall vdl2Waterfall = vdl2WaterfallEnabled
                    ? new Vdl2IqWaterfall(vdl2FrequenciesHz)
                    : null;
            JLabel statusLabel = new JLabel("Click a channel activity segment");

            if (waterfallEnabled) {
                waterfall = new WaterfallPanel(1800, 720);
                waterfall.enableMouse();
                waterfall.setMinValue(-110.0f);
                waterfall.setMaxValue(-45.0f);
                waterfall.setZoomLevel(1.0);

                activity = new ChannelActivityPanel(plan, pfb, status, waterfall, statusLabel);
                channelSpectrum = channelWaterfallEnabled
                        ? new ChannelSpectrumWaterfall(activity)
                        : null;
            }

            if (!waterfallEnabled && vdl2Waterfall == null) {
                return new DebugWindow(null, null, null, null);
            }

            WaterfallPanel airbandWaterfall = waterfall;
            ChannelActivityPanel airbandActivity = activity;
            ChannelSpectrumWaterfall airbandChannelSpectrum = channelSpectrum;
            JLabel airbandStatusLabel = statusLabel;
            SwingUtilities.invokeAndWait(() -> {
                if (airbandWaterfall != null) {
                    JFrame frame = new JFrame("Jarband Airband Debug");
                    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    JPanel bottom = new JPanel(new BorderLayout());
                    bottom.add(airbandActivity, BorderLayout.CENTER);
                    bottom.add(airbandStatusLabel, BorderLayout.SOUTH);
                    frame.add(airbandWaterfall, BorderLayout.CENTER);
                    frame.add(bottom, BorderLayout.SOUTH);
                    frame.pack();
                    frame.setVisible(true);
                }

                if (airbandChannelSpectrum != null) {
                    JFrame channelFrame = new JFrame("Jarband Selected Channel Spectrum");
                    channelFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                    channelFrame.add(airbandChannelSpectrum.waterfall(), BorderLayout.CENTER);
                    channelFrame.pack();
                    channelFrame.setVisible(true);
                }

                if (vdl2Waterfall != null) {
                    vdl2Waterfall.open();
                }
            });
            return new DebugWindow(waterfall, activity, channelSpectrum, vdl2Waterfall);
        }

        Vdl2Processor.IqSink vdl2IqSink() {
            return vdl2Waterfall;
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
            if (vdl2Waterfall != null) {
                vdl2Waterfall.close();
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
        private final Vdl2Worker vdl2Worker;
        private volatile Throwable failure;

        ChannelizerWorker(ArrayBlockingQueue<NativeSampleBlock> input,
                          ArrayBlockingQueue<ChannelizedFrame> output,
                          PfbConfig pfb,
                          ChannelizedFrameRing preroll,
                          DebugWindow debug,
                          CaptureStats captureStats,
                          Vdl2Worker vdl2Worker) {
            this.input = input;
            this.output = output;
            this.pfb = pfb;
            this.preroll = preroll;
            this.debug = debug;
            this.captureStats = captureStats;
            this.vdl2Worker = vdl2Worker;
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
                    if (vdl2Worker != null) {
                        vdl2Worker.submit(block);
                    }
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
                if (vdl2Worker != null) {
                    vdl2Worker.stop();
                }
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

    private static final class Vdl2Worker implements Runnable {
        private final Vdl2Processor processor;
        private final ArrayBlockingQueue<NativeSampleBlock> queue = new ArrayBlockingQueue<>(VDL2_QUEUE_CAPACITY);
        private volatile Throwable failure;
        private long droppedBlocks;

        Vdl2Worker(Vdl2Processor processor) {
            this.processor = processor;
        }

        void submit(NativeSampleBlock block) {
            if (failure != null) {
                return;
            }
            if (!queue.offer(block)) {
                do {
                    queue.poll();
                    droppedBlocks++;
                } while (!queue.offer(block));
                if ((droppedBlocks & 0x3f) == 0) {
                    System.out.printf("VDL2 dropped %,d stale blocks because demodulation is behind%n", droppedBlocks);
                }
            }
        }

        void stop() {
            while (!queue.offer(NativeSampleBlock.POISON)) {
                queue.poll();
            }
        }

        String statusAndReset() {
            long dropped = droppedBlocks;
            droppedBlocks = 0;
            String status = processor.statusAndReset();
            if (dropped > 0) {
                status += String.format(java.util.Locale.ROOT, "%n  VDL2 worker dropped %,d stale blocks", dropped);
            }
            return status;
        }

        void throwIfFailed() {
            if (failure != null) {
                throw new IllegalStateException("VDL2 thread failed", failure);
            }
        }

        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    NativeSampleBlock block = queue.take();
                    if (block.isPoison()) {
                        break;
                    }
                    processor.process(block);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                failure = t;
            } finally {
                try {
                    processor.close();
                } catch (Exception e) {
                    if (failure == null) {
                        failure = e;
                    }
                }
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

    private static final class Vdl2IqWaterfall implements Vdl2Processor.IqSink, AutoCloseable {
        private final List<Integer> frequenciesHz;
        private final WaterfallPanel waterfall = new WaterfallPanel(900, 360);
        private volatile JFrame frame;
        private volatile boolean closed;
        private FFT fft;
        private int fill;

        Vdl2IqWaterfall(List<Integer> frequenciesHz) {
            this.frequenciesHz = List.copyOf(frequenciesHz);
            waterfall.setMinValue(-115.0f);
            waterfall.setMaxValue(-35.0f);
            waterfall.setZoomLevel(1.0);
        }

        void open() {
            frame = new JFrame("Jarband VDL2 Resampled I/Q Spectrum");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(waterfall, BorderLayout.CENTER);
            frame.add(new JLabel(labelText()), BorderLayout.SOUTH);
            frame.pack();
            frame.setVisible(true);
        }

        @Override
        public void accept(MemorySegment samples, int sampleCount) {
            if (closed || sampleCount == 0) {
                return;
            }
            if (fft == null) {
                fft = new FFT(VDL2_WATERFALL_FFT_SIZE);
            }
            int consumed = 0;
            while (consumed < sampleCount) {
                int copied = Math.min(VDL2_WATERFALL_FFT_SIZE - fill, sampleCount - consumed);
                MemorySegment source = samples.asSlice((long) consumed * 2L * Float.BYTES,
                        (long) copied * 2L * Float.BYTES);
                MemorySegment target = fft.fft_in.asSlice((long) fill * 2L * Float.BYTES,
                        (long) copied * 2L * Float.BYTES);
                target.copyFrom(source);
                fill += copied;
                consumed += copied;

                if (fill == VDL2_WATERFALL_FFT_SIZE) {
                    fft.execute();
                    waterfall.addLine(fft);
                    fill = 0;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            if (fft != null) {
                try {
                    fft.close();
                } catch (Exception e) {
                    // Debug display cleanup should not take down recorder shutdown.
                }
            }
            JFrame currentFrame = frame;
            if (currentFrame != null) {
                SwingUtilities.invokeLater(currentFrame::dispose);
            }
        }

        private String labelText() {
            if (frequenciesHz.isEmpty()) {
                return String.format(java.util.Locale.ROOT,
                        "Resampled I/Q FFT: %.3f Msps; no VDL2 channels configured",
                        Vdl2WidebandResampler.OUTPUT_RATE / 1_000_000.0);
            }
            StringBuilder label = new StringBuilder(String.format(java.util.Locale.ROOT,
                    "Resampled I/Q FFT: %.3f Msps; VDL2 channels: ",
                    Vdl2WidebandResampler.OUTPUT_RATE / 1_000_000.0));
            for (int i = 0; i < frequenciesHz.size(); i++) {
                if (i > 0) {
                    label.append("  ");
                }
                label.append(String.format(java.util.Locale.ROOT, "%.3f",
                        frequenciesHz.get(i) / 1_000_000.0));
            }
            return label.toString();
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
