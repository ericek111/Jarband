package eu.lixko.jarband.dsp.vdl2;

public final class Vdl2Demodulator {
    public static final int SYMBOL_RATE = 10_500;
    public static final int SAMPLES_PER_SYMBOL = 10;
    public static final int SAMPLE_RATE = SYMBOL_RATE * SAMPLES_PER_SYMBOL;

    private static final int PREAMBLE_SYMBOLS = 16;
    private static final int SYNC_BUFFER_LENGTH = PREAMBLE_SYMBOLS * SAMPLES_PER_SYMBOL;
    private static final int SYNC_SKIP = 3;
    private static final float SYNC_THRESHOLD = 4.0f;
    private static final float PHASE_ERROR_MAX = 1000.0f;
    private static final float MAG_LP = 0.9f;
    private static final float NF_LP = 0.85f;
    private static final int ARITY = 8;
    private static final float PI_OVER_4 = (float) (Math.PI / 4.0);
    private static final float TWO_PI = (float) (2.0 * Math.PI);
    private static final int[] GRAY_CODE = { 0, 1, 3, 2, 6, 7, 5, 4 };
    private static final float[] PREAMBLE_PHASE = {
            0.0f * PI_OVER_4, 3.0f * PI_OVER_4, -3.0f * PI_OVER_4, 1.0f * PI_OVER_4,
            1.0f * PI_OVER_4, 2.0f * PI_OVER_4, 0.0f * PI_OVER_4, 4.0f * PI_OVER_4,
            -3.0f * PI_OVER_4, 4.0f * PI_OVER_4, -2.0f * PI_OVER_4, 3.0f * PI_OVER_4,
            1.0f * PI_OVER_4, -2.0f * PI_OVER_4, -3.0f * PI_OVER_4, 0.0f * PI_OVER_4
    };
    private static final float[] LR_X = linearRegressionX();
    private static final float LR_DENOM = linearRegressionDenominator();

    private final int frequencyHz;
    private final Vdl2SymbolSink sink;
    private final float[] syncBuffer = new float[SYNC_BUFFER_LENGTH];
    private final float[] syncError = new float[PREAMBLE_SYMBOLS];
    private final float[] phaseError = new float[3];
    private State state = State.SEARCHING;
    private int syncBufferIndex;
    private int symbolClock;
    private int symbolsSinceSync;
    private int noiseFloorCount;
    private int framePowerCount;
    private int headerSymbolCount;
    private boolean firstSymbolAfterSync;
    private float previousPhase;
    private float previousPhaseDelta;
    private float phaseDelta;
    private float ppmError;
    private float magnitudeLp;
    private float noiseFloorMagnitude = 2.0f;
    private float framePower;
    private long syncs;
    private long symbols;
    private float lastSyncError = Float.NaN;
    private float bestSyncError = Float.POSITIVE_INFINITY;
    private float lastFramePowerDbfs = Float.NaN;
    private float lastNoiseFloorDbfs = Float.NaN;
    private float lastSearchPowerDbfs = Float.NaN;
    private String lastHeaderSymbols = "";
    private final char[] headerSymbols = new char[9];

    Vdl2Demodulator(int frequencyHz, Vdl2SymbolSink sink) {
        this.frequencyHz = frequencyHz;
        this.sink = sink;
        resetSearch();
    }

    void accept(float i, float q, long unixMillis) {
        if (state == State.SEARCHING) {
            syncBufferIndex = (syncBufferIndex + 1) % SYNC_BUFFER_LENGTH;
            syncBuffer[syncBufferIndex] = (float) Math.atan2(q, i);
            if (++symbolClock < SYNC_SKIP) {
                return;
            }
            symbolClock = 0;
            float magnitude = (float) Math.hypot(i, q);
            magnitudeLp = magnitudeLp * MAG_LP + magnitude * (1.0f - MAG_LP);
            lastSearchPowerDbfs = noiseDb(magnitudeLp);
            lastNoiseFloorDbfs = noiseDb(noiseFloorMagnitude);
            if (++noiseFloorCount == 1000) {
                noiseFloorCount = 0;
                noiseFloorMagnitude = NF_LP * noiseFloorMagnitude
                        + (1.0f - NF_LP) * Math.min(magnitudeLp, noiseFloorMagnitude)
                        + 0.0001f;
            }
            if (gotSync()) {
                state = State.SYNCED;
                symbolsSinceSync = 0;
                framePower = 0.0f;
                framePowerCount = 0;
                headerSymbolCount = 0;
                firstSymbolAfterSync = true;
                syncs++;
            }
            return;
        }

        if (++symbolClock < SAMPLES_PER_SYMBOL) {
            return;
        }
        symbolClock = 0;

        float phase = (float) Math.atan2(q, i);
        float dphi = phase - previousPhase - phaseDelta;
        if (dphi < 0.0f) {
            dphi += TWO_PI;
        } else if (dphi > TWO_PI) {
            dphi -= TWO_PI;
        }
        int symbol = Math.floorMod(Math.round(dphi / PI_OVER_4), ARITY);
        float symbolPower = i * i + q * q;
        framePower = (framePower * framePowerCount + symbolPower) / (framePowerCount + 1);
        framePowerCount++;
        previousPhase = phase;
        symbols++;
        lastFramePowerDbfs = powerDb(framePower);
        lastNoiseFloorDbfs = noiseDb(noiseFloorMagnitude);
        boolean burstStart = firstSymbolAfterSync;
        firstSymbolAfterSync = false;
        int symbolBits = GRAY_CODE[symbol];
        if (headerSymbolCount < headerSymbols.length) {
            headerSymbols[headerSymbolCount++] = (char) ('0' + symbolBits);
            if (headerSymbolCount == headerSymbols.length) {
                lastHeaderSymbols = new String(headerSymbols);
            }
        }
        sink.accept(frequencyHz, unixMillis, symbolBits,
                lastFramePowerDbfs, lastNoiseFloorDbfs, ppmError, burstStart);

        // The Java side does not know the burst length anymore; dumpvdl2 does.
        // This just bounds bad locks so we return to preamble search quickly.
        if (++symbolsSinceSync > 8_192) {
            resetSearch();
        }
    }

    private boolean gotSync() {
        float errorMean = 0.0f;
        float unwrap = 0.0f;
        float previousError = syncBuffer[(syncBufferIndex + SAMPLES_PER_SYMBOL) % SYNC_BUFFER_LENGTH]
                - PREAMBLE_PHASE[0];
        syncError[0] = previousError;
        errorMean += previousError;
        for (int i = 1; i < PREAMBLE_SYMBOLS; i++) {
            float currentError = syncBuffer[(syncBufferIndex + (i + 1) * SAMPLES_PER_SYMBOL) % SYNC_BUFFER_LENGTH]
                    - PREAMBLE_PHASE[i];
            float diff = currentError - previousError;
            previousError = currentError;
            if (diff > Math.PI) {
                unwrap -= TWO_PI;
            } else if (diff < -Math.PI) {
                unwrap += TWO_PI;
            }
            syncError[i] = currentError + unwrap;
            errorMean += syncError[i];
        }
        errorMean /= PREAMBLE_SYMBOLS;

        float frequencyError = 0.0f;
        for (int i = 0; i < PREAMBLE_SYMBOLS; i++) {
            syncError[i] -= errorMean;
            frequencyError += LR_X[i] * syncError[i];
        }
        frequencyError /= LR_DENOM;

        phaseError[0] = 0.0f;
        for (int i = 0; i < PREAMBLE_SYMBOLS; i++) {
            float corrected = syncError[i] - frequencyError * LR_X[i];
            phaseError[0] += corrected * corrected;
        }
        lastSyncError = phaseError[0];
        bestSyncError = Math.min(bestSyncError, phaseError[0]);

        if (phaseError[1] < SYNC_THRESHOLD && phaseError[0] > phaseError[1]) {
            // Place the symbol clock at the minimum of the three recent sync
            // error samples, matching dumpvdl2's timing recovery.
            float vertexX = parabolaVertex(symbolClock, SYNC_SKIP, phaseError[2], phaseError[1], phaseError[0]);
            symbolClock = -Math.round(vertexX);
            int syncPoint = syncBufferIndex - symbolClock;
            if (syncPoint < 0) {
                syncPoint += SYNC_BUFFER_LENGTH;
            }
            previousPhase = syncBuffer[syncPoint];
            phaseDelta = previousPhaseDelta;
            ppmError = SYMBOL_RATE * phaseDelta / (TWO_PI * frequencyHz) * 1.0e6f;
            phaseError[1] = PHASE_ERROR_MAX;
            phaseError[2] = PHASE_ERROR_MAX;
            return true;
        }
        phaseError[2] = phaseError[1];
        phaseError[1] = phaseError[0];
        previousPhaseDelta = frequencyError;
        return false;
    }

    Stats statusAndReset() {
        Stats stats = new Stats(syncs, symbols, lastFramePowerDbfs, lastNoiseFloorDbfs,
                lastSearchPowerDbfs,
                ppmError, lastSyncError, Float.isFinite(bestSyncError) ? bestSyncError : Float.NaN,
                lastHeaderSymbols);
        syncs = 0;
        symbols = 0;
        bestSyncError = Float.POSITIVE_INFINITY;
        return stats;
    }

    private void resetSearch() {
        state = State.SEARCHING;
        symbolClock = 0;
        symbolsSinceSync = 0;
        headerSymbolCount = 0;
        firstSymbolAfterSync = false;
        phaseError[0] = 0.0f;
        phaseError[1] = PHASE_ERROR_MAX;
        phaseError[2] = PHASE_ERROR_MAX;
        framePower = 0.0f;
        framePowerCount = 0;
    }

    private static float powerDb(float power) {
        return 10.0f * (float) Math.log10(Math.max(power, 1.0e-20f));
    }

    private static float noiseDb(float magnitude) {
        return 20.0f * (float) Math.log10(magnitude + 0.001f);
    }

    private static float parabolaVertex(float x, int d, float y1, float y2, float y3) {
        float denom = d * 2.0f * d * -d;
        float a = (x * (y2 - y1) + (x - d) * (y1 - y3) + (x - 2 * d) * (y3 - y2)) / denom;
        float b = (x * x * (y1 - y2)
                + (x - d) * (x - d) * (y3 - y1)
                + (x - 2 * d) * (x - 2 * d) * (y2 - y3)) / denom;
        return -b / (2.0f * a);
    }

    private static float[] linearRegressionX() {
        float mean = (PREAMBLE_SYMBOLS - 1) / 2.0f;
        float[] x = new float[PREAMBLE_SYMBOLS];
        for (int i = 0; i < PREAMBLE_SYMBOLS; i++) {
            x[i] = i - mean;
        }
        return x;
    }

    private static float linearRegressionDenominator() {
        float denom = 0.0f;
        for (float x : LR_X) {
            denom += x * x;
        }
        return denom;
    }

    private enum State {
        SEARCHING,
        SYNCED
    }

    record Stats(long syncs, long symbols, float framePowerDbfs, float noiseFloorDbfs,
                 float searchPowerDbfs,
                 float ppmError, float lastSyncError, float bestSyncError, String headerSymbols) {
        float snrDb() {
            return framePowerDbfs - noiseFloorDbfs;
        }

        float searchSnrDb() {
            return searchPowerDbfs - noiseFloorDbfs;
        }
    }
}
