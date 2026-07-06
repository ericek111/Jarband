package eu.lixko.jarband.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import eu.lixko.jarband.dsp.airband.ChannelStateArrays;
import eu.lixko.jarband.dsp.channelizer.ChannelPlan;
import eu.lixko.jarband.dsp.channelizer.LogicalChannel;
import eu.lixko.jarband.dsp.channelizer.PfbConfig;

@SuppressWarnings("serial")
public final class ChannelActivityPanel extends JPanel {
    private static final Color ACTIVE = new Color(0x19A64A);
    private static final Color INACTIVE = new Color(0xA51E28);
    private static final Color GRID = new Color(0x303030);

    private final ChannelPlan plan;
    private final PfbConfig pfb;
    private final ChannelStateArrays state;
    private final WaterfallPanel waterfall;
    private final JLabel status;
    private volatile LogicalChannel selectedChannel;

    public ChannelActivityPanel(ChannelPlan plan, PfbConfig pfb, ChannelStateArrays state,
                                WaterfallPanel waterfall, JLabel status) {
        this.plan = plan;
        this.pfb = pfb;
        this.state = state;
        this.waterfall = waterfall;
        this.status = status;
        this.selectedChannel = plan.channels().isEmpty() ? null : plan.channels().getFirst();
        setPreferredSize(new Dimension(1800, 14));
        setMinimumSize(new Dimension(64, 10));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showStatusForClick(e.getX());
            }
        });
        new Timer(100, _ -> repaint()).start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int channels = plan.channels().size();
        if (channels == 0) {
            return;
        }

        int width = getWidth();
        int height = getHeight();
        double visibleLow = visibleLowHz();
        double hzPerPixel = visibleBandwidthHz() / Math.max(1, width);
        for (LogicalChannel channel : plan.channels()) {
            int x0 = (int) Math.floor((channel.frequencyHz() - visibleLow - hzPerPixel * 0.5) / hzPerPixel);
            int x1 = (int) Math.ceil((channel.frequencyHz() - visibleLow + hzPerPixel * 0.5) / hzPerPixel);
            if (x1 < 0 || x0 >= width) {
                continue;
            }
            x0 = Math.max(0, x0);
            x1 = Math.min(width, x1);
            g.setColor(state.squelchState[channel.id()] == ChannelStateArrays.OPEN ? ACTIVE : INACTIVE);
            g.fillRect(x0, 0, Math.max(1, x1 - x0), height);
            if (x1 - x0 > 3) {
                g.setColor(GRID);
                g.drawLine(x1 - 1, 0, x1 - 1, height);
            }
        }
    }

    private void showStatusForClick(int x) {
        LogicalChannel channel = channelAt(x);
        if (channel == null) {
            status.setText("No channel at cursor");
            return;
        }
        int id = channel.id();
        selectedChannel = channel;
        float margin = Float.isFinite(state.power[id]) && Float.isFinite(state.noiseFloor[id])
                ? state.power[id] - state.noiseFloor[id]
                : Float.NaN;
        int bin = channel.pfbBins()[channel.pfbBins().length / 2];
        status.setText(String.format(java.util.Locale.ROOT,
                "%.6f MHz  bin %d (%.6f MHz)  %s  power %.1f dB  noise %.1f dB  SNR %.1f dB",
                channel.frequencyHz() / 1_000_000.0,
                bin,
                ChannelPlan.frequencyForBin(pfb, bin) / 1_000_000.0,
                state.squelchState[id] == ChannelStateArrays.OPEN ? "OPEN" : "closed",
                state.power[id],
                state.noiseFloor[id],
                margin));
    }

    public LogicalChannel selectedChannel() {
        return selectedChannel;
    }

    private LogicalChannel channelAt(int x) {
        double frequency = visibleLowHz() + visibleBandwidthHz() * x / Math.max(1, getWidth());
        LogicalChannel best = null;
        double bestError = Double.POSITIVE_INFINITY;
        double maxError = Math.max(pfb.branchSpacingHz(), visibleBandwidthHz() / Math.max(1, getWidth()));
        for (LogicalChannel channel : plan.channels()) {
            double error = Math.abs(channel.frequencyHz() - frequency);
            if (error < bestError) {
                best = channel;
                bestError = error;
            }
        }
        return bestError <= maxError ? best : null;
    }

    private double visibleLowHz() {
        double zoom = waterfall.getRealZoomFactor();
        double offset = waterfall.getViewOffset();
        double startFraction = ((offset + 1.0) * 0.5) - zoom * 0.5;
        return pfb.centerFrequency() - pfb.sampleRate() * 0.5 + startFraction * pfb.sampleRate();
    }

    private double visibleBandwidthHz() {
        return pfb.sampleRate() * waterfall.getRealZoomFactor();
    }
}
