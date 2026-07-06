open module Jarband {
	requires java.desktop;
	requires jdk.incubator.vector;
	requires JSoapy;
	exports eu.lixko.jarband.app;
	exports eu.lixko.jarband.capture;
	exports eu.lixko.jarband.dsp.airband;
	exports eu.lixko.jarband.dsp.channelizer;
	exports eu.lixko.jarband.recording;
}
