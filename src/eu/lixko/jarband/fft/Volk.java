package eu.lixko.jarband.fft;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jsoapy.util.NativeUtils;
import eu.lixko.jsoapy.util.NativeUtils.ApiMethod;


public class Volk {
	

	
	
	static {
		System.loadLibrary("volk");
	}
	
	public static ApiMethod volk_32fc_32f_multiply_32fc = new ApiMethod(1, "volk_32fc_32f_multiply_32fc", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    public static void volk_32fc_32f_multiply_32fc(MemorySegment cVector, MemorySegment aVector, MemorySegment bVector, int num_points) {
    	NativeUtils.call(volk_32fc_32f_multiply_32fc, cVector, aVector, bVector, num_points);	
    }
    
	public static ApiMethod volk_32fc_s32f_power_spectrum_32f = new ApiMethod(1, "volk_32fc_s32f_power_spectrum_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_INT
    ));
    public static void volk_32fc_s32f_power_spectrum_32f(MemorySegment logPowerOutput, MemorySegment complexFFTInput, float normalizationFactor, int num_points) {
    	NativeUtils.call(volk_32fc_s32f_power_spectrum_32f, logPowerOutput, complexFFTInput, normalizationFactor, num_points);
    }
    
	public static ApiMethod volk_16u_byteswap_get_func_desc = new ApiMethod(1, "volk_16u_byteswap_get_func_desc", FunctionDescriptor.of(
        ValueLayout.ADDRESS
    ));
    public static void volk_16u_byteswap_get_func_desc(MemorySegment logPowerOutput, MemorySegment complexFFTInput, float normalizationFactor, int num_points) {
    	NativeUtils.call(volk_16u_byteswap_get_func_desc, logPowerOutput, complexFFTInput, normalizationFactor, num_points);
    }
    
	public static ApiMethod volk_16u_byteswap = new ApiMethod(1, "volk_16u_byteswap", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    public static void volk_16u_byteswap(MemorySegment intsToSwap, int num_points) {
    	NativeUtils.call(volk_16u_byteswap, intsToSwap, num_points);
    }
    
	public static ApiMethod volk_32fc_s32fc_x2_rotator2_32fc = new ApiMethod(1, "volk_32fc_s32fc_x2_rotator2_32fc", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
        
    ));
    public static void volk_32fc_s32fc_x2_rotator2_32fc(MemorySegment outVector, MemorySegment inVector, MemorySegment phase_inc, MemorySegment phase, int num_points) {
    	NativeUtils.call(volk_32fc_s32fc_x2_rotator2_32fc, outVector, inVector, phase_inc, phase, num_points);
    }
    
	/**
	VOLK's lv_32fc_t type should be, according to LuaRadio, binary compatible with this (which would imply that its typedef origin `std::complex<float>`, is also this):
	
		typedef struct complex_float32 {
		    float real;
		    float imag;
		} complex_float32_t;
	*/
    public static class lv_32fc_t {
    	public final MemorySegment data;
    	
    	public lv_32fc_t(float real, float imag) {
    		// data = Arena.ofAuto().allocate(ValueLayout.JAVA_FLOAT, (int) 2); // NoSuchMethodError ?????????
    		data = Arena.ofAuto().allocate(Float.BYTES * 2);
    		data.setAtIndex(ValueLayout.JAVA_FLOAT, 0, real);
    		data.setAtIndex(ValueLayout.JAVA_FLOAT, 1, imag);
    	}
    	
    	public float real() {
    		return this.data.getAtIndex(ValueLayout.JAVA_FLOAT, 0);
    	}
    	
    	public float imag() {
    		return this.data.getAtIndex(ValueLayout.JAVA_FLOAT, 1);
    	}
    	
    	public MemorySegment address() {
    		return this.data;
    	}
    }
    
    
	    
}
