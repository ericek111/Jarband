package eu.lixko.jarband.dsp.airband;

public final class ChannelStateArrays {
    public static final byte CLOSED = 0;
    public static final byte OPEN = 1;

    public final float[] power;
    public final float[] noiseFloor;
    public final float[] agcGain;
    public final byte[] squelchState;
    public final int[] noiseSamples;
    public final int[] openSamples;
    public final long[] utteranceStartMillis;
    public final int[] opusOffset;
    public final long[] lastOpenMillis;

    public ChannelStateArrays(int channels) {
        this.power = new float[channels];
        this.noiseFloor = new float[channels];
        this.agcGain = new float[channels];
        this.squelchState = new byte[channels];
        this.noiseSamples = new int[channels];
        this.openSamples = new int[channels];
        this.utteranceStartMillis = new long[channels];
        this.opusOffset = new int[channels];
        this.lastOpenMillis = new long[channels];
        java.util.Arrays.fill(power, Float.NaN);
        java.util.Arrays.fill(noiseFloor, Float.NaN);
        java.util.Arrays.fill(agcGain, 1.0f);
    }
}
