package eu.lixko.jarband.dsp.channelizer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jarband.capture.NativeSampleBlock;
import eu.lixko.jarband.fft.LiquidDsp;

public final class LiquidPfbAnalyzer implements AutoCloseable {
    private final PfbConfig config;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment analyzer;
    private final MemorySegment input;
    private final MemorySegment output;

    public LiquidPfbAnalyzer(PfbConfig config) {
        this.config = config;
        this.analyzer = LiquidDsp.firpfbch_crcf_create_kaiser(
                LiquidDsp.LIQUID_ANALYZER,
                config.branches(),
                config.semiLength(),
                config.stopbandAttenuation());
        this.input = arena.allocate((long) config.branches() * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
        this.output = arena.allocate((long) config.branches() * 2L * Float.BYTES, ValueLayout.JAVA_FLOAT.byteAlignment());
    }

    public int framesIn(NativeSampleBlock block) {
        return block.availableSampleCount() / config.branches();
    }

    public ChannelizedFrame execute(NativeSampleBlock block, int frameIndex, ChannelizedFrameRing ring) {
        long sampleOffset = (long) frameIndex * config.branches();
        MemorySegment source = block.samples().asSlice(sampleOffset * 2L * Float.BYTES, input.byteSize());
        input.copyFrom(source);
        int rc = LiquidDsp.firpfbch_crcf_analyzer_execute(analyzer, input, output);
        if (rc != 0) {
            throw new IllegalStateException("firpfbch_crcf_analyzer_execute failed: " + rc);
        }
        return ring.append(output, block.firstSampleIndex() + sampleOffset, block.capturedNanos());
    }

    @Override
    public void close() {
        LiquidDsp.firpfbch_crcf_destroy(analyzer);
        arena.close();
    }
}
