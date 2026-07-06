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

    private final Socket socket;
    private final OutputStream output;
    private final Map<Integer, Batch> batches = new HashMap<>();

    public Vdl2SymbolSocket(InetSocketAddress address) throws IOException {
        this.socket = new Socket();
        this.socket.connect(address);
        this.output = socket.getOutputStream();
        output.write('J');
        output.write('V');
        output.write('2');
        output.write('S');
        output.write(1);
    }

    @Override
    public synchronized void accept(int frequencyHz, long unixMillis, int symbolBits,
                                    float framePowerDbfs, float noiseFloorDbfs, float ppmError) {
        try {
            Batch batch = batches.computeIfAbsent(frequencyHz, ignored -> new Batch());
            if (batch.count > 0
                    && (batch.count == MAX_SYMBOLS_PER_BATCH
                    || unixMillis - batch.firstUnixMillis >= MAX_BATCH_MILLIS)) {
                flush(frequencyHz, batch);
            }
            if (batch.count == 0) {
                batch.firstUnixMillis = unixMillis;
            }
            batch.framePowerDbfs = framePowerDbfs;
            batch.noiseFloorDbfs = noiseFloorDbfs;
            batch.ppmError = ppmError;
            batch.symbols[batch.count++] = (byte) (symbolBits & 0x07);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write VDL2 symbol batch", e);
        }
    }

    private void flush(int frequencyHz, Batch batch) throws IOException {
        if (batch.count == 0) {
            return;
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        header.putInt(frequencyHz);
        header.putLong(batch.firstUnixMillis);
        header.putFloat(batch.framePowerDbfs);
        header.putFloat(batch.noiseFloorDbfs);
        header.putFloat(batch.ppmError);
        header.putShort((short) batch.count);
        header.putInt(0);
        output.write(header.array());
        output.write(batch.symbols, 0, batch.count);
        batch.count = 0;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        for (var entry : batches.entrySet()) {
            try {
                flush(entry.getKey(), entry.getValue());
            } catch (IOException e) {
                failure = e;
            }
        }
        try {
            socket.close();
        } catch (IOException e) {
            failure = e;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static final class Batch {
        final byte[] symbols = new byte[MAX_SYMBOLS_PER_BATCH];
        long firstUnixMillis;
        float framePowerDbfs;
        float noiseFloorDbfs;
        float ppmError;
        int count;
    }
}
