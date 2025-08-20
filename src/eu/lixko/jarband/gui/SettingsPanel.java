package eu.lixko.jarband.gui;

import javax.swing.JTabbedPane;

import eu.lixko.jarband.gui.SensorsPanel.DeviceSensors;
import eu.lixko.jsoapy.soapy.SoapySDRDevice;
import eu.lixko.jsoapy.soapy.SoapySDRDeviceDirection;

@SuppressWarnings("serial")
public class SettingsPanel extends JTabbedPane {

	protected final SoapySDRDevice device;

	public SettingsPanel(SoapySDRDevice device) {
		super();
		
		this.device = device;
		
		if (!device.listSensors().isEmpty())
			this.addTab("Device", new DeviceSensors(device));
		
		addDirTab(SoapySDRDeviceDirection.RX);
		addDirTab(SoapySDRDeviceDirection.TX);
		
		// this.setSelectedIndex(1);
	}
	
	private void addDirTab(SoapySDRDeviceDirection dir) {
		for (int i = 0; i < device.getNumChannels(dir); i++) {
			this.addTab(dir.name() + i, new ChannelPanel(device, dir, i));
		}
	}
	
}
