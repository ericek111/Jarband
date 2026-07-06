package eu.lixko.jarband.gui;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.HashMap;
import java.util.Map;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;

import eu.lixko.jsoapy.soapy.SoapySDRDevice;
import eu.lixko.jsoapy.soapy.SoapySDRDeviceDirection;
import eu.lixko.jsoapy.soapy.SoapySDRRange;

public class GainsPanel extends JPanel {
	
	protected final SoapySDRDevice device;
	protected final SoapySDRDeviceDirection direction;
	protected final long channel;
	
    protected static final double SLIDER_SCALER = 1000d;
	
	protected HashMap<String, JSlider> sliders = new HashMap<>();
	protected HashMap<String, JLabel> gainLabels = new HashMap<>();
	
	public GainsPanel(SoapySDRDevice device, SoapySDRDeviceDirection direction, long channel) {
		super (new GridBagLayout());
		
		this.device = device;
		this.direction = direction;
		this.channel = channel;
				
		GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.insets = new Insets(0,0,15,0);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        // this.add(new JLabel("Gains"), constraints);
        
        constraints.insets = new Insets(0, 0, 0, 0);
        constraints.gridy++;

        for (String gainName : this.device.listGains(direction, channel)) {
            double currentVal = this.device.getGain(direction, channel, gainName);
            SoapySDRRange range = this.device.getGainRange(direction, channel, gainName);
            System.err.println(gainName + " : " + range.getMinimum() + ", " + range.getMaximum() + " | " + currentVal);
            currentVal = Math.clamp(currentVal, range.getMinimum(), range.getMaximum());
	        JSlider gainSlider = new JSlider(JSlider.HORIZONTAL, (int) (range.getMinimum() * SLIDER_SCALER), (int) (range.getMaximum() * SLIDER_SCALER), (int) (currentVal * SLIDER_SCALER));
	        JLabel gainLabel = new JLabel();
	    	
	        this.add(new JLabel(gainName + " Gain: "), constraints);
	        gainLabel.setHorizontalAlignment(SwingConstants.RIGHT);
	        this.add(gainLabel, constraints);
	        constraints.gridy++;
	

	        this.add(gainSlider, constraints);
	        constraints.gridy++;
	        
	        
	        sliders.put(gainName, gainSlider);
	        gainLabels.put(gainName, gainLabel);
	        
	        gainSlider.addChangeListener((ChangeEvent e) -> {
	        	double gainVal = (double) gainSlider.getValue() / SLIDER_SCALER;
	        	device.setGain(direction, channel, gainName, gainVal);
	        	updateGainSliders();
	        });
        }
        
        updateGainSliders();
	}
	
	protected void updateGainSliders() {
		for (Map.Entry<String, JSlider> entry : sliders.entrySet()) {
			// if (device.getGainMode(direction, channel))
			double gain = device.getGain(direction, channel, entry.getKey());
			entry.getValue().setValue((int) (gain * SLIDER_SCALER));
			gainLabels.get(entry.getKey()).setText(gain + "");
		}
	}
	
	
}
