package eu.lixko.jarband.fft;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jsoapy.util.NativeUtils.ApiMethod;


public class Volk {




	static {
		System.loadLibrary("volk");
	}

	private static ApiMethod volk_32fc_32f_multiply_32fc = new ApiMethod(1, "volk_32fc_32f_multiply_32fc", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    public static void volk_32fc_32f_multiply_32fc(MemorySegment cVector, MemorySegment aVector, MemorySegment bVector, int num_points) {
    	try {
			volk_32fc_32f_multiply_32fc.HANDLE.invokeExact(cVector, aVector, bVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

	private static ApiMethod volk_32fc_s32f_power_spectrum_32f = new ApiMethod(1, "volk_32fc_s32f_power_spectrum_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_INT
    ));
    public static void volk_32fc_s32f_power_spectrum_32f(MemorySegment logPowerOutput, MemorySegment complexFFTInput, float normalizationFactor, int num_points) {
    	try {
			volk_32fc_s32f_power_spectrum_32f.HANDLE.invokeExact(logPowerOutput, complexFFTInput, normalizationFactor, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

	private static ApiMethod volk_16u_byteswap_get_func_desc = new ApiMethod(1, "volk_16u_byteswap_get_func_desc", FunctionDescriptor.of(
        ValueLayout.ADDRESS
    ));
    public static MemorySegment volk_16u_byteswap_get_func_desc() {
    	try {
			return (MemorySegment) volk_16u_byteswap_get_func_desc.HANDLE.invokeExact();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

	private static ApiMethod volk_16u_byteswap = new ApiMethod(1, "volk_16u_byteswap", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    public static void volk_16u_byteswap(MemorySegment intsToSwap, int num_points) {
    	try {
			volk_16u_byteswap.HANDLE.invokeExact(intsToSwap, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

	private static ApiMethod volk_32fc_s32fc_x2_rotator2_32fc = new ApiMethod(1, "volk_32fc_s32fc_x2_rotator2_32fc", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT

    ));
    public static void volk_32fc_s32fc_x2_rotator2_32fc(MemorySegment outVector, MemorySegment inVector, MemorySegment phase_inc, MemorySegment phase, int num_points) {
    	try {
			volk_32fc_s32fc_x2_rotator2_32fc.HANDLE.invokeExact(outVector, inVector, phase_inc, phase, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

	private static ApiMethod volk_32fc_magnitude_squared_32f = new ApiMethod(1, "volk_32fc_magnitude_squared_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    public static void volk_32fc_magnitude_squared_32f(MemorySegment magnitudeVector, MemorySegment complexVector, int num_points) {
    	try {
			volk_32fc_magnitude_squared_32f.HANDLE.invokeExact(magnitudeVector, complexVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

	private static ApiMethod volk_32fc_magnitude_32f = new ApiMethod(1, "volk_32fc_magnitude_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    public static void volk_32fc_magnitude_32f(MemorySegment magnitudeVector, MemorySegment complexVector, int num_points) {
    	try {
			volk_32fc_magnitude_32f.HANDLE.invokeExact(magnitudeVector, complexVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32f_s32f_multiply_32f = new ApiMethod(1, "volk_32f_s32f_multiply_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_INT
    ));
    /**
     * Multiply real vector by scalar: cVector[i] = aVector[i] * scalar
     */
    public static void volk_32f_s32f_multiply_32f(MemorySegment cVector, MemorySegment aVector, float scalar, int num_points) {
        try {
			volk_32f_s32f_multiply_32f.HANDLE.invokeExact(cVector, aVector, scalar, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32fc_deinterleave_real_32f = new ApiMethod(1, "volk_32fc_deinterleave_real_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Extract real part from complex vector: iBuffer[i] = complexVector[i].real
     */
    public static void volk_32fc_deinterleave_real_32f(MemorySegment iBuffer, MemorySegment complexVector, int num_points) {
        try {
			volk_32fc_deinterleave_real_32f.HANDLE.invokeExact(iBuffer, complexVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32f_x2_interleave_32fc = new ApiMethod(1, "volk_32f_x2_interleave_32fc", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Interleave two real vectors into complex: complexVector[i] = (iBuffer[i], qBuffer[i])
     * Also useful for stereo: stereo[i] = (left[i], right[i])
     */
    public static void volk_32f_x2_interleave_32fc(MemorySegment complexVector, MemorySegment iBuffer, MemorySegment qBuffer, int num_points) {
        try {
			volk_32f_x2_interleave_32fc.HANDLE.invokeExact(complexVector, iBuffer, qBuffer, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32fc_deinterleave_32f_x2 = new ApiMethod(1, "volk_32fc_deinterleave_32f_x2", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Deinterleave complex to two real vectors: iBuffer[i] = complexVector[i].real, qBuffer[i] = complexVector[i].imag
     * Also useful for stereo: left[i] = stereo[i*2], right[i] = stereo[i*2+1]
     */
    public static void volk_32fc_deinterleave_32f_x2(MemorySegment iBuffer, MemorySegment qBuffer, MemorySegment complexVector, int num_points) {
        try {
			volk_32fc_deinterleave_32f_x2.HANDLE.invokeExact(iBuffer, qBuffer, complexVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32f_x2_add_32f = new ApiMethod(1, "volk_32f_x2_add_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Add two real vectors: cVector[i] = aVector[i] + bVector[i]
     */
    public static void volk_32f_x2_add_32f(MemorySegment cVector, MemorySegment aVector, MemorySegment bVector, int num_points) {
        try {
			volk_32f_x2_add_32f.HANDLE.invokeExact(cVector, aVector, bVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32f_x2_subtract_32f = new ApiMethod(1, "volk_32f_x2_subtract_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Subtract two real vectors: cVector[i] = aVector[i] - bVector[i]
     */
    public static void volk_32f_x2_subtract_32f(MemorySegment cVector, MemorySegment aVector, MemorySegment bVector, int num_points) {
        try {
			volk_32f_x2_subtract_32f.HANDLE.invokeExact(cVector, aVector, bVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32f_x2_multiply_32f = new ApiMethod(1, "volk_32f_x2_multiply_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Multiply two real vectors element-wise: cVector[i] = aVector[i] * bVector[i]
     */
    public static void volk_32f_x2_multiply_32f(MemorySegment cVector, MemorySegment aVector, MemorySegment bVector, int num_points) {
        try {
			volk_32f_x2_multiply_32f.HANDLE.invokeExact(cVector, aVector, bVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32f_sqrt_32f = new ApiMethod(1, "volk_32f_sqrt_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Square root of real vector: cVector[i] = sqrt(aVector[i])
     */
    public static void volk_32f_sqrt_32f(MemorySegment cVector, MemorySegment aVector, int num_points) {
        try {
			volk_32f_sqrt_32f.HANDLE.invokeExact(cVector, aVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32f_accumulator_s32f = new ApiMethod(1, "volk_32f_accumulator_s32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Sum all elements: *result = sum(aVector[i])
     */
    public static void volk_32f_accumulator_s32f(MemorySegment result, MemorySegment aVector, int num_points) {
        try {
			volk_32f_accumulator_s32f.HANDLE.invokeExact(result, aVector, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
    }

    private static ApiMethod volk_32f_x2_dot_prod_32f = new ApiMethod(1, "volk_32f_x2_dot_prod_32f", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Compute dot product: *result = sum(input[i] * taps[i])
     */
    public static void volk_32f_x2_dot_prod_32f(MemorySegment result, MemorySegment input, MemorySegment taps, int num_points) {
        try {
			volk_32f_x2_dot_prod_32f.HANDLE.invokeExact(result, input, taps, num_points);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
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
