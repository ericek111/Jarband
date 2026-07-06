package eu.lixko.jarband.recording;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;

public final class OggOpusFile implements AutoCloseable {
    private static final int PAGE_AUDIO_PACKETS = 50;
    private static final int OPUS_GRANULE_RATE = 48_000;
    private static final int[] CRC_TABLE = crcTable();

    private final FileChannel channel;
    private final Arena arena = Arena.ofShared();
    private final int inputSampleRate;
    private final int granuleIncrement;
    private final MemorySegment stream;
    private final MemorySegment page;
    private int serial;
    private int byteOffset;
    private long granulePosition;
    private int pendingPageOffset = -1;
    private long packetNo;
    private int pendingPackets;
    private boolean streamOpen;

    public OggOpusFile(Path path, int inputSampleRate, int frameMillis) throws IOException {
        Files.createDirectories(path.getParent());
        this.inputSampleRate = inputSampleRate;
        this.granuleIncrement = OPUS_GRANULE_RATE * frameMillis / 1000;
        this.stream = arena.allocate(OggNative.STREAM_STATE_BYTES, 8);
        this.page = arena.allocate(OggNative.PAGE_BYTES, 8);
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        long existingSize = channel.size();
        if (existingSize > Integer.MAX_VALUE) {
            throw new IOException("Ogg Opus file exceeds 32-bit offset contract: " + path);
        }

        if (existingSize == 0) {
            startNewLogicalStream(path);
            writeHeaders();
        } else {
            ResumeState state = scanExisting();
            channel.truncate(state.byteOffset());
            channel.position(state.byteOffset());
            this.byteOffset = state.byteOffset();
            if (state.byteOffset() == 0 || state.endOfStream()) {
                startNewLogicalStream(path);
                writeHeaders();
            } else {
                this.serial = state.serial();
                this.granulePosition = state.granulePosition();
                this.packetNo = state.packetCount();
                initStream(state.nextPageSequence());
            }
        }
    }

    public synchronized int offsetForNextPacket() {
        return pendingPageOffset >= 0 ? pendingPageOffset : byteOffset;
    }

    public synchronized void writeAudioPacket(byte[] packet, int length) throws IOException {
        if (pendingPackets == 0) {
            pendingPageOffset = byteOffset;
        }
        granulePosition += granuleIncrement;
        packetIn(packet, length, false, false, granulePosition);
        pendingPackets++;
        drainPages(false);
        if (pendingPackets >= PAGE_AUDIO_PACKETS) {
            flushAudio(false);
        }
    }

    private void writeHeaders() throws IOException {
        packetIn(opusHead(), 19, true, false, 0);
        flushOneRequired();
        packetIn(opusTags(), opusTags().length, false, false, 0);
        flushOneRequired();
    }

    private void startNewLogicalStream(Path path) {
        this.serial = new Random(path.toAbsolutePath().hashCode() ^ System.nanoTime()).nextInt();
        this.granulePosition = 0;
        this.packetNo = 0;
        initStream(0);
    }

    private void initStream(int pageSequence) {
        int rc = OggNative.streamInit(stream, serial);
        if (rc != 0) {
            throw new IllegalStateException("ogg_stream_init failed: " + rc);
        }
        streamOpen = true;
        stream.set(ValueLayout.JAVA_LONG, OggNative.STREAM_PAGENO_OFFSET, pageSequence);
        stream.set(ValueLayout.JAVA_LONG, OggNative.STREAM_PACKETNO_OFFSET, packetNo);
        stream.set(ValueLayout.JAVA_LONG, OggNative.STREAM_GRANULEPOS_OFFSET, granulePosition);
    }

    private byte[] opusHead() {
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(StandardCharsets.US_ASCII));
        head.put((byte) 1);                 // version
        head.put((byte) 1);                 // mono
        head.putShort((short) 0);           // pre-skip
        head.putInt(inputSampleRate);
        head.putShort((short) 0);           // output gain
        head.put((byte) 0);                 // channel mapping family
        return head.array();
    }

    private byte[] opusTags() {
        byte[] vendor = "Jarband".getBytes(StandardCharsets.UTF_8);
        ByteBuffer tags = ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        tags.put("OpusTags".getBytes(StandardCharsets.US_ASCII));
        tags.putInt(vendor.length);
        tags.put(vendor);
        tags.putInt(0);
        return tags.array();
    }

    private void flushAudio(boolean endOfStream) throws IOException {
        if (pendingPackets == 0) {
            return;
        }
        if (endOfStream) {
            packetIn(new byte[0], 0, false, true, granulePosition);
        }
        flushAll();
        pendingPackets = 0;
        pendingPageOffset = -1;
    }

    private void packetIn(byte[] packetBytes, int length, boolean bos, boolean eos, long granule) {
        try (Arena packetArena = Arena.ofConfined()) {
            MemorySegment packetData = length == 0
                    ? MemorySegment.NULL
                    : packetArena.allocateFrom(ValueLayout.JAVA_BYTE, java.util.Arrays.copyOf(packetBytes, length));
            MemorySegment packet = packetArena.allocate(OggNative.PACKET_BYTES, 8);
            packet.set(ValueLayout.ADDRESS, OggNative.PACKET_PACKET_OFFSET, packetData);
            packet.set(ValueLayout.JAVA_LONG, OggNative.PACKET_BYTES_OFFSET, length);
            packet.set(ValueLayout.JAVA_LONG, OggNative.PACKET_BOS_OFFSET, bos ? 1L : 0L);
            packet.set(ValueLayout.JAVA_LONG, OggNative.PACKET_EOS_OFFSET, eos ? 1L : 0L);
            packet.set(ValueLayout.JAVA_LONG, OggNative.PACKET_GRANULEPOS_OFFSET, granule);
            packet.set(ValueLayout.JAVA_LONG, OggNative.PACKET_PACKETNO_OFFSET, packetNo++);
            int rc = OggNative.packetIn(stream, packet);
            if (rc != 0) {
                throw new IllegalStateException("ogg_stream_packetin failed: " + rc);
            }
        }
    }

    private void flushOneRequired() throws IOException {
        int rc = OggNative.flush(stream, page);
        if (rc == 0) {
            throw new IOException("libogg did not flush a required header page");
        }
        writePageFromLibogg();
    }

    private void flushAll() throws IOException {
        while (OggNative.flush(stream, page) != 0) {
            writePageFromLibogg();
        }
    }

    private void drainPages(boolean force) throws IOException {
        while ((force ? OggNative.flush(stream, page) : OggNative.pageOut(stream, page)) != 0) {
            writePageFromLibogg();
        }
    }

    private void writePageFromLibogg() throws IOException {
        MemorySegment header = page.get(ValueLayout.ADDRESS, OggNative.PAGE_HEADER_OFFSET)
                .reinterpret(page.get(ValueLayout.JAVA_LONG, OggNative.PAGE_HEADER_LEN_OFFSET));
        MemorySegment body = page.get(ValueLayout.ADDRESS, OggNative.PAGE_BODY_OFFSET)
                .reinterpret(page.get(ValueLayout.JAVA_LONG, OggNative.PAGE_BODY_LEN_OFFSET));
        writeSegment(header);
        writeSegment(body);
    }

    private void writeSegment(MemorySegment segment) throws IOException {
        ByteBuffer buffer = segment.asByteBuffer();
        channel.position(byteOffset);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        byteOffset += (int) segment.byteSize();
        if (byteOffset < 0) {
            throw new IOException("Ogg Opus file exceeds 32-bit offset contract");
        }
    }

    private ResumeState scanExisting() throws IOException {
        channel.position(0);
        int streamSerial = 0;
        int nextSequence = 0;
        long lastGranule = 0;
        long packetCount = 0;
        int lastGoodOffset = 0;
        boolean sawPage = false;
        boolean endOfStream = false;

        while (true) {
            int pageOffset = lastGoodOffset;
            ByteBuffer fixedHeader = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN);
            int headerBytes = readFully(fixedHeader);
            if (headerBytes == 0) {
                break;
            }
            if (headerBytes < 27) {
                break;
            }
            byte[] header = fixedHeader.array();
            if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S' || header[4] != 0) {
                break;
            }

            int headerType = header[5] & 0xff;
            long granule = ByteBuffer.wrap(header, 6, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            int serialOnPage = ByteBuffer.wrap(header, 14, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int sequenceOnPage = ByteBuffer.wrap(header, 18, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int segmentCount = header[26] & 0xff;

            ByteBuffer segmentTable = ByteBuffer.allocate(segmentCount);
            if (readFully(segmentTable) < segmentCount) {
                break;
            }
            byte[] segments = segmentTable.array();
            int payloadSize = 0;
            for (byte segment : segments) {
                payloadSize += segment & 0xff;
                if ((segment & 0xff) < 255) {
                    packetCount++;
                }
            }

            ByteBuffer payload = ByteBuffer.allocate(payloadSize);
            if (readFully(payload) < payloadSize) {
                break;
            }

            byte[] page = new byte[27 + segmentCount + payloadSize];
            System.arraycopy(header, 0, page, 0, header.length);
            System.arraycopy(segments, 0, page, 27, segments.length);
            System.arraycopy(payload.array(), 0, page, 27 + segmentCount, payloadSize);
            int expectedCrc = ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            page[22] = 0;
            page[23] = 0;
            page[24] = 0;
            page[25] = 0;
            if (crc(page) != expectedCrc) {
                break;
            }

            if (!sawPage) {
                streamSerial = serialOnPage;
            }
            if (serialOnPage != streamSerial) {
                streamSerial = serialOnPage;
                nextSequence = 0;
                lastGranule = 0;
                packetCount = 0;
            }
            sawPage = true;
            nextSequence = sequenceOnPage + 1;
            if (granule >= 0) {
                lastGranule = granule;
            }
            endOfStream = (headerType & 0x04) != 0;
            lastGoodOffset = pageOffset + page.length;
        }

        if (!sawPage) {
            return new ResumeState(0, 0, 0, 0, 0, false);
        }
        return new ResumeState(streamSerial, nextSequence, lastGranule, packetCount, lastGoodOffset, endOfStream);
    }

    private int readFully(ByteBuffer buffer) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static int crc(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) & 0xff) ^ (b & 0xff)];
        }
        return crc;
    }

    private static int[] crcTable() {
        int[] table = new int[256];
        for (int i = 0; i < table.length; i++) {
            int r = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
            }
            table[i] = r;
        }
        return table;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException thrown = null;
        try {
            flushAudio(false);
        } catch (IOException e) {
            thrown = e;
        }
        if (streamOpen) {
            OggNative.streamClear(stream);
            streamOpen = false;
        }
        arena.close();
        try {
            channel.close();
        } catch (IOException e) {
            if (thrown == null) thrown = e;
            else thrown.addSuppressed(e);
        }
        if (thrown != null) throw thrown;
    }

    private record ResumeState(int serial, int nextPageSequence, long granulePosition,
                               long packetCount, int byteOffset, boolean endOfStream) {}
}
