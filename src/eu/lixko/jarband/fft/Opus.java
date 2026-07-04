package eu.lixko.jarband.fft;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import eu.lixko.jsoapy.util.NativeUtils;
import eu.lixko.jsoapy.util.NativeUtils.ApiMethod;

/**
 * Java bindings for libopus.
 *
 * <p>Covers encoder creation/destruction, float encoding, and the CTL interface
 * for configuring bitrate, VBR, and signal type. The CTL interface is a C
 * varargs function; it is bound twice — once for integer-value SET calls and
 * once for pointer-value GET calls — using {@link Linker.Option#firstVariadicArg}.
 */
public class Opus {
    static {
        System.loadLibrary("opus");
    }

    // ========== Application type constants ==========
    /** General-purpose audio codec. */
    public static final int OPUS_APPLICATION_AUDIO = 2049;
    /** Optimised for voice; applies voice-specific pre-processing. */
    public static final int OPUS_APPLICATION_VOIP  = 2048;
    /** Lowest possible latency (no look-ahead). */
    public static final int OPUS_APPLICATION_RESTRICTED_LOWDELAY = 2051;

    // ========== CTL request codes ==========
    public static final int OPUS_SET_BITRATE_REQUEST    = 4002;
    public static final int OPUS_GET_BITRATE_REQUEST    = 4003;
    public static final int OPUS_SET_VBR_REQUEST        = 4006;
    public static final int OPUS_SET_COMPLEXITY_REQUEST = 4010;
    public static final int OPUS_SET_SIGNAL_REQUEST     = 4024;

    // ========== Signal type constants (for OPUS_SET_SIGNAL_REQUEST) ==========
    public static final int OPUS_SIGNAL_VOICE = 3001;
    public static final int OPUS_SIGNAL_MUSIC = 3002;

    // ========== Special bitrate values ==========
    /** Use VBR auto-bitrate. */
    public static final int OPUS_AUTO = -1000;
    /** Maximum possible bitrate. */
    public static final int OPUS_BITRATE_MAX = -1;

    // ========== Error codes ==========
    public static final int OPUS_OK             =  0;
    public static final int OPUS_BAD_ARG        = -1;
    public static final int OPUS_BUFFER_TOO_SMALL = -2;
    public static final int OPUS_INTERNAL_ERROR = -3;
    public static final int OPUS_INVALID_PACKET = -4;
    public static final int OPUS_UNIMPLEMENTED  = -5;
    public static final int OPUS_INVALID_STATE  = -6;
    public static final int OPUS_ALLOC_FAIL     = -7;

    // ========== opus_encoder_create ==========
    // OpusEncoder* opus_encoder_create(opus_int32 Fs, int channels, int application, int *error)
    private static final ApiMethod opus_encoder_create = new ApiMethod("opus_encoder_create",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,   // Fs
            ValueLayout.JAVA_INT,   // channels
            ValueLayout.JAVA_INT,   // application
            ValueLayout.ADDRESS     // *error (int)
        ));

    /**
     * Create a new Opus encoder.
     *
     * @param Fs         sample rate in Hz (8000, 12000, 16000, 24000, or 48000)
     * @param channels   number of channels (1 = mono, 2 = stereo)
     * @param application application type (e.g. {@link #OPUS_APPLICATION_VOIP})
     * @param error      pre-allocated 4-byte segment; receives {@link #OPUS_OK} or an error code
     * @return encoder handle, or NULL on error
     */
    public static MemorySegment opus_encoder_create(int Fs, int channels, int application, MemorySegment error) {
        return (MemorySegment) NativeUtils.call(opus_encoder_create, Fs, channels, application, error);
    }

    // ========== opus_encoder_destroy ==========
    // void opus_encoder_destroy(OpusEncoder *st)
    private static final ApiMethod opus_encoder_destroy = new ApiMethod("opus_encoder_destroy",
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS     // *st
        ));

    /**
     * Destroy an Opus encoder and free its resources.
     */
    public static void opus_encoder_destroy(MemorySegment st) {
        NativeUtils.call(opus_encoder_destroy, st);
    }

    // ========== opus_encode_float ==========
    // opus_int32 opus_encode_float(OpusEncoder *st, const float *pcm, int frame_size,
    //                              unsigned char *data, opus_int32 max_data_bytes)
    private static final ApiMethod opus_encode_float = new ApiMethod("opus_encode_float",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,   // return: bytes written, or negative error code
            ValueLayout.ADDRESS,    // *st
            ValueLayout.ADDRESS,    // *pcm
            ValueLayout.JAVA_INT,   // frame_size (samples per channel)
            ValueLayout.ADDRESS,    // *data (output buffer)
            ValueLayout.JAVA_INT    // max_data_bytes
        ));

    /**
     * Encode a frame of floating-point audio.
     *
     * @param st             encoder handle
     * @param pcm            PCM input: {@code frame_size × channels} floats in [-1,+1]
     * @param frame_size     samples per channel (e.g. 160 for 20 ms at 8 kHz)
     * @param data           output buffer (at least {@code max_data_bytes} long)
     * @param max_data_bytes capacity of {@code data}
     * @return number of bytes written, or a negative error code
     */
    public static int opus_encode_float(MemorySegment st, MemorySegment pcm, int frame_size,
                                        MemorySegment data, int max_data_bytes) {
        try {
            return (int) opus_encode_float.HANDLE.invokeExact(st, pcm, frame_size, data, max_data_bytes);
        } catch (Throwable e) {
            e.printStackTrace();
            return OPUS_INTERNAL_ERROR;
        }
    }

    // ========== opus_encoder_ctl — varargs, two typed overloads ==========
    //
    // C prototype: int opus_encoder_ctl(OpusEncoder *st, int request, ...)
    //
    // 'firstVariadicArg(2)' tells the Panama linker that parameter index 2 (0-based)
    // is the first variadic argument. This is required on some ABIs (notably x86-64
    // System V) to correctly pass the extra arguments.

    /** CTL set: pass an int value as the variadic argument. */
    private static final MethodHandle opus_encoder_ctl_set;

    /** CTL get: pass a pointer (MemorySegment) as the variadic argument. */
    private static final MethodHandle opus_encoder_ctl_get;

    static {
        MemorySegment ctlAddr = NativeUtils.findOrThrow("opus_encoder_ctl");
        opus_encoder_ctl_set = Linker.nativeLinker().downcallHandle(
            ctlAddr,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,    // *st
                ValueLayout.JAVA_INT,   // request
                ValueLayout.JAVA_INT    // int value (variadic)
            ),
            Linker.Option.firstVariadicArg(2));
        opus_encoder_ctl_get = Linker.nativeLinker().downcallHandle(
            ctlAddr,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,    // *st
                ValueLayout.JAVA_INT,   // request
                ValueLayout.ADDRESS     // int* out (variadic)
            ),
            Linker.Option.firstVariadicArg(2));
    }

    /**
     * Set an integer encoder parameter via CTL.
     *
     * @param st      encoder handle
     * @param request CTL request code (e.g. {@link #OPUS_SET_BITRATE_REQUEST})
     * @param value   integer value to set
     * @return {@link #OPUS_OK} or a negative error code
     */
    public static int opus_encoder_ctl_set(MemorySegment st, int request, int value) {
        try {
            return (int) opus_encoder_ctl_set.invokeExact(st, request, value);
        } catch (Throwable e) {
            e.printStackTrace();
            return OPUS_INTERNAL_ERROR;
        }
    }

    /**
     * Get an integer encoder parameter via CTL.
     *
     * @param st      encoder handle
     * @param request CTL request code (e.g. {@link #OPUS_GET_BITRATE_REQUEST})
     * @param out     pre-allocated 4-byte segment that receives the value
     * @return {@link #OPUS_OK} or a negative error code
     */
    public static int opus_encoder_ctl_get(MemorySegment st, int request, MemorySegment out) {
        try {
            return (int) opus_encoder_ctl_get.invokeExact(st, request, out);
        } catch (Throwable e) {
            e.printStackTrace();
            return OPUS_INTERNAL_ERROR;
        }
    }

    /**
     * Convenience: set bitrate in bits/second. Use {@link #OPUS_AUTO} for auto-VBR.
     */
    public static int setEncoderBitrate(MemorySegment st, int bitratesBps) {
        return opus_encoder_ctl_set(st, OPUS_SET_BITRATE_REQUEST, bitratesBps);
    }

    /**
     * Convenience: enable or disable VBR (1 = enabled, 0 = CBR).
     */
    public static int setEncoderVbr(MemorySegment st, int enabled) {
        return opus_encoder_ctl_set(st, OPUS_SET_VBR_REQUEST, enabled);
    }

    /**
     * Convenience: set signal type hint ({@link #OPUS_SIGNAL_VOICE} or {@link #OPUS_SIGNAL_MUSIC}).
     */
    public static int setEncoderSignal(MemorySegment st, int signalType) {
        return opus_encoder_ctl_set(st, OPUS_SET_SIGNAL_REQUEST, signalType);
    }

    /**
     * Convenience: set encoding complexity (0 = fastest, 10 = best quality).
     */
    public static int setEncoderComplexity(MemorySegment st, int complexity) {
        return opus_encoder_ctl_set(st, OPUS_SET_COMPLEXITY_REQUEST, complexity);
    }

    /**
     * Convenience: allocate a 4-byte error-code segment in the given arena.
     */
    public static MemorySegment allocError(Arena arena) {
        return arena.allocate(ValueLayout.JAVA_INT);
    }

    /**
     * Read the error code written into a segment returned by {@link #allocError(Arena)}.
     */
    public static int readError(MemorySegment errorSeg) {
        return errorSeg.get(ValueLayout.JAVA_INT, 0);
    }
}
