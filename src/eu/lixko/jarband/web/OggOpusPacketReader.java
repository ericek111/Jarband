package eu.lixko.jarband.web;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class OggOpusPacketReader implements AutoCloseable {
    private final FileChannel channel;
    private final int endOffset;
    private final ByteBuffer fixedHeader = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN);
    private byte[] partialPacket = new byte[0];

    OggOpusPacketReader(Path path, int startOffset, int endOffset) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.endOffset = endOffset;
        channel.position(startOffset);
    }

    List<byte[]> readPagePackets() throws IOException {
        if (channel.position() >= endOffset) {
            return List.of();
        }
        fixedHeader.clear();
        int headerBytes = readFully(fixedHeader);
        if (headerBytes == 0) {
            return List.of();
        }
        if (headerBytes < 27) {
            throw new EOFException("Short Ogg page header");
        }
        byte[] header = fixedHeader.array();
        if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S' || header[4] != 0) {
            throw new IOException("Invalid Ogg page");
        }

        int segmentCount = header[26] & 0xff;
        ByteBuffer segmentsBuffer = ByteBuffer.allocate(segmentCount);
        if (readFully(segmentsBuffer) < segmentCount) {
            throw new EOFException("Short Ogg segment table");
        }
        byte[] segments = segmentsBuffer.array();
        int payloadSize = 0;
        for (byte segment : segments) {
            payloadSize += segment & 0xff;
        }
        ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadSize);
        if (readFully(payloadBuffer) < payloadSize) {
            throw new EOFException("Short Ogg page payload");
        }
        byte[] payload = payloadBuffer.array();

        var packets = new ArrayList<byte[]>();
        int payloadOffset = 0;
        byte[] packet = partialPacket;
        for (byte segment : segments) {
            int len = segment & 0xff;
            int oldLength = packet.length;
            packet = Arrays.copyOf(packet, oldLength + len);
            System.arraycopy(payload, payloadOffset, packet, oldLength, len);
            payloadOffset += len;
            if (len < 255) {
                if (!isHeaderPacket(packet)) {
                    packets.add(packet);
                }
                packet = new byte[0];
            }
        }
        partialPacket = packet;
        return packets;
    }

    private static boolean isHeaderPacket(byte[] packet) {
        return startsWith(packet, "OpusHead") || startsWith(packet, "OpusTags");
    }

    private static boolean startsWith(byte[] packet, String prefix) {
        byte[] bytes = prefix.getBytes(StandardCharsets.US_ASCII);
        if (packet.length < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (packet[i] != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private int readFully(ByteBuffer buffer) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
