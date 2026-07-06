package eu.lixko.jarband.dsp.vdl2;

public interface Vdl2SymbolSink {
    void accept(int frequencyHz, long unixMillis, int symbolBits,
                float framePowerDbfs, float noiseFloorDbfs, float ppmError, boolean burstStart);
}
