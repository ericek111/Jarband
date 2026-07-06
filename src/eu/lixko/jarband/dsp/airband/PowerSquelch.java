package eu.lixko.jarband.dsp.airband;

public final class PowerSquelch {
    private static final int MIN_OPEN_SAMPLES = 250;
    private static final float POWER_ALPHA = 0.005f;

    private final ChannelStateArrays state;
    private final float openDb;
    private final float closeDb;
    private final int hangMillis;

    public PowerSquelch(ChannelStateArrays state, float openDb, float closeDb, int hangMillis) {
        this.state = state;
        this.openDb = openDb;
        this.closeDb = closeDb;
        this.hangMillis = hangMillis;
    }

    public float measure(int channel, float i, float q) {
        float linear = i * i + q * q + 1.0e-20f;
        float instantDb = 10.0f * (float) Math.log10(linear);
        return updatePower(channel, instantDb);
    }

    public boolean update(int channel, float db, float bandNoiseFloorDb, long nowMillis) {
        state.noiseFloor[channel] = bandNoiseFloorDb;
        state.noiseSamples[channel]++;
        float marginDb = db - bandNoiseFloorDb;

        if (state.squelchState[channel] == ChannelStateArrays.CLOSED) {
            if (marginDb >= openDb) {
                state.openSamples[channel]++;
            } else {
                state.openSamples[channel] = 0;
            }
            if (state.openSamples[channel] >= MIN_OPEN_SAMPLES) {
                state.squelchState[channel] = ChannelStateArrays.OPEN;
                state.utteranceStartMillis[channel] = nowMillis;
                state.lastOpenMillis[channel] = nowMillis;
                state.openSamples[channel] = 0;
            }
        } else if (marginDb >= closeDb) {
            state.lastOpenMillis[channel] = nowMillis;
        } else if (nowMillis - state.lastOpenMillis[channel] > hangMillis) {
            state.squelchState[channel] = ChannelStateArrays.CLOSED;
            state.openSamples[channel] = 0;
        }

        return state.squelchState[channel] == ChannelStateArrays.OPEN;
    }

    private float updatePower(int channel, float instantDb) {
        if (!Float.isFinite(state.power[channel])) {
            state.power[channel] = instantDb;
        } else {
            state.power[channel] += POWER_ALPHA * (instantDb - state.power[channel]);
        }
        return state.power[channel];
    }
}
