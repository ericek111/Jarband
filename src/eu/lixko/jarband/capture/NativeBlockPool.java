package eu.lixko.jarband.capture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ArrayBlockingQueue;

public final class NativeBlockPool implements AutoCloseable {
    private final Arena arena = Arena.ofShared();
    private final ArrayBlockingQueue<MemorySegment> free;
    private final long blockBytes;

    public NativeBlockPool(int blocks, int complexSamplesPerBlock) {
        this.blockBytes = (long) complexSamplesPerBlock * 2L * Float.BYTES;
        this.free = new ArrayBlockingQueue<>(blocks);
        for (int i = 0; i < blocks; i++) {
            free.add(arena.allocate(blockBytes, ValueLayout.JAVA_FLOAT.byteAlignment()));
        }
    }

    public MemorySegment acquire() throws InterruptedException {
        return free.take();
    }

    public void release(MemorySegment segment) {
        if (segment != null && segment.byteSize() == blockBytes) {
            free.offer(segment);
        }
    }

    @Override
    public void close() {
        arena.close();
    }
}
