package eu.lixko.jarband.dsp.airband;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

import eu.lixko.jarband.capture.NativeSampleBlock;
import eu.lixko.jarband.fft.LiquidDsp;
import eu.lixko.jarband.recording.WavFloatWriter;

public final class WidebandChannelDebugDemod implements AutoCloseable {
    private static final int OUTPUT_RATE = (int) AirbandAmDemodulator.DEFAULT_IF_SAMPLE_RATE;

    private final Arena arena = Arena.ofShared();
    private final double sampleRate;
    private final AirbandAmDemodulator demod = new AirbandAmDemodulator(OUTPUT_RATE);
    private final MemorySegment resampler;
    private final WavFloatWriter wav;
    private double oscI = 1.0;
    private double oscQ = 0.0;
    private final double rotI;
    private final double rotQ;
    private MemorySegment mixed;
    private MemorySegment resampled;
    private MemorySegment outputCount;
    private int mixedCapacity;
    private int resampledCapacity;
    private long inputSamples;
    private long outputSamples;
    private double sumSquares;
    private float peak;

    public WidebandChannelDebugDemod(double sampleRate, double centerFrequency, double channelFrequency, Path wavPath)
            throws IOException {
        this.sampleRate = sampleRate;
        this.resampler = LiquidDsp.msresamp_crcf_create((float) (OUTPUT_RATE / sampleRate), 80.0f);
        this.wav = new WavFloatWriter(wavPath, OUTPUT_RATE);
        this.outputCount = arena.allocate(ValueLayout.JAVA_INT);

        double offsetHz = channelFrequency - centerFrequency;
        double phaseStep = -2.0 * Math.PI * offsetHz / sampleRate;
        this.rotI = Math.cos(phaseStep);
        this.rotQ = Math.sin(phaseStep);
    }

    public void accept(NativeSampleBlock block) throws IOException {
        int availableSamples = block.availableSampleCount();
        ensureCapacity(availableSamples);
        for (int n = 0; n < availableSamples; n++) {
            float i = block.samples().getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L);
            float q = block.samples().getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L + 1L);

            float mixedI = (float) (i * oscI + q * oscQ);
            float mixedQ = (float) (q * oscI - i * oscQ);
            stepOscillator();
            mixed.setAtIndex(ValueLayout.JAVA_FLOAT, n * 2L, mixedI);
            mixed.setAtIndex(ValueLayout.JAVA_FLOAT, n * 2L + 1L, mixedQ);
            inputSamples++;
        }

        int rc = LiquidDsp.msresamp_crcf_execute(resampler, mixed, availableSamples, resampled, outputCount);
        if (rc != 0) {
            throw new IllegalStateException("msresamp_crcf_execute failed: " + rc);
        }
        int produced = Math.min(outputCount.get(ValueLayout.JAVA_INT, 0), resampledCapacity);
        for (int n = 0; n < produced; n++) {
            float i = resampled.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L);
            float q = resampled.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L + 1L);
            float audio = demod.demodulate(i, q);
            wav.write(audio);
            outputSamples++;
            sumSquares += audio * audio;
            peak = Math.max(peak, Math.abs(audio));
        }
    }

    public String statusAndReset() {
        double rms = outputSamples == 0 ? 0.0 : Math.sqrt(sumSquares / outputSamples);
        String status = String.format(java.util.Locale.ROOT,
                "direct debug audio rms %.4f peak %.4f in %,d/s out %,d/s",
                rms, peak, inputSamples, outputSamples);
        inputSamples = 0;
        outputSamples = 0;
        sumSquares = 0.0;
        peak = 0.0f;
        return status;
    }

    private void stepOscillator() {
        double nextI = oscI * rotI - oscQ * rotQ;
        double nextQ = oscI * rotQ + oscQ * rotI;
        oscI = nextI;
        oscQ = nextQ;
        if ((inputSamples & 0x3fff) == 0) {
            double mag = Math.hypot(oscI, oscQ);
            oscI /= mag;
            oscQ /= mag;
        }
    }

    private void ensureCapacity(int inputCount) {
        if (inputCount > mixedCapacity) {
            mixedCapacity = inputCount;
            mixed = arena.allocate((long) mixedCapacity * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
        }
        int neededOutput = Math.max(32, (int) Math.ceil(inputCount * OUTPUT_RATE / sampleRate) + 256);
        if (neededOutput > resampledCapacity) {
            resampledCapacity = neededOutput;
            resampled = arena.allocate((long) resampledCapacity * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
        }
    }

    @Override
    public void close() throws IOException {
        try {
            wav.close();
        } finally {
            LiquidDsp.msresamp_crcf_destroy(resampler);
            arena.close();
        }
    }
}
