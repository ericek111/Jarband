package eu.lixko.jarband.gui;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import eu.lixko.jsoapy.soapy.SoapySDRArgInfo;
import eu.lixko.jsoapy.soapy.SoapySDRDevice;
import eu.lixko.jsoapy.soapy.SoapySDRDeviceDirection;

public abstract class SensorsPanel extends JPanel {
	protected final SoapySDRDevice device;
	
	protected HashMap<String, JLabel> valFields = new HashMap<>();
	
	public SensorsPanel(SoapySDRDevice device) {
		super (new GridBagLayout());
		
		this.device = device;
		
		this.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		
		GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 4;
        constraints.insets = new Insets(0,0,15,0);
        this.add(new JLabel("Device Sensors"), constraints);
        
        constraints.insets = new Insets(0, 5, 0, 5);
        constraints.gridy++;
        constraints.gridwidth = 1;
        
        constraints.gridx = GridBagConstraints.RELATIVE;
        
        for (String sensorName : this.listSensors()) {
        	SoapySDRArgInfo sensorInfo = this.getSensorInfo(sensorName);
        	
            constraints.gridy++;
            
            constraints.anchor = GridBagConstraints.EAST;
            JLabel label = new JLabel(sensorInfo.name.isEmpty() ? sensorName : sensorInfo.name);
            this.add(label, constraints);
     
            constraints.anchor = GridBagConstraints.WEST;
            JLabel varLabel = new JLabel();
            if (!sensorInfo.description.isEmpty()) {
            	varLabel.setToolTipText(sensorInfo.description);
            }
            valFields.put(sensorName, varLabel);
            
            this.add(varLabel, constraints);
            this.add(new JLabel(sensorInfo.units), constraints);
        }

        this.updateValFields();
        
        javax.swing.Timer timer = new javax.swing.Timer(1000, e -> this.updateValFields());
        timer.start();

        this.validate();
	}
	
	public void updateValFields() {
		for (Map.Entry<String, JLabel> entry : valFields.entrySet()) {
			entry.getValue().setText(device.readSensor(entry.getKey()));
		}
	}
	
	public abstract List<String> listSensors();

	public abstract String readSensor(String key);
	
	public abstract SoapySDRArgInfo getSensorInfo(String key);
	
	protected static class DeviceSensors extends SensorsPanel {
		public DeviceSensors(SoapySDRDevice device) {
			super(device);
		}

		public List<String> listSensors() {
			return device.listSensors();
		}

		public String readSensor(String key) {
			return device.readSensor(key);
		}

		public SoapySDRArgInfo getSensorInfo(String key) {
			return device.getSensorInfo(key);
		}
	}

	protected static class ChannelSensors extends SensorsPanel {
		protected SoapySDRDeviceDirection direction;
		protected long channel;
		
		public ChannelSensors(SoapySDRDevice device, SoapySDRDeviceDirection direction, long channel) {
			super(device);
			this.direction = direction;
			this.channel = channel;
		}

		public List<String> listSensors() {
			return device.listSensors(direction, channel);
		}

		public String readSensor(String key) {
			return device.readSensor(direction, channel, key);
		}

		public SoapySDRArgInfo getSensorInfo(String key) {
			return device.getSensorInfo(direction, channel, key);
		}
		
	}
	
}

