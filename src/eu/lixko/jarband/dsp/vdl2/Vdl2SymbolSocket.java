package eu.lixko.jarband.dsp.vdl2;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

public final class Vdl2SymbolSocket implements Vdl2SymbolSink, Closeable {
    private static final int MAX_SYMBOLS_PER_BATCH = 128;
    private static final int MAX_BATCH_MILLIS = 25;
    private static final int HEADER_BYTES = 30;
    private static final long RECONNECT_INTERVAL_NANOS = 2_000_000_000L;
    private static final int FLAG_BURST_START = 1;

    private final InetSocketAddress address;
    private final Map<Integer, Batch> batches = new HashMap<>();
    private Socket socket;
    private OutputStream output;
    private long nextConnectAttemptNanos;
    private long batchesSent;
    private long symbolsSent;
    private long batchesDropped;
    private long symbolsDropped;
    private long reconnects;
    private long connectionFailures;
    private boolean connectedLogged;

    public Vdl2SymbolSocket(InetSocketAddress address) {
        this.address = address;
        tryConnect(System.nanoTime());
    }

    @Override
    public synchronized void accept(int frequencyHz, long unixMillis, int symbolBits,
                                    float framePowerDbfs, float noiseFloorDbfs, float ppmError, boolean burstStart) {
        Batch batch = batches.computeIfAbsent(frequencyHz, ignored -> new Batch());
        if (burstStart && batch.count > 0) {
            flush(frequencyHz, batch);
        }
        if (batch.count > 0
                && (batch.count == MAX_SYMBOLS_PER_BATCH
                || unixMillis - batch.firstUnixMillis >= MAX_BATCH_MILLIS)) {
            flush(frequencyHz, batch);
        }
        if (batch.count == 0) {
            batch.firstUnixMillis = unixMillis;
            batch.flags = 0;
        }
        if (burstStart) {
            batch.flags |= FLAG_BURST_START;
        }
        batch.framePowerDbfs = framePowerDbfs;
        batch.noiseFloorDbfs = noiseFloorDbfs;
        batch.ppmError = ppmError;
        batch.symbols[batch.count++] = (byte) (symbolBits & 0x07);
    }

    private void flush(int frequencyHz, Batch batch) {
        if (batch.count == 0) {
            return;
        }
        long now = System.nanoTime();
        if (!ensureConnected(now)) {
            drop(batch);
            return;
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        header.putInt(frequencyHz);
        header.putLong(batch.firstUnixMillis);
        header.putFloat(batch.framePowerDbfs);
        header.putFloat(batch.noiseFloorDbfs);
        header.putFloat(batch.ppmError);
        header.putShort((short) batch.count);
        header.putInt(batch.flags);
        try {
            output.write(header.array());
            output.write(batch.symbols, 0, batch.count);
            batchesSent++;
            symbolsSent += batch.count;
        } catch (IOException e) {
            disconnect(e);
            drop(batch);
            return;
        }
        batch.count = 0;
    }

    synchronized Counters countersAndReset() {
        Counters counters = new Counters(batchesSent, symbolsSent, batchesDropped, symbolsDropped,
                reconnects, connectionFailures, socket != null && socket.isConnected() && !socket.isClosed());
        batchesSent = 0;
        symbolsSent = 0;
        batchesDropped = 0;
        symbolsDropped = 0;
        reconnects = 0;
        connectionFailures = 0;
        return counters;
    }

    @Override
    public synchronized void close() {
        for (var entry : batches.entrySet()) {
            flush(entry.getKey(), entry.getValue());
        }
        closeSocket();
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
            candidate.connect(address, 1000);
            OutputStream candidateOutput = candidate.getOutputStream();
            candidateOutput.write('J');
            candidateOutput.write('V');
            candidateOutput.write('2');
            candidateOutput.write('S');
            candidateOutput.write(1);
            this.socket = candidate;
            this.output = candidateOutput;
            reconnects++;
            if (!connectedLogged) {
                System.out.printf("VDL2 symbol stream connected to %s:%d%n",
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
        System.out.printf("VDL2 symbol stream disconnected: %s%n", e.getMessage());
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

    private void drop(Batch batch) {
        batchesDropped++;
        symbolsDropped += batch.count;
        batch.count = 0;
    }

    private static final class Batch {
        final byte[] symbols = new byte[MAX_SYMBOLS_PER_BATCH];
        long firstUnixMillis;
        float framePowerDbfs;
        float noiseFloorDbfs;
        float ppmError;
        int flags;
        int count;
    }

    record Counters(long batches, long symbols, long droppedBatches, long droppedSymbols,
                    long reconnects, long connectionFailures, boolean connected) {
    }
}
