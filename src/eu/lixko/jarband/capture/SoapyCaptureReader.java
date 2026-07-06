package eu.lixko.jarband.capture;

import java.util.Map;
import java.util.concurrent.BlockingQueue;

import eu.lixko.jsoapy.soapy.SoapySDRDevice;
import eu.lixko.jsoapy.soapy.SoapySDRDeviceDirection;
import eu.lixko.jsoapy.soapy.SoapySDRStream;
import eu.lixko.jsoapy.soapy.StreamFormat;
import eu.lixko.jsoapy.util.NativeUtils;

public final class SoapyCaptureReader implements Runnable {
    private final Settings settings;
    private final BlockingQueue<NativeSampleBlock> output;
    private volatile boolean running = true;

    public SoapyCaptureReader(Settings settings, BlockingQueue<NativeSampleBlock> output) {
        this.settings = settings;
        this.output = output;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        NativeUtils.loadLibrary();
        SoapySDRDevice device = SoapySDRDevice.makeStrArgs(settings.args());
        device.setSampleRate(SoapySDRDeviceDirection.RX, 0, settings.sampleRateHz());
        device.setFrequency(SoapySDRDeviceDirection.RX, 0, settings.centerFrequencyHz());
        for (var gain : settings.gains().entrySet()) {
            device.setGain(SoapySDRDeviceDirection.RX, 0, gain.getKey(), gain.getValue());
        }

        SoapySDRStream stream = device.setupStream(SoapySDRDeviceDirection.RX, StreamFormat.CF32, null, null);
        stream.activateStream();
        long firstSample = 0;
        try {
            while (running) {
                int read = stream.readStream(1_000_000);
                if (read <= 0) {
                    continue;
                }
                output.put(new NativeSampleBlock(stream.getNormalBuffer(0), read, firstSample, System.nanoTime()));
                firstSample += read;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            output.offer(NativeSampleBlock.POISON);
        }
    }

    public record Settings(String args, double sampleRateHz, double centerFrequencyHz, Map<String, Double> gains) {}
}
