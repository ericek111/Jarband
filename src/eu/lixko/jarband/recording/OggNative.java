package eu.lixko.jarband.recording;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import eu.lixko.jsoapy.util.NativeUtils;
import eu.lixko.jsoapy.util.NativeUtils.ApiMethod;

final class OggNative {
    static {
        System.loadLibrary("ogg");
    }

    static final long STREAM_STATE_BYTES = 408;
    static final long PAGE_BYTES = 32;
    static final long PACKET_BYTES = 48;

    static final long STREAM_PAGENO_OFFSET = 384;
    static final long STREAM_PACKETNO_OFFSET = 392;
    static final long STREAM_GRANULEPOS_OFFSET = 400;

    static final long PAGE_HEADER_OFFSET = 0;
    static final long PAGE_HEADER_LEN_OFFSET = 8;
    static final long PAGE_BODY_OFFSET = 16;
    static final long PAGE_BODY_LEN_OFFSET = 24;

    static final long PACKET_PACKET_OFFSET = 0;
    static final long PACKET_BYTES_OFFSET = 8;
    static final long PACKET_BOS_OFFSET = 16;
    static final long PACKET_EOS_OFFSET = 24;
    static final long PACKET_GRANULEPOS_OFFSET = 32;
    static final long PACKET_PACKETNO_OFFSET = 40;

    private static final ApiMethod ogg_stream_init = new ApiMethod("ogg_stream_init", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT));
    private static final ApiMethod ogg_stream_clear = new ApiMethod("ogg_stream_clear", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS));
    private static final ApiMethod ogg_stream_packetin = new ApiMethod("ogg_stream_packetin", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS));
    private static final ApiMethod ogg_stream_pageout = new ApiMethod("ogg_stream_pageout", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS));
    private static final ApiMethod ogg_stream_flush = new ApiMethod("ogg_stream_flush", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS));

    private OggNative() {}

    static int streamInit(MemorySegment stream, int serial) {
        return (int) NativeUtils.call(ogg_stream_init, stream, serial);
    }

    static int streamClear(MemorySegment stream) {
        return (int) NativeUtils.call(ogg_stream_clear, stream);
    }

    static int packetIn(MemorySegment stream, MemorySegment packet) {
        return (int) NativeUtils.call(ogg_stream_packetin, stream, packet);
    }

    static int pageOut(MemorySegment stream, MemorySegment page) {
        return (int) NativeUtils.call(ogg_stream_pageout, stream, page);
    }

    static int flush(MemorySegment stream, MemorySegment page) {
        return (int) NativeUtils.call(ogg_stream_flush, stream, page);
    }
}
