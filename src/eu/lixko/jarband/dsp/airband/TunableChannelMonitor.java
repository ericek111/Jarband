package eu.lixko.jarband.dsp.airband;

import java.awt.FlowLayout;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import eu.lixko.jarband.dsp.channelizer.ChannelizedFrame;
import eu.lixko.jarband.dsp.channelizer.LogicalChannel;

public final class TunableChannelMonitor implements AutoCloseable {
    private static final float AUDIO_RATE = 48_000.0f;
    private static final int AUDIO_BLOCK_SAMPLES = 480;

    private final List<LogicalChannel> channels;
    private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
    private final JComboBox<ChannelChoice> channelSelector;
    private final JComboBox<MixerChoice> mixerSelector;
    private final JToggleButton enabled = new JToggleButton("Monitor", true);
    private final AudioFormat format = new AudioFormat(AUDIO_RATE, 16, 1, true, false);
    private final Thread audioThread;
    private final byte[] blockPcm = new byte[AUDIO_BLOCK_SAMPLES * 2];
    private SourceDataLine line;
    private String lineStatus = "closed";
    private volatile boolean running = true;
    private volatile float latestAudio;
    private float envelopeDc;
    private float audioGain = 1.0f;
    private float audioThreadSample;
    private double tonePhase;
    private long outputSamples;
    private double audioSumSquares;
    private float audioPeak;
    private float selectedPowerDb = Float.NaN;

    public TunableChannelMonitor(List<LogicalChannel> channels, double inputRate) throws LineUnavailableException {
        this.channels = channels;
        this.channelSelector = new JComboBox<>(choices(channels));
        this.mixerSelector = new JComboBox<>(mixerChoices(format));
        printMixers();
        openSelectedMixer();
        mixerSelector.addActionListener(_ -> openSelectedMixer());
        this.audioThread = Thread.ofPlatform().name("jarband-audio-monitor").start(this::audioLoop);

        panel.add(new JLabel("Audio"));
        panel.add(channelSelector);
        panel.add(new JLabel("Output"));
        panel.add(mixerSelector);
        panel.add(enabled);
    }

    public JPanel panel() {
        return panel;
    }

    public void accept(ChannelizedFrame frame) {
        if (!enabled.isSelected()) {
            return;
        }
        ChannelChoice choice = (ChannelChoice) channelSelector.getSelectedItem();
        if (choice == null) {
            return;
        }
        if (choice.index() < 0) {
            return;
        }
        LogicalChannel channel = channels.get(choice.index());
        float i = 0.0f;
        float q = 0.0f;
        int[] bins = channel.pfbBins();
        for (int bin : bins) {
            i += frame.i(bin);
            q += frame.q(bin);
        }
        i /= bins.length;
        q /= bins.length;
        selectedPowerDb = 10.0f * (float) Math.log10(i * i + q * q + 1.0e-20f);
        latestAudio = envelopeAudio(i, q);
    }

    private float envelopeAudio(float i, float q) {
        float envelope = (float) Math.sqrt(i * i + q * q);
        envelopeDc += 0.0005f * (envelope - envelopeDc);
        float ac = envelope - envelopeDc;
        float targetGain = Math.min(200.0f, 0.2f / (Math.abs(ac) + 1.0e-4f));
        audioGain += 0.0005f * (targetGain - audioGain);
        return Math.max(-1.0f, Math.min(1.0f, ac * audioGain));
    }

    private void audioLoop() {
        while (running) {
            fillAudioBlock();
            SourceDataLine currentLine = line;
            if (currentLine != null && currentLine.isOpen()) {
                currentLine.write(blockPcm, 0, blockPcm.length);
                outputSamples += AUDIO_BLOCK_SAMPLES;
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void fillAudioBlock() {
        ChannelChoice choice = (ChannelChoice) channelSelector.getSelectedItem();
        for (int n = 0; n < AUDIO_BLOCK_SAMPLES; n++) {
            float target = enabled.isSelected() && choice != null
                    ? (choice.index() < 0 ? testTone() : latestAudio)
                    : 0.0f;
            audioThreadSample += 0.05f * (target - audioThreadSample);
            audioSumSquares += audioThreadSample * audioThreadSample;
            audioPeak = Math.max(audioPeak, Math.abs(audioThreadSample));
            short value = (short) Math.round(Math.max(-1.0f, Math.min(1.0f, audioThreadSample)) * 32767.0f);
            int idx = n * 2;
            blockPcm[idx] = (byte) (value & 0xff);
            blockPcm[idx + 1] = (byte) ((value >>> 8) & 0xff);
        }
    }

    public String statusAndReset() {
        double rms = outputSamples == 0 ? 0.0 : Math.sqrt(audioSumSquares / outputSamples);
        String status = String.format(java.util.Locale.ROOT,
                "monitor %s [%s] power %.1f dB audio rms %.4f peak %.4f out %,d/s",
                channelSelector.getSelectedItem(),
                lineStatus,
                selectedPowerDb,
                rms,
                audioPeak,
                outputSamples);
        outputSamples = 0;
        audioSumSquares = 0.0;
        audioPeak = 0.0f;
        return status;
    }

    private void openSelectedMixer() {
        closeLine();
        MixerChoice choice = (MixerChoice) mixerSelector.getSelectedItem();
        try {
            line = choice == null || choice.info() == null
                    ? AudioSystem.getSourceDataLine(format)
                    : (SourceDataLine) AudioSystem.getMixer(choice.info()).getLine(new DataLine.Info(SourceDataLine.class, format));
            line.open(format, 16_384);
            line.start();
            lineStatus = "open " + (choice == null ? "default" : choice);
            System.out.println("Opened audio output: " + lineStatus);
        } catch (Exception e) {
            line = null;
            lineStatus = "failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("Failed to open audio output " + choice + ": " + e);
        }
    }

    private void closeLine() {
        SourceDataLine currentLine = line;
        if (currentLine != null) {
            currentLine.stop();
            currentLine.close();
        }
        line = null;
    }

    private float testTone() {
        tonePhase += 2.0 * Math.PI * 440.0 / AUDIO_RATE;
        if (tonePhase > 2.0 * Math.PI) {
            tonePhase -= 2.0 * Math.PI;
        }
        return 0.2f * (float) Math.sin(tonePhase);
    }

    private static ChannelChoice[] choices(List<LogicalChannel> channels) {
        ChannelChoice[] choices = new ChannelChoice[channels.size() + 1];
        choices[0] = new ChannelChoice(-1, 0.0);
        for (int i = 0; i < channels.size(); i++) {
            choices[i + 1] = new ChannelChoice(i, channels.get(i).frequencyHz());
        }
        return choices;
    }

    private static MixerChoice[] mixerChoices(AudioFormat format) {
        java.util.ArrayList<MixerChoice> choices = new java.util.ArrayList<>();
        choices.add(new MixerChoice(null));
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (mixer.isLineSupported(lineInfo)) {
                choices.add(new MixerChoice(info));
            }
        }
        return choices.toArray(MixerChoice[]::new);
    }

    private static void printMixers() {
        System.out.println("Java Sound mixers:");
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        if (infos.length == 0) {
            System.out.println("  <none>");
            return;
        }
        for (Mixer.Info info : infos) {
            System.out.printf("  %s / %s / %s%n", info.getName(), info.getVendor(), info.getDescription());
        }
    }

    @Override
    public void close() {
        running = false;
        audioThread.interrupt();
        try {
            audioThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        SourceDataLine currentLine = line;
        if (currentLine != null) {
            currentLine.drain();
        }
        closeLine();
    }

    private record ChannelChoice(int index, double frequencyHz) {
        @Override
        public String toString() {
            if (index < 0) {
                return "440 Hz test tone";
            }
            return String.format(java.util.Locale.ROOT, "%.6f MHz", frequencyHz / 1_000_000.0);
        }
    }

    private record MixerChoice(Mixer.Info info) {
        @Override
        public String toString() {
            return info == null ? "Default" : info.getName();
        }
    }
}
