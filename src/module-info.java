open module Jarband {
	requires java.desktop;
	requires jdk.incubator.vector;
	requires JSoapy;
	requires org.tomlj;
	exports eu.lixko.jarband.app;
	exports eu.lixko.jarband.capture;
	exports eu.lixko.jarband.dsp.airband;
	exports eu.lixko.jarband.dsp.channelizer;
	exports eu.lixko.jarband.dsp.vdl2;
	exports eu.lixko.jarband.recording;
}
