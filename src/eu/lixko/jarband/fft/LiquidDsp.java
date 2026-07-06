package eu.lixko.jarband.fft;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jsoapy.util.NativeUtils;
import eu.lixko.jsoapy.util.NativeUtils.ApiMethod;

/**
 * Java bindings for the Liquid DSP library.
 * Provides access to filters, resamplers, demodulators, and other DSP primitives.
 */
public class LiquidDsp {
	static {
		System.loadLibrary("liquid");
	}

	// ========== Liquid DSP constants ==========
	public static final int LIQUID_FIRFILT_UNKNOWN = 0;
	public static final int LIQUID_FIRFILT_KAISER = 1;
	public static final int LIQUID_FIRFILT_PM = 2;
	public static final int LIQUID_FIRFILT_RCOS = 3;
	public static final int LIQUID_FIRFILT_FEXP = 4;
	public static final int LIQUID_FIRFILT_FSECH = 5;
	public static final int LIQUID_FIRFILT_FARCSECH = 6;
	public static final int LIQUID_FIRFILT_ARKAISER = 7;
	public static final int LIQUID_FIRFILT_RKAISER = 8;
	public static final int LIQUID_FIRFILT_RRC = 9;
	public static final int LIQUID_FIRFILT_hM3 = 10;
	public static final int LIQUID_FIRFILT_GMSKTX = 11;
	public static final int LIQUID_FIRFILT_GMSKRX = 12;
	public static final int LIQUID_FIRFILT_RFEXP = 13;
	public static final int LIQUID_FIRFILT_RFSECH = 14;
	public static final int LIQUID_FIRFILT_RFARCSECH = 15;
	
	public static final int LIQUID_AMPMODEM_DSB = 0;
	public static final int LIQUID_AMPMODEM_USB = 1;
	public static final int LIQUID_AMPMODEM_LSB = 2;

	public static final int LIQUID_ANALYZER = 0;
	public static final int LIQUID_SYNTHESIZER = 1;
	
    private static ApiMethod liquid_firdes_kaiser = new ApiMethod("liquid_firdes_kaiser", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.ADDRESS
    ));
    public static int liquid_firdes_kaiser(MemorySegment coeffs, float _fc, float _as, float _mu) {
        return (int) NativeUtils.call(liquid_firdes_kaiser, (int) (coeffs.byteSize() / Float.BYTES), _fc, _as, _mu, coeffs);
    }
    
    private static ApiMethod firpfbch_crcf_create = new ApiMethod("firpfbch_crcf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static MemorySegment firpfbch_crcf_create(int _type, int _M, int _p, MemorySegment _h) {
        return (MemorySegment) NativeUtils.call(firpfbch_crcf_create, _type, _M, _p, _h);
    }

    private static ApiMethod firpfbch_crcf_create_kaiser = new ApiMethod("firpfbch_crcf_create_kaiser", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_FLOAT
    ));
    public static MemorySegment firpfbch_crcf_create_kaiser(int _type, int _M, int _m, float _As) {
        return (MemorySegment) NativeUtils.call(firpfbch_crcf_create_kaiser, _type, _M, _m, _As);
    }
    
    private static ApiMethod firpfbch_crcf_synthesizer_execute = new ApiMethod("firpfbch_crcf_synthesizer_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch_crcf_synthesizer_execute(MemorySegment _q, MemorySegment _x, MemorySegment _y) {
    	try {
			return (int) firpfbch_crcf_synthesizer_execute.HANDLE.invokeExact(_q, _x, _y);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod firpfbch_crcf_analyzer_execute = new ApiMethod("firpfbch_crcf_analyzer_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch_crcf_analyzer_execute(MemorySegment _q, MemorySegment _x, MemorySegment _y) {
    	try {
			return (int) firpfbch_crcf_analyzer_execute.HANDLE.invokeExact(_q, _x, _y);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod firpfbch_crcf_destroy = new ApiMethod("firpfbch_crcf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch_crcf_destroy(MemorySegment _q) {
        return (int) NativeUtils.call(firpfbch_crcf_destroy, _q);
    }

    private static ApiMethod firpfbch_crcf_reset = new ApiMethod("firpfbch_crcf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch_crcf_reset(MemorySegment _q) {
        return (int) NativeUtils.call(firpfbch_crcf_reset, _q);
    }

    private static ApiMethod firpfbch2_crcf_create = new ApiMethod("firpfbch2_crcf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static MemorySegment firpfbch2_crcf_create(int _type, int _M, int _m, MemorySegment _h) {
        return (MemorySegment) NativeUtils.call(firpfbch2_crcf_create, _type, _M, _m, _h);
    }

    private static ApiMethod firpfbch2_crcf_create_kaiser = new ApiMethod("firpfbch2_crcf_create_kaiser", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_FLOAT
    ));
    public static MemorySegment firpfbch2_crcf_create_kaiser(int _type, int _M, int _m, float _As) {
        return (MemorySegment) NativeUtils.call(firpfbch2_crcf_create_kaiser, _type, _M, _m, _As);
    }

    private static ApiMethod firpfbch2_crcf_copy = new ApiMethod("firpfbch2_crcf_copy", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    public static MemorySegment firpfbch2_crcf_copy(MemorySegment _q) {
        return (MemorySegment) NativeUtils.call(firpfbch2_crcf_copy, _q);
    }

    private static ApiMethod firpfbch2_crcf_destroy = new ApiMethod("firpfbch2_crcf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch2_crcf_destroy(MemorySegment _q) {
        return (int) NativeUtils.call(firpfbch2_crcf_destroy, _q);
    }

    private static ApiMethod firpfbch2_crcf_reset = new ApiMethod("firpfbch2_crcf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch2_crcf_reset(MemorySegment _q) {
        return (int) NativeUtils.call(firpfbch2_crcf_reset, _q);
    }

    private static ApiMethod firpfbch2_crcf_print = new ApiMethod("firpfbch2_crcf_print", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch2_crcf_print(MemorySegment _q) {
        return (int) NativeUtils.call(firpfbch2_crcf_print, _q);
    }

    private static ApiMethod firpfbch2_crcf_get_type = new ApiMethod("firpfbch2_crcf_get_type", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch2_crcf_get_type(MemorySegment _q) {
        return (int) NativeUtils.call(firpfbch2_crcf_get_type, _q);
    }

    private static ApiMethod firpfbch2_crcf_get_M = new ApiMethod("firpfbch2_crcf_get_M", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch2_crcf_get_M(MemorySegment _q) {
        return (int) NativeUtils.call(firpfbch2_crcf_get_M, _q);
    }

    private static ApiMethod firpfbch2_crcf_get_m = new ApiMethod("firpfbch2_crcf_get_m", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch2_crcf_get_m(MemorySegment _q) {
        return (int) NativeUtils.call(firpfbch2_crcf_get_m, _q);
    }

    private static ApiMethod firpfbch2_crcf_execute = new ApiMethod("firpfbch2_crcf_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    public static int firpfbch2_crcf_execute(MemorySegment _q, MemorySegment _x, MemorySegment _y) {
    	try {
			return (int) firpfbch2_crcf_execute.HANDLE.invokeExact(_q, _x, _y);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    // ========== FIR Filter (firfilt_crcf) - Complex input, complex output ==========

    private static ApiMethod firfilt_crcf_create_kaiser = new ApiMethod("firfilt_crcf_create_kaiser", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT
    ));
    /**
     * Create a FIR filter using Kaiser window.
     * @param n filter length
     * @param fc cutoff frequency (normalized, 0 < fc < 0.5)
     * @param As stop-band attenuation in dB
     * @param mu fractional sample delay
     * @return filter object
     */
    public static MemorySegment firfilt_crcf_create_kaiser(int n, float fc, float As, float mu) {
        return (MemorySegment) NativeUtils.call(firfilt_crcf_create_kaiser, n, fc, As, mu);
    }

    private static ApiMethod firfilt_crcf_destroy = new ApiMethod("firfilt_crcf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firfilt_crcf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(firfilt_crcf_destroy, q);
    }

    private static ApiMethod firfilt_crcf_reset = new ApiMethod("firfilt_crcf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firfilt_crcf_reset(MemorySegment q) {
        return (int) NativeUtils.call(firfilt_crcf_reset, q);
    }

    private static ApiMethod firfilt_crcf_execute = new ApiMethod("firfilt_crcf_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    /**
     * Execute FIR filter on a single complex sample.
     * @param q filter object
     * @param x input sample (complex float, 2 floats)
     * @param y output sample (complex float, 2 floats)
     */
    public static int firfilt_crcf_execute(MemorySegment q, MemorySegment x, MemorySegment y) {
    	try {
			return (int) firfilt_crcf_execute.HANDLE.invokeExact(q, x, y);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod firfilt_crcf_execute_block = new ApiMethod("firfilt_crcf_execute_block", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    /**
     * Execute FIR filter on a block of complex samples.
     * @param q filter object
     * @param x input samples (complex float array)
     * @param n number of samples
     * @param y output samples (complex float array)
     */
    public static int firfilt_crcf_execute_block(MemorySegment q, MemorySegment x, int n, MemorySegment y) {
    	try {
			return (int) firfilt_crcf_execute_block.HANDLE.invokeExact(q, x, n, y);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod firfilt_crcf_set_scale = new ApiMethod("firfilt_crcf_set_scale", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    /**
     * Set the output scaling for the filter.
     * @param q filter object
     * @param scale complex scale factor (2 floats: real, imag)
     */
    public static int firfilt_crcf_set_scale(MemorySegment q, MemorySegment scale) {
        return (int) NativeUtils.call(firfilt_crcf_set_scale, q, scale);
    }

    // ========== FIR Decimator (firdecim_crcf) - Complex input, complex output ==========

    private static ApiMethod firdecim_crcf_create = new ApiMethod("firdecim_crcf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    public static MemorySegment firdecim_crcf_create(int m, MemorySegment h, int hLen) {
        return (MemorySegment) NativeUtils.call(firdecim_crcf_create, m, h, hLen);
    }

    private static ApiMethod firdecim_crcf_destroy = new ApiMethod("firdecim_crcf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firdecim_crcf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(firdecim_crcf_destroy, q);
    }

    private static ApiMethod firdecim_crcf_execute_block = new ApiMethod("firdecim_crcf_execute_block", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firdecim_crcf_execute_block(MemorySegment q, MemorySegment x, int n, MemorySegment y) {
        try {
            return (int) firdecim_crcf_execute_block.HANDLE.invokeExact(q, x, n, y);
        } catch (Throwable e) {
            e.printStackTrace();
            return -1;
        }
    }

    // ========== FIR Filter (firfilt_cccf) - Complex coefficients, complex input/output ==========
    // Used for asymmetric filters such as the SSB channel bandpass, where the
    // passband is one-sided around DC and cannot be expressed with real-symmetric taps.

    private static ApiMethod firfilt_cccf_create = new ApiMethod("firfilt_cccf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Create a FIR filter from custom complex coefficients.
     * @param h tap array (interleaved I/Q floats, length = 2 * n)
     * @param n number of taps
     */
    public static MemorySegment firfilt_cccf_create(MemorySegment h, int n) {
        return (MemorySegment) NativeUtils.call(firfilt_cccf_create, h, n);
    }

    private static ApiMethod firfilt_cccf_destroy = new ApiMethod("firfilt_cccf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firfilt_cccf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(firfilt_cccf_destroy, q);
    }

    private static ApiMethod firfilt_cccf_reset = new ApiMethod("firfilt_cccf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firfilt_cccf_reset(MemorySegment q) {
        return (int) NativeUtils.call(firfilt_cccf_reset, q);
    }

    private static ApiMethod firfilt_cccf_execute_block = new ApiMethod("firfilt_cccf_execute_block", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firfilt_cccf_execute_block(MemorySegment q, MemorySegment x, int n, MemorySegment y) {
        try {
            return (int) firfilt_cccf_execute_block.HANDLE.invokeExact(q, x, n, y);
        } catch (Throwable e) {
            e.printStackTrace();
            return -1;
        }
    }

    // ========== FIR Filter (firfilt_rrrf) - Real input, real output ==========

    private static ApiMethod firfilt_rrrf_create_kaiser = new ApiMethod("firfilt_rrrf_create_kaiser", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT
    ));
    public static MemorySegment firfilt_rrrf_create_kaiser(int n, float fc, float As, float mu) {
        return (MemorySegment) NativeUtils.call(firfilt_rrrf_create_kaiser, n, fc, As, mu);
    }

    private static ApiMethod firfilt_rrrf_destroy = new ApiMethod("firfilt_rrrf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firfilt_rrrf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(firfilt_rrrf_destroy, q);
    }

    private static ApiMethod firfilt_rrrf_reset = new ApiMethod("firfilt_rrrf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firfilt_rrrf_reset(MemorySegment q) {
        return (int) NativeUtils.call(firfilt_rrrf_reset, q);
    }

    private static ApiMethod firfilt_rrrf_execute_block = new ApiMethod("firfilt_rrrf_execute_block", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firfilt_rrrf_execute_block(MemorySegment q, MemorySegment x, int n, MemorySegment y) {
    	try {
			return (int) firfilt_rrrf_execute_block.HANDLE.invokeExact(q, x, n, y);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    // ========== IIR Filter (iirfilt_rrrf) - Real input, real output ==========

    private static ApiMethod iirfilt_rrrf_create_lowpass = new ApiMethod("iirfilt_rrrf_create_lowpass", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_FLOAT
    ));
    /**
     * Create a Butterworth low-pass IIR filter.
     * @param order filter order
     * @param fc normalized cutoff frequency (0 < fc < 0.5)
     */
    public static MemorySegment iirfilt_rrrf_create_lowpass(int order, float fc) {
        return (MemorySegment) NativeUtils.call(iirfilt_rrrf_create_lowpass, order, fc);
    }

    private static ApiMethod iirfilt_rrrf_create_prototype = new ApiMethod("iirfilt_rrrf_create_prototype", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,   // ftype
        ValueLayout.JAVA_INT,   // btype
        ValueLayout.JAVA_INT,   // format
        ValueLayout.JAVA_INT,   // order
        ValueLayout.JAVA_FLOAT, // fc
        ValueLayout.JAVA_FLOAT, // f0
        ValueLayout.JAVA_FLOAT, // Ap
        ValueLayout.JAVA_FLOAT  // As
    ));
    /**
     * Create a real IIR filter using standard prototype.
     * @param ftype 0=butter, 1=cheby1, 2=cheby2, 3=ellip, 4=bessel
     * @param btype 0=lowpass, 1=highpass, 2=bandpass, 3=bandstop
     * @param format 0=second-order sections, 1=transfer function
     * @param order filter order (bandpass gets 2×order poles)
     * @param fc cutoff / half-bandwidth (normalized)
     * @param f0 center frequency for bandpass/bandstop (normalized)
     */
    public static MemorySegment iirfilt_rrrf_create_prototype(int ftype, int btype, int format,
            int order, float fc, float f0, float Ap, float As) {
        return (MemorySegment) NativeUtils.call(iirfilt_rrrf_create_prototype, ftype, btype, format, order, fc, f0, Ap, As);
    }

    private static ApiMethod iirfilt_rrrf_destroy = new ApiMethod("iirfilt_rrrf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int iirfilt_rrrf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(iirfilt_rrrf_destroy, q);
    }

    private static ApiMethod iirfilt_rrrf_reset = new ApiMethod("iirfilt_rrrf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int iirfilt_rrrf_reset(MemorySegment q) {
        return (int) NativeUtils.call(iirfilt_rrrf_reset, q);
    }

    private static ApiMethod iirfilt_rrrf_execute_block = new ApiMethod("iirfilt_rrrf_execute_block", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int iirfilt_rrrf_execute_block(MemorySegment q, MemorySegment x, int n, MemorySegment y) {
    	try {
			return (int) iirfilt_rrrf_execute_block.HANDLE.invokeExact(q, x, n, y);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    // ========== Arbitrary Resampler (msresamp_crcf) - Complex ==========

    private static ApiMethod msresamp_crcf_create = new ApiMethod("msresamp_crcf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT
    ));
    /**
     * Create a multi-stage arbitrary rate resampler for complex signals.
     * @param r resampling rate (output/input)
     * @param As stop-band attenuation in dB
     */
    public static MemorySegment msresamp_crcf_create(float r, float As) {
        return (MemorySegment) NativeUtils.call(msresamp_crcf_create, r, As);
    }

    private static ApiMethod msresamp_crcf_destroy = new ApiMethod("msresamp_crcf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int msresamp_crcf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(msresamp_crcf_destroy, q);
    }

    private static ApiMethod msresamp_crcf_reset = new ApiMethod("msresamp_crcf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int msresamp_crcf_reset(MemorySegment q) {
        return (int) NativeUtils.call(msresamp_crcf_reset, q);
    }

    private static ApiMethod msresamp_crcf_execute = new ApiMethod("msresamp_crcf_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    /**
     * Execute resampler on a block of complex samples.
     * @param q resampler object
     * @param x input samples
     * @param nx number of input samples
     * @param y output samples (must be large enough: ceil(nx * r) + margin)
     * @param ny pointer to unsigned int to receive number of output samples
     */
    public static int msresamp_crcf_execute(MemorySegment q, MemorySegment x, int nx, MemorySegment y, MemorySegment ny) {
    	try {
			return (int) msresamp_crcf_execute.HANDLE.invokeExact(q, x, nx, y, ny);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod msresamp_crcf_get_delay = new ApiMethod("msresamp_crcf_get_delay", FunctionDescriptor.of(
        ValueLayout.JAVA_FLOAT,
        ValueLayout.ADDRESS
    ));
    public static float msresamp_crcf_get_delay(MemorySegment q) {
        return (float) NativeUtils.call(msresamp_crcf_get_delay, q);
    }

    // ========== Arbitrary Resampler (msresamp_rrrf) - Real ==========

    private static ApiMethod msresamp_rrrf_create = new ApiMethod("msresamp_rrrf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT
    ));
    public static MemorySegment msresamp_rrrf_create(float r, float As) {
        return (MemorySegment) NativeUtils.call(msresamp_rrrf_create, r, As);
    }

    private static ApiMethod msresamp_rrrf_destroy = new ApiMethod("msresamp_rrrf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int msresamp_rrrf_destroy(MemorySegment q) {
    	try {
			return (int) msresamp_rrrf_destroy.HANDLE.invokeExact(q);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod msresamp_rrrf_reset = new ApiMethod("msresamp_rrrf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int msresamp_rrrf_reset(MemorySegment q) {
    	try {
			return (int) msresamp_rrrf_reset.HANDLE.invokeExact(q);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod msresamp_rrrf_execute = new ApiMethod("msresamp_rrrf_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    public static int msresamp_rrrf_execute(MemorySegment q, MemorySegment x, int nx, MemorySegment y, MemorySegment ny) {
    	try {
			return (int) msresamp_rrrf_execute.HANDLE.invokeExact(q, x, nx, y, ny);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    // ========== FM Demodulator (freqdem) ==========

    private static ApiMethod freqdem_create = new ApiMethod("freqdem_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT
    ));
    /**
     * Create a frequency demodulator.
     * @param kf modulation factor (max frequency deviation / sample rate)
     */
    public static MemorySegment freqdem_create(float kf) {
        return (MemorySegment) NativeUtils.call(freqdem_create, kf);
    }

    private static ApiMethod freqdem_destroy = new ApiMethod("freqdem_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int freqdem_destroy(MemorySegment q) {
        return (int) NativeUtils.call(freqdem_destroy, q);
    }

    private static ApiMethod freqdem_reset = new ApiMethod("freqdem_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int freqdem_reset(MemorySegment q) {
        return (int) NativeUtils.call(freqdem_reset, q);
    }

    private static ApiMethod freqdem_demodulate_block = new ApiMethod("freqdem_demodulate_block", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    /**
     * Demodulate a block of complex samples to real audio.
     * @param q demodulator object
     * @param r input complex samples
     * @param n number of samples
     * @param m output real samples
     */
    public static int freqdem_demodulate_block(MemorySegment q, MemorySegment r, int n, MemorySegment m) {
    	try {
			return (int) freqdem_demodulate_block.HANDLE.invokeExact(q, r, n, m);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
        // return (int) NativeUtils.call(freqdem_demodulate_block, q, r, n, m);
    }

    // ========== AGC (agc_crcf) - Automatic Gain Control ==========

    private static ApiMethod agc_crcf_create = new ApiMethod("agc_crcf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS
    ));
    public static MemorySegment agc_crcf_create() {
        return (MemorySegment) NativeUtils.call(agc_crcf_create);
    }

    private static ApiMethod agc_crcf_destroy = new ApiMethod("agc_crcf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int agc_crcf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(agc_crcf_destroy, q);
    }

    private static ApiMethod agc_crcf_reset = new ApiMethod("agc_crcf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int agc_crcf_reset(MemorySegment q) {
    	try {
			return (int) agc_crcf_reset.HANDLE.invokeExact(q);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod agc_crcf_set_bandwidth = new ApiMethod("agc_crcf_set_bandwidth", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT
    ));
    public static int agc_crcf_set_bandwidth(MemorySegment q, float bandwidth) {
    	try {
			return (int) agc_crcf_set_bandwidth.HANDLE.invokeExact(q, bandwidth);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod agc_crcf_execute_block = new ApiMethod("agc_crcf_execute_block", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int agc_crcf_execute_block(MemorySegment q, MemorySegment x, int n, MemorySegment y) {
    	try {
			return (int) agc_crcf_execute_block.HANDLE.invokeExact(q, x, n, y);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod agc_crcf_get_gain = new ApiMethod("agc_crcf_get_gain", FunctionDescriptor.of(
        ValueLayout.JAVA_FLOAT,
        ValueLayout.ADDRESS
    ));
    public static float agc_crcf_get_gain(MemorySegment q) {
    	try {
			return (float) agc_crcf_get_gain.HANDLE.invokeExact(q);
		} catch (Throwable e) {
			e.printStackTrace();
			return Float.NaN;
		}
    }
    
    private static ApiMethod agc_crcf_get_rssi = new ApiMethod("agc_crcf_get_rssi", FunctionDescriptor.of(
        ValueLayout.JAVA_FLOAT,
        ValueLayout.ADDRESS
    ));
    public static float agc_crcf_get_rssi(MemorySegment q) {
    	try {
			return (float) agc_crcf_get_rssi.HANDLE.invokeExact(q);
		} catch (Throwable e) {
			e.printStackTrace();
			return Float.NaN;
		}
    }

    // ========== AGC (agc_rrrf) - Real AGC ==========

    private static ApiMethod agc_rrrf_create = new ApiMethod("agc_rrrf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS
    ));
    public static MemorySegment agc_rrrf_create() {
        return (MemorySegment) NativeUtils.call(agc_rrrf_create);
    }

    private static ApiMethod agc_rrrf_destroy = new ApiMethod("agc_rrrf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int agc_rrrf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(agc_rrrf_destroy, q);
    }

    private static ApiMethod agc_rrrf_set_bandwidth = new ApiMethod("agc_rrrf_set_bandwidth", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT
    ));
    public static int agc_rrrf_set_bandwidth(MemorySegment q, float bandwidth) {
        return (int) NativeUtils.call(agc_rrrf_set_bandwidth, q, bandwidth);
    }

    private static ApiMethod agc_rrrf_execute_block = new ApiMethod("agc_rrrf_execute_block", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int agc_rrrf_execute_block(MemorySegment q, MemorySegment x, int n, MemorySegment y) {
    	try {
			return (int) agc_rrrf_execute_block.HANDLE.invokeExact(q, x, n, y);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    // ========== Hilbert Transform (firhilbf) ==========

    private static ApiMethod firhilbf_create = new ApiMethod("firhilbf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_FLOAT
    ));
    /**
     * Create a Hilbert transform filter (real to complex or vice versa).
     * @param m filter semi-length
     * @param As stop-band attenuation in dB
     */
    public static MemorySegment firhilbf_create(int m, float As) {
        return (MemorySegment) NativeUtils.call(firhilbf_create, m, As);
    }

    private static ApiMethod firhilbf_destroy = new ApiMethod("firhilbf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firhilbf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(firhilbf_destroy, q);
    }

    private static ApiMethod firhilbf_reset = new ApiMethod("firhilbf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int firhilbf_reset(MemorySegment q) {
        return (int) NativeUtils.call(firhilbf_reset, q);
    }

    private static ApiMethod firhilbf_decim_execute = new ApiMethod("firhilbf_decim_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    /**
     * Decimate real signal to complex (2 real samples -> 1 complex sample).
     * @param q hilbert object
     * @param x input real samples (2 samples)
     * @param y output complex sample (2 floats: real, imag)
     */
    public static int firhilbf_decim_execute(MemorySegment q, MemorySegment x, MemorySegment y) {
        return (int) NativeUtils.call(firhilbf_decim_execute, q, x, y);
    }

    private static ApiMethod firhilbf_interp_execute = new ApiMethod("firhilbf_interp_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    /**
     * Interpolate complex signal to real (1 complex sample -> 2 real samples).
     * @param q hilbert object
     * @param x input complex sample (2 floats: real, imag)
     * @param y output real samples (2 samples)
     */
    public static int firhilbf_interp_execute(MemorySegment q, MemorySegment x, MemorySegment y) {
        return (int) NativeUtils.call(firhilbf_interp_execute, q, x, y);
    }

    // ========== NCO (nco_crcf) - Numerically Controlled Oscillator ==========

    private static ApiMethod nco_crcf_create = new ApiMethod("nco_crcf_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Create a numerically controlled oscillator.
     * @param type oscillator type (0 = standard, 1 = VCO)
     */
    public static MemorySegment nco_crcf_create(int type) {
        return (MemorySegment) NativeUtils.call(nco_crcf_create, type);
    }

    private static ApiMethod nco_crcf_destroy = new ApiMethod("nco_crcf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int nco_crcf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(nco_crcf_destroy, q);
    }

    private static ApiMethod nco_crcf_reset = new ApiMethod("nco_crcf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int nco_crcf_reset(MemorySegment q) {
        return (int) NativeUtils.call(nco_crcf_reset, q);
    }

    private static ApiMethod nco_crcf_set_frequency = new ApiMethod("nco_crcf_set_frequency", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT
    ));
    /**
     * Set the NCO frequency in radians per sample.
     */
    public static int nco_crcf_set_frequency(MemorySegment q, float frequency) {
        return (int) NativeUtils.call(nco_crcf_set_frequency, q, frequency);
    }

    private static ApiMethod nco_crcf_set_phase = new ApiMethod("nco_crcf_set_phase", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT
    ));
    public static int nco_crcf_set_phase(MemorySegment q, float phase) {
        return (int) NativeUtils.call(nco_crcf_set_phase, q, phase);
    }

    private static ApiMethod nco_crcf_mix_block_down = new ApiMethod("nco_crcf_mix_block_down", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    /**
     * Mix a block of samples down (multiply by conjugate of oscillator output).
     * @param q NCO object
     * @param x input complex samples
     * @param y output complex samples
     * @param n number of samples
     */
    public static int nco_crcf_mix_block_down(MemorySegment q, MemorySegment x, MemorySegment y, int n) {
    	try {
			return (int) nco_crcf_mix_block_down.HANDLE.invokeExact(q, x, y, n);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod nco_crcf_mix_block_up = new ApiMethod("nco_crcf_mix_block_up", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    ));
    public static int nco_crcf_mix_block_up(MemorySegment q, MemorySegment x, MemorySegment y, int n) {
    	try {
			return (int) nco_crcf_mix_block_up.HANDLE.invokeExact(q, x, y, n);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }

    private static ApiMethod nco_crcf_step = new ApiMethod("nco_crcf_step", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    /**
     * Step the NCO forward by one sample.
     */
    public static int nco_crcf_step(MemorySegment q) {
        try {
            return (int) nco_crcf_step.HANDLE.invokeExact(q);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static ApiMethod nco_crcf_cexpf = new ApiMethod("nco_crcf_cexpf", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    /**
     * Get the current NCO output as complex exponential.
     * @param q NCO object
     * @param y output complex sample (2 floats: real, imag)
     */
    public static int nco_crcf_cexpf(MemorySegment q, MemorySegment y) {
        try {
            return (int) nco_crcf_cexpf.HANDLE.invokeExact(q, y);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static ApiMethod nco_crcf_pll_step = new ApiMethod("nco_crcf_pll_step", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT
    ));
    /**
     * Step the NCO's internal PLL with phase error.
     * @param q NCO object
     * @param dphi phase error
     */
    public static int nco_crcf_pll_step(MemorySegment q, float dphi) {
        try {
            return (int) nco_crcf_pll_step.HANDLE.invokeExact(q, dphi);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static ApiMethod nco_crcf_pll_set_bandwidth = new ApiMethod("nco_crcf_pll_set_bandwidth", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT
    ));
    /**
     * Set the PLL bandwidth.
     */
    public static int nco_crcf_pll_set_bandwidth(MemorySegment q, float bw) {
        try {
            return (int) nco_crcf_pll_set_bandwidth.HANDLE.invokeExact(q, bw);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static ApiMethod nco_crcf_mix_down = new ApiMethod("nco_crcf_mix_down", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    /**
     * Mix down a single complex sample.
     * @param q NCO object
     * @param x input complex sample
     * @param y output complex sample
     */
    public static int nco_crcf_mix_down(MemorySegment q, MemorySegment x, MemorySegment y) {
        try {
            return (int) nco_crcf_mix_down.HANDLE.invokeExact(q, x, y);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    // ========== Hilbert Transform single-sample operations ==========

    private static ApiMethod firhilbf_r2c_execute = new ApiMethod("firhilbf_r2c_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.ADDRESS
    ));
    /**
     * Execute Hilbert transform: real to complex (single sample).
     * @param q hilbert object
     * @param x input real sample
     * @param y output complex sample (2 floats: real, imag)
     */
    public static int firhilbf_r2c_execute(MemorySegment q, float x, MemorySegment y) {
        try {
            return (int) firhilbf_r2c_execute.HANDLE.invokeExact(q, x, y);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static ApiMethod firhilbf_c2r_execute = new ApiMethod("firhilbf_c2r_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    /**
     * Execute Hilbert transform: complex to real (single sample).
     * @param q hilbert object
     * @param x input complex sample (2 floats: real, imag)
     * @param y output real sample (pointer to float)
     */
    public static int firhilbf_c2r_execute(MemorySegment q, MemorySegment x, MemorySegment y) {
        try {
            return (int) firhilbf_c2r_execute.HANDLE.invokeExact(q, x, y);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    // ========== IIR Filter (iirfilt_crcf) - Complex input, complex output ==========

    private static ApiMethod iirfilt_crcf_create_lowpass = new ApiMethod("iirfilt_crcf_create_lowpass", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,   // order
        ValueLayout.JAVA_FLOAT  // fc (cutoff, normalized)
    ));
    /**
     * Create a complex IIR lowpass filter (Butterworth).
     * @param order filter order
     * @param fc cutoff frequency (normalized, 0 < fc < 0.5)
     */
    public static MemorySegment iirfilt_crcf_create_lowpass(int order, float fc) {
        return (MemorySegment) NativeUtils.call(iirfilt_crcf_create_lowpass, order, fc);
    }

    private static ApiMethod iirfilt_crcf_create_prototype = new ApiMethod("iirfilt_crcf_create_prototype", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,   // ftype (butter=0, cheby1=1, etc)
        ValueLayout.JAVA_INT,   // btype (lowpass=0, highpass=1, bandpass=2, bandstop=3)
        ValueLayout.JAVA_INT,   // format (sos=0, tf=1)
        ValueLayout.JAVA_INT,   // order
        ValueLayout.JAVA_FLOAT, // fc (cutoff)
        ValueLayout.JAVA_FLOAT, // f0 (center for bandpass)
        ValueLayout.JAVA_FLOAT, // Ap (passband ripple)
        ValueLayout.JAVA_FLOAT  // As (stopband attenuation)
    ));
    /**
     * Create a complex IIR filter using standard prototype.
     * @param ftype filter type: 0=butter, 1=cheby1, 2=cheby2, 3=ellip, 4=bessel
     * @param btype band type: 0=lowpass, 1=highpass, 2=bandpass, 3=bandstop
     * @param format 0=second-order sections, 1=transfer function
     * @param order filter order
     * @param fc cutoff frequency (normalized, 0 < fc < 0.5)
     * @param f0 center frequency for bandpass/bandstop
     * @param Ap passband ripple in dB
     * @param As stopband attenuation in dB
     */
    public static MemorySegment iirfilt_crcf_create_prototype(int ftype, int btype, int format,
            int order, float fc, float f0, float Ap, float As) {
        return (MemorySegment) NativeUtils.call(iirfilt_crcf_create_prototype, ftype, btype, format, order, fc, f0, Ap, As);
    }

    private static ApiMethod iirfilt_crcf_destroy = new ApiMethod("iirfilt_crcf_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int iirfilt_crcf_destroy(MemorySegment q) {
        return (int) NativeUtils.call(iirfilt_crcf_destroy, q);
    }

    private static ApiMethod iirfilt_crcf_reset = new ApiMethod("iirfilt_crcf_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int iirfilt_crcf_reset(MemorySegment q) {
        return (int) NativeUtils.call(iirfilt_crcf_reset, q);
    }

    private static ApiMethod iirfilt_crcf_execute = new ApiMethod("iirfilt_crcf_execute", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS
    ));
    /**
     * Execute IIR filter on a single complex sample.
     * @param q filter object
     * @param x input complex sample (2 floats)
     * @param y output complex sample (2 floats)
     */
    public static int iirfilt_crcf_execute(MemorySegment q, MemorySegment x, MemorySegment y) {
        try {
            return (int) iirfilt_crcf_execute.HANDLE.invokeExact(q, x, y);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    // ========== Amplitude Demodulator (ampmodem) ==========

    private static ApiMethod ampmodem_create = new ApiMethod("ampmodem_create", FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT
    ));
    /**
     * Create an AM demodulator.
     * @param m modulation depth (0 < m <= 1)
     * @param type 0 = double sideband (DSB), 1 = single sideband (USB), 2 = single sideband (LSB)
     * @param suppressed_carrier 0 = carrier present, 1 = suppressed carrier
     */
    public static MemorySegment ampmodem_create(float m, int type, int suppressed_carrier) {
        return (MemorySegment) NativeUtils.call(ampmodem_create, m, type, suppressed_carrier);
    }

    private static ApiMethod ampmodem_destroy = new ApiMethod("ampmodem_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int ampmodem_destroy(MemorySegment q) {
        return (int) NativeUtils.call(ampmodem_destroy, q);
    }

    private static ApiMethod ampmodem_reset = new ApiMethod("ampmodem_reset", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int ampmodem_reset(MemorySegment q) {
        return (int) NativeUtils.call(ampmodem_reset, q);
    }

    private static ApiMethod ampmodem_demodulate_block = new ApiMethod("ampmodem_demodulate_block", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    public static int ampmodem_demodulate_block(MemorySegment q, MemorySegment r, int n, MemorySegment m) {
    	try {
			return (int) ampmodem_demodulate_block.HANDLE.invokeExact(q, r, n, m);
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
    }
    
}
