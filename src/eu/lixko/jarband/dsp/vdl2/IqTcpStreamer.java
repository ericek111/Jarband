package eu.lixko.jarband.dsp.vdl2;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.net.Socket;

import eu.lixko.jarband.capture.NativeSampleBlock;

public final class IqTcpStreamer implements AutoCloseable {
    private static final long RECONNECT_INTERVAL_NANOS = 2_000_000_000L;
    private static final float INT16_SCALE = 32767.0f;

    private final InetSocketAddress address;
    private final Vdl2WidebandResampler resampler;
    private Socket socket;
    private OutputStream output;
    private byte[] outputBytes;
    private long nextConnectAttemptNanos;
    private long blocksSent;
    private long blocksDropped;
    private long samplesSent;
    private long reconnects;
    private long connectionFailures;
    private boolean connectedLogged;

    public IqTcpStreamer(double inputSampleRateHz, double inputCenterFrequencyHz,
                         double outputCenterFrequencyHz, int outputSampleRateHz,
                         InetSocketAddress address) {
        this.address = address;
        this.resampler = new Vdl2WidebandResampler(
                inputSampleRateHz, outputSampleRateHz, inputCenterFrequencyHz, outputCenterFrequencyHz);
    }

    public void process(NativeSampleBlock block) {
        long now = System.nanoTime();
        if (!ensureConnected(now)) {
            blocksDropped++;
            return;
        }

        Vdl2WidebandResampler.Result result = resampler.process(block);
        int samples = result.sampleCount();
        ensureOutputCapacity(samples);
        MemorySegment iq = result.samples();
        for (int n = 0; n < samples; n++) {
            short i = floatToInt16(iq.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L));
            short q = floatToInt16(iq.getAtIndex(ValueLayout.JAVA_FLOAT, n * 2L + 1L));
            int offset = n * 4;
            outputBytes[offset] = (byte) i;
            outputBytes[offset + 1] = (byte) (i >>> 8);
            outputBytes[offset + 2] = (byte) q;
            outputBytes[offset + 3] = (byte) (q >>> 8);
        }

        try {
            output.write(outputBytes, 0, samples * 4);
            blocksSent++;
            samplesSent += samples;
        } catch (IOException e) {
            disconnect(e);
            blocksDropped++;
        }
    }

    public synchronized Counters countersAndReset() {
        Counters counters = new Counters(blocksSent, blocksDropped, samplesSent,
                reconnects, connectionFailures,
                socket != null && socket.isConnected() && !socket.isClosed());
        blocksSent = 0;
        blocksDropped = 0;
        samplesSent = 0;
        reconnects = 0;
        connectionFailures = 0;
        return counters;
    }

    private boolean ensureConnected(long now) {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return true;
        }
        if (now < nextConnectAttemptNanos) {
            return false;
        }
        return tryConnect(now);
    }

    private boolean tryConnect(long now) {
        closeSocket();
        try {
            Socket candidate = new Socket();
            candidate.setTcpNoDelay(true);
            candidate.connect(address, 1000);
            socket = candidate;
            output = candidate.getOutputStream();
            reconnects++;
            if (!connectedLogged) {
                System.out.printf("I/Q S16_LE stream connected to %s:%d%n",
                        address.getHostString(), address.getPort());
                connectedLogged = true;
            }
            return true;
        } catch (IOException e) {
            connectionFailures++;
            nextConnectAttemptNanos = now + RECONNECT_INTERVAL_NANOS;
            return false;
        }
    }

    private void disconnect(IOException e) {
        System.out.printf("I/Q S16_LE stream disconnected: %s%n", e.getMessage());
        closeSocket();
        nextConnectAttemptNanos = System.nanoTime() + RECONNECT_INTERVAL_NANOS;
        connectedLogged = false;
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
        output = null;
    }

    private void ensureOutputCapacity(int samples) {
        int bytes = samples * 4;
        if (outputBytes == null || outputBytes.length < bytes) {
            outputBytes = new byte[bytes];
        }
    }

    private static short floatToInt16(float value) {
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));
        return (short) Math.round(clamped * INT16_SCALE);
    }

    @Override
    public void close() {
        resampler.close();
        closeSocket();
    }

    public record Counters(long blocksSent, long blocksDropped, long samplesSent,
                           long reconnects, long connectionFailures, boolean connected) {
    }
}
