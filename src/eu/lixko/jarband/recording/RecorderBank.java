package eu.lixko.jarband.recording;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import eu.lixko.jarband.dsp.airband.AirbandFrameProcessor;
import eu.lixko.jarband.dsp.channelizer.ChannelPlan;

public final class RecorderBank implements AirbandFrameProcessor.AudioSink, AutoCloseable {
    private static final int QUEUE_CAPACITY = 4096;

    private final ArrayBlockingQueue<EncoderEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final List<ChannelRecorder> recorders;
    private final OpusFrameSink frameSink;
    private final Thread worker;
    private volatile Throwable failure;
    private volatile boolean closing;

    public RecorderBank(Path outputDir, ChannelPlan plan, boolean weeklyRotation,
                        int opusSampleRate, int opusBitrate, int opusFrameMillis, int opusComplexity) {
        this(outputDir, plan, weeklyRotation, opusSampleRate, opusBitrate, opusFrameMillis, opusComplexity, null);
    }

    public RecorderBank(Path outputDir, ChannelPlan plan, boolean weeklyRotation,
                        int opusSampleRate, int opusBitrate, int opusFrameMillis, int opusComplexity,
                        OpusFrameSink frameSink) {
        this.frameSink = frameSink;
        var list = new ArrayList<ChannelRecorder>(plan.size());
        for (var channel : plan.channels()) {
            list.add(new ChannelRecorder(outputDir, channel, weeklyRotation,
                    opusSampleRate, opusBitrate, opusFrameMillis, opusComplexity, frameSink));
        }
        this.recorders = List.copyOf(list);
        this.worker = Thread.ofPlatform().name("jarband-opus-writer").start(this::runWorker);
    }

    @Override
    public void audio(int channelId, long unixMillis, float[] audio, int length) {
        if (length <= 0) {
            return;
        }
        enqueue(EncoderEvent.audio(channelId, unixMillis, Arrays.copyOf(audio, length), length));
    }

    @Override
    public void closeUtterance(int channelId, long unixMillis, float averageSnrDb) {
        enqueue(EncoderEvent.closeUtterance(channelId, unixMillis, averageSnrDb));
    }

    private void enqueue(EncoderEvent event) {
        throwIfFailed();
        if (closing) {
            throw new IllegalStateException("Recorder bank is closing");
        }
        try {
            while (!queue.offer(event, 100, TimeUnit.MILLISECONDS)) {
                throwIfFailed();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while queueing recorder event", e);
        }
        throwIfFailed();
    }

    private void runWorker() {
        try {
            while (true) {
                EncoderEvent event = queue.take();
                if (event.kind == EncoderEvent.STOP) {
                    break;
                }
                if (event.kind == EncoderEvent.AUDIO) {
                    recorders.get(event.channelId).accept(event.unixMillis, event.audio, event.length);
                } else {
                    OpusFrameSink.UtteranceClosed closed =
                            recorders.get(event.channelId).closeUtterance(event.unixMillis, event.averageSnrDb);
                    if (frameSink != null) {
                        frameSink.closeUtterance(event.channelId, event.unixMillis, closed);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            failure = t;
        }
    }

    private void throwIfFailed() {
        Throwable thrown = failure;
        if (thrown != null) {
            throw new IllegalStateException("Opus recorder thread failed", thrown);
        }
    }

    @Override
    public void close() throws IOException {
        closing = true;
        if (failure == null) {
            try {
                while (!queue.offer(EncoderEvent.stop(), 100, TimeUnit.MILLISECONDS)) {
                    throwIfFailed();
                }
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping Opus recorder thread", e);
            }
        }

        IOException thrown = null;
        for (ChannelRecorder recorder : recorders) {
            try {
                recorder.close();
            } catch (IOException e) {
                if (thrown == null) thrown = e;
                else thrown.addSuppressed(e);
            }
        }
        try {
            throwIfFailed();
        } catch (IllegalStateException e) {
            IOException io = new IOException("Opus recorder thread failed", e);
            if (thrown == null) thrown = io;
            else thrown.addSuppressed(io);
        }
        if (thrown != null) throw thrown;
    }

    private static final class EncoderEvent {
        static final byte AUDIO = 0;
        static final byte CLOSE_UTTERANCE = 1;
        static final byte STOP = 2;

        final byte kind;
        final int channelId;
        final long unixMillis;
        final float averageSnrDb;
        final float[] audio;
        final int length;

        private EncoderEvent(byte kind, int channelId, long unixMillis, float averageSnrDb, float[] audio, int length) {
            this.kind = kind;
            this.channelId = channelId;
            this.unixMillis = unixMillis;
            this.averageSnrDb = averageSnrDb;
            this.audio = audio;
            this.length = length;
        }

        static EncoderEvent audio(int channelId, long unixMillis, float[] audio, int length) {
            return new EncoderEvent(AUDIO, channelId, unixMillis, 0.0f, audio, length);
        }

        static EncoderEvent closeUtterance(int channelId, long unixMillis, float averageSnrDb) {
            return new EncoderEvent(CLOSE_UTTERANCE, channelId, unixMillis, averageSnrDb, null, 0);
        }

        static EncoderEvent stop() {
            return new EncoderEvent(STOP, -1, 0L, 0.0f, null, 0);
        }
    }
}
