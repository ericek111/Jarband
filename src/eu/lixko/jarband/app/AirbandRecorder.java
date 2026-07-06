package eu.lixko.jarband.app;

import java.awt.BorderLayout;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import eu.lixko.jarband.FFT;
import eu.lixko.jarband.capture.NativeSampleBlock;
import eu.lixko.jarband.capture.SoapyCaptureReader;
import eu.lixko.jarband.dsp.airband.AirbandFrameProcessor;
import eu.lixko.jarband.dsp.airband.ChannelStateArrays;
import eu.lixko.jarband.dsp.airband.PowerSquelch;
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
    private static final String SDR_ARGS = "remote=10.0.34.40,remote:prot=tcp";
    private static final double SDR_SAMPLE_RATE_HZ = 8_000_000.0;
    private static final double SDR_CENTER_FREQUENCY_HZ = 133_000_005.0;
    private static final Map<String, Double> SDR_GAINS = Map.of("LNA", 1d, "Baseband", 23d, "Mixer", 0d, "Mixbuffer", 0d);

    private static final int WATERFALL_FFT_SIZE = 262_144;
    private static final boolean ENABLE_CHANNEL_WATERFALL = false;

    private static final float SQUELCH_OPEN_DB = 12.0f;
    private static final float SQUELCH_CLOSE_DB = 8.0f;
    private static final int SQUELCH_HANG_MILLIS = 800;
    private static final int PREROLL_MILLIS = 500;
    private static final int UTTERANCE_MERGE_MILLIS = 2_500;

    private static final Path OUTPUT_DIRECTORY = Path.of("recordings");

    private AirbandRecorder() {}

    public static void main(String[] args) throws Exception {
        NativeUtils.loadLibrary();

        PfbConfig pfb = PfbConfig.forAirband(SDR_SAMPLE_RATE_HZ, SDR_CENTER_FREQUENCY_HZ);
        ChannelPlan plan = ChannelPlan.visibleAirband(
                pfb,
                List.of());

        System.out.printf("Jarband airband recorder: %,d logical channels, %.3f Hz PFB spacing%n",
                plan.size(), pfb.branchSpacingHz());
        System.out.printf("PFB channel output rate: %.3f Hz%n", pfb.channelOutputRateHz());
        System.out.printf("SDR: center %.6f MHz, bandwidth %.3f MHz%n",
                SDR_CENTER_FREQUENCY_HZ / 1_000_000.0,
                SDR_SAMPLE_RATE_HZ / 1_000_000.0);
        printChannelMap(pfb, plan);

        var captureQueue = new ArrayBlockingQueue<NativeSampleBlock>(8);
        var capture = new SoapyCaptureReader(
                new SoapyCaptureReader.Settings(SDR_ARGS, SDR_SAMPLE_RATE_HZ, SDR_CENTER_FREQUENCY_HZ, SDR_GAINS),
                captureQueue);
        Thread captureThread = null;

        ChannelStateArrays state = new ChannelStateArrays(plan.size());
        DebugWindow debug = DebugWindow.open(plan, pfb, state);
        PowerSquelch squelch = new PowerSquelch(
                state,
                SQUELCH_OPEN_DB,
                SQUELCH_CLOSE_DB,
                SQUELCH_HANG_MILLIS);
        int prerollFrames = Math.max(1, (int) Math.ceil(pfb.channelOutputRateHz() * PREROLL_MILLIS / 1000.0));
        ChannelizedFrameRing preroll = new ChannelizedFrameRing(prerollFrames + 1, pfb.branches());
        AirbandFrameProcessor processor = new AirbandFrameProcessor(
                plan, state, squelch, preroll, prerollFrames, UTTERANCE_MERGE_MILLIS,
                pfb.channelOutputRateHz(), 8_000, Clock.systemUTC());

        try (DebugWindow debugWindow = debug;
             LiquidPfbAnalyzer analyzer = new LiquidPfbAnalyzer(pfb);
             FFT waterfallFft = new FFT(WATERFALL_FFT_SIZE);
             AirbandFrameProcessor frameProcessor = processor;
             RecorderBank recorders = new RecorderBank(OUTPUT_DIRECTORY, plan, true,
                     8_000, 16_000, 20, 3)) {
            float[] oneSample = new float[1];
            int waterfallFill = 0;
            long lastStatusNanos = System.nanoTime();
            CaptureStats captureStats = new CaptureStats();
            captureThread = Thread.ofPlatform().name("jarband-capture").start(capture);
            while (!Thread.currentThread().isInterrupted()) {
                NativeSampleBlock block = captureQueue.take();
                if (block.isPoison()) {
                    break;
                }
                captureStats.accept(block);
                waterfallFill = updateWaterfall(block, waterfallFft, debugWindow.waterfall(), waterfallFill);
                int frames = analyzer.framesIn(block);
                for (int i = 0; i < frames; i++) {
                    var channelized = analyzer.execute(block, i);
                    if (debugWindow.channelSpectrum() != null) {
                        debugWindow.channelSpectrum().accept(channelized);
                    }
                    frameProcessor.process(channelized, oneSample, recorders::accept);
                }
                debugWindow.repaintActivity();
                long now = System.nanoTime();
                if (now - lastStatusNanos > 1_000_000_000L) {
                    lastStatusNanos = now;
                    System.out.println(captureStats.statusAndReset());
                    System.out.printf("Squelch open: %,d / %,d channels, above threshold: %,d, max margin: %.1f dB%n",
                            countOpen(state), plan.size(), countAboveMargin(state, SQUELCH_OPEN_DB), maxMargin(state));
                }
            }
        } finally {
            capture.stop();
            if (captureThread != null) {
                captureThread.interrupt();
                captureThread.join(1000);
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

    private static int countOpen(ChannelStateArrays state) {
        int open = 0;
        for (byte squelch : state.squelchState) {
            if (squelch == ChannelStateArrays.OPEN) {
                open++;
            }
        }
        return open;
    }

    private static float maxMargin(ChannelStateArrays state) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < state.power.length; i++) {
            if (Float.isFinite(state.power[i]) && Float.isFinite(state.noiseFloor[i])) {
                max = Math.max(max, state.power[i] - state.noiseFloor[i]);
            }
        }
        return Float.isFinite(max) ? max : Float.NaN;
    }

    private static int countAboveMargin(ChannelStateArrays state, float marginDb) {
        int count = 0;
        for (int i = 0; i < state.power.length; i++) {
            if (Float.isFinite(state.power[i]) && Float.isFinite(state.noiseFloor[i])
                    && state.power[i] - state.noiseFloor[i] >= marginDb) {
                count++;
            }
        }
        return count;
    }

    private record DebugWindow(WaterfallPanel waterfall, ChannelActivityPanel activity,
                               ChannelSpectrumWaterfall channelSpectrum) implements AutoCloseable {
        static DebugWindow open(ChannelPlan plan, PfbConfig pfb, ChannelStateArrays state) throws Exception {
            WaterfallPanel waterfall = new WaterfallPanel(1800, 720);
            waterfall.enableMouse();
            waterfall.setMinValue(-110.0f);
            waterfall.setMaxValue(-45.0f);
            waterfall.setZoomLevel(1.0);

            JLabel status = new JLabel("Click a channel activity segment");
            ChannelActivityPanel activity = new ChannelActivityPanel(plan, pfb, state, waterfall, status);
            ChannelSpectrumWaterfall channelSpectrum = ENABLE_CHANNEL_WATERFALL
                    ? new ChannelSpectrumWaterfall(activity)
                    : null;
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame("Jarband Airband Debug");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                JPanel bottom = new JPanel(new BorderLayout());
                bottom.add(activity, BorderLayout.CENTER);
                bottom.add(status, BorderLayout.SOUTH);
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
            SwingUtilities.invokeLater(activity::repaint);
        }

        @Override
        public void close() throws Exception {
            if (channelSpectrum != null) {
                channelSpectrum.close();
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
        private long samples;
        private long nearFullScale;
        private double sumSquares;
        private float peak;

        void accept(NativeSampleBlock block) {
            MemorySegment samplesSegment = block.samples();
            int availableSamples = block.availableSampleCount();
            for (int n = 0; n < availableSamples; n++) {
                float i = samplesSegment.getAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, n * 2L);
                float q = samplesSegment.getAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, n * 2L + 1L);
                float mag = (float) Math.hypot(i, q);
                samples++;
                sumSquares += i * i + q * q;
                peak = Math.max(peak, Math.max(Math.abs(i), Math.abs(q)));
                if (Math.abs(i) >= 0.98f || Math.abs(q) >= 0.98f || mag >= 0.98f) {
                    nearFullScale++;
                }
            }
        }

        String statusAndReset() {
            double rms = samples == 0 ? 0.0 : Math.sqrt(sumSquares / samples);
            double clippedPercent = samples == 0 ? 0.0 : nearFullScale * 100.0 / samples;
            String status = String.format(java.util.Locale.ROOT,
                    "RF input rms %.4f peak %.4f near-full-scale %.3f%%",
                    rms, peak, clippedPercent);
            samples = 0;
            nearFullScale = 0;
            sumSquares = 0.0;
            peak = 0.0f;
            return status;
        }
    }
}
