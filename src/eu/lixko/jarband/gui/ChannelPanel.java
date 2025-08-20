package eu.lixko.jarband.gui;

import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.HashMap;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultFormatter;

import eu.lixko.jarband.gui.SensorsPanel.ChannelSensors;
import eu.lixko.jsoapy.soapy.SoapySDRArgInfo;
import eu.lixko.jsoapy.soapy.SoapySDRDevice;
import eu.lixko.jsoapy.soapy.SoapySDRDeviceDirection;
import eu.lixko.jsoapy.soapy.SoapySDRRange;

public class ChannelPanel extends JPanel {
	
	protected final SoapySDRDevice device;
	protected final SoapySDRDeviceDirection direction;
	protected final long channel;
	
	protected HashMap<String, JSpinner> freqFields = new HashMap<>();
	
	public ChannelPanel(SoapySDRDevice device, SoapySDRDeviceDirection direction, long channel) {
		super (new GridBagLayout());
		
		this.device = device;
		this.direction = direction;
		this.channel = channel;
		this.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.insets = new Insets(0,0,15,0);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        
        constraints.insets = new Insets(0, 0, 0, 0);
        constraints.gridy++;
        
		
		this.add(new GainsPanel(device, direction, channel), constraints);
		constraints.gridy++;
		
        if (!device.listSensors(direction, channel).isEmpty()) {
			this.add(new ChannelSensors(device, direction, channel), constraints);
			constraints.gridy++;
		}
        
        for (String freqName : device.listFrequencies(direction, channel)) {
        	// List<SoapySDRArgInfo> info = device.getFrequencyArgsInfo(direction, channel, freqName);
        	// device.getFrequency(direction, channel, freqName);
        	List<SoapySDRRange> freqRange = device.getFrequencyRange(direction, channel, freqName);
        	double minFreq = Double.MIN_VALUE;
        	double maxFreq = Double.MAX_VALUE;
        	for (SoapySDRRange range : freqRange) {
        		minFreq = Math.max(minFreq, range.getMinimum());
        		maxFreq = Math.min(maxFreq, range.getMaximum());
        	}
        	
        	constraints.insets = new Insets(15, 0, 0, 0);
        	this.add(new JLabel(freqName + " Frequency [Hz]:"), constraints);
        	constraints.insets = new Insets(0, 0, 0, 0);
        	constraints.gridy++;
        	
        	FrequencySpinner spinner = FrequencySpinner.makeSpinner(device.getFrequency(direction, channel, freqName), minFreq, maxFreq);
            spinner.addChangeListener(event -> {
            	device.setFrequency(direction, channel, freqName, spinner.getFrequency());
            });
            
        	freqFields.put(freqName, spinner);
        	
        	this.add(spinner, constraints);
        	constraints.gridy++;
        }
        
        constraints.anchor = GridBagConstraints.SOUTH;
        constraints.fill = GridBagConstraints.VERTICAL;
        constraints.weighty = 1;
        this.add(Box.createVerticalGlue(), constraints);
        
        /* GridBagConstraints horizontalFill = new GridBagConstraints();
        horizontalFill.anchor = GridBagConstraints.SOUTH;
        horizontalFill.fill = GridBagConstraints.VERTICAL;
        horizontalFill.gridy = GridBagConstraints.REMAINDER;
        this.add(Box.createVerticalGlue(), horizontalFill); */
        
        // this.device.listGains(direction, channel);
		
	}
	
	protected static void freqSpinnerHandler(MouseEvent event) {
		
	}
	
	
}
