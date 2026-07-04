package eu.lixko.jarband.fft;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jsoapy.util.NativeUtils;
import eu.lixko.jsoapy.util.NativeUtils.ApiMethod;

public class FFTW {
	
	static {
		System.loadLibrary("fftw3");
		System.loadLibrary("fftw3f");
	}
	
	public static final int FFTW_FORWARD = -1;
	public static final int FFTW_BACKWARD = 1;
	/** Fastest planning — no measurements, ~30% slower execution. */
	public static final int FFTW_ESTIMATE = 1 << 6;
	/** Benchmarks a few plan variants at creation time. With imported wisdom, near-instant for known sizes. */
	public static final int FFTW_MEASURE = 0;
	/** More exhaustive search than MEASURE; used when wisdom files are being primed offline. */
	public static final int FFTW_PATIENT = 1 << 5;
	
    private static ApiMethod fftwf_malloc = new ApiMethod("fftwf_malloc", FunctionDescriptor.of(
		ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG
    ));
    public static MemorySegment fftwf_malloc(long size) {
    	var seg = (MemorySegment) NativeUtils.call(fftwf_malloc, size);
    	return seg.reinterpret(size);
    }
    
    private static ApiMethod fftwf_free = new ApiMethod("fftwf_free", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS
    ));
    public static void fftwf_free(MemorySegment ptr) {
    	NativeUtils.call(fftwf_free, ptr);
    }
    
    private static ApiMethod fftwf_destroy_plan = new ApiMethod("fftwf_destroy_plan", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS
    ));
    public static void fftwf_destroy_plan(MemorySegment ptr) {
    	NativeUtils.call(fftwf_destroy_plan, ptr);
    }
    
    private static ApiMethod fftwf_execute = new ApiMethod("fftwf_execute", FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS
    ));
    public static void fftwf_execute(MemorySegment ptr) {
    	try {
			fftwf_execute.HANDLE.invokeExact(ptr);
		} catch (Throwable e) {
			e.printStackTrace();
		}
    }
    
    private static ApiMethod fftwf_plan_dft_1d = new ApiMethod("fftwf_plan_dft_1d", FunctionDescriptor.of(
		ValueLayout.ADDRESS,
		ValueLayout.JAVA_LONG,
		ValueLayout.ADDRESS,
		ValueLayout.ADDRESS,
		ValueLayout.JAVA_INT,
		ValueLayout.JAVA_INT
    ));
    public static MemorySegment fftwf_plan_dft_1d(long n, MemorySegment in, MemorySegment out, int sign, int flags) {
    	return (MemorySegment) NativeUtils.call(fftwf_plan_dft_1d, n, in, out, sign, flags);
    }

    // ========== Wisdom (plan cache) ==========
    // FFTW stores benchmarks of actual plan performance in a "wisdom" file. When
    // wisdom covering a given size is imported before planning, FFTW_MEASURE
    // becomes near-instant; without it, MEASURE for e.g. a 262144-point FFT can
    // stall the caller for seconds.

    private static ApiMethod fftwf_import_wisdom_from_filename = new ApiMethod("fftwf_import_wisdom_from_filename", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    /** Import wisdom from a file. Returns non-zero on success. */
    public static int fftwf_import_wisdom_from_filename(MemorySegment pathCStr) {
        return (int) NativeUtils.call(fftwf_import_wisdom_from_filename, pathCStr);
    }

    private static ApiMethod fftwf_export_wisdom_to_filename = new ApiMethod("fftwf_export_wisdom_to_filename", FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    ));
    /** Export wisdom to a file. Returns non-zero on success. */
    public static int fftwf_export_wisdom_to_filename(MemorySegment pathCStr) {
        return (int) NativeUtils.call(fftwf_export_wisdom_to_filename, pathCStr);
    }
}
