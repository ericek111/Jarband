import type { OpusPacket, RecordingBytes, ServerMessage } from './types';

const MAGIC = 0x4a424f50;
const RECORDING_MAGIC = 0x4a425243;
const decoder = new TextDecoder();

export function connectSocket(
  onMessage: (message: ServerMessage) => void,
  onPacket: (packet: OpusPacket) => void,
  onRecordingBytes: (bytes: RecordingBytes) => void,
  onStatus: (status: string) => void,
  onSocket?: (socket: WebSocket) => void
) {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(`${protocol}//${location.host}/airband/ws`);
  socket.binaryType = 'arraybuffer';
  onSocket?.(socket);

  socket.addEventListener('open', () => {
    onStatus('Connected');
    send(socket, { type: 'list_channels' });
  });
  socket.addEventListener('close', () => {
    onStatus('Disconnected; reconnecting...');
    setTimeout(() => connectSocket(onMessage, onPacket, onRecordingBytes, onStatus, onSocket), 1500);
  });
  socket.addEventListener('message', event => {
    if (typeof event.data === 'string') {
      onMessage(JSON.parse(event.data) as ServerMessage);
    } else {
      const parsed = parseBinary(event.data as ArrayBuffer);
      parsed.packets.forEach(onPacket);
      parsed.recordingBytes.forEach(onRecordingBytes);
    }
  });

  return socket;
}

export function send(socket: WebSocket | null, message: unknown) {
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(message));
  }
}

function parseBinary(buffer: ArrayBuffer): { packets: OpusPacket[]; recordingBytes: RecordingBytes[] } {
  if (buffer.byteLength < 4) return { packets: [], recordingBytes: [] };
  const magic = new DataView(buffer).getUint32(0, true);
  if (magic === MAGIC) return { packets: parsePackets(buffer), recordingBytes: [] };
  if (magic === RECORDING_MAGIC) return { packets: [], recordingBytes: parseRecordingBytes(buffer) };
  return { packets: [], recordingBytes: [] };
}

function parsePackets(buffer: ArrayBuffer): OpusPacket[] {
  const view = new DataView(buffer);
  if (view.getUint32(0, true) !== MAGIC) return [];
  const version = view.getUint16(4, true);
  if (version === 1) {
    const parsed = parseRecord(buffer, 4);
    return parsed ? [parsed.packet] : [];
  }
  if (version !== 2) return [];

  const count = view.getUint16(6, true);
  let offset = 8;
  const packets: OpusPacket[] = [];
  for (let i = 0; i < count; i++) {
    const parsed = parseRecord(buffer, offset);
    if (!parsed) break;
    packets.push(parsed.packet);
    offset = parsed.nextOffset;
  }
  return packets;
}

function parseRecord(buffer: ArrayBuffer, offset: number): { packet: OpusPacket; nextOffset: number } | null {
  const view = new DataView(buffer);
  const recordVersion = view.getUint16(offset, true);
  if (recordVersion !== 1 && recordVersion !== 2) return null;

  const channelId = view.getUint16(offset + 2, true);
  const sampleRate = view.getUint32(offset + 4, true);
  const unixMillis = Number(view.getBigInt64(offset + 8, true));
  const durationMillis = view.getUint32(offset + 16, true);
  const packetLength = view.getUint32(offset + 20, true);
  const nameLength = view.getUint16(offset + 32, true);
  const channelName = decoder.decode(new Uint8Array(buffer, offset + 34, nameLength));
  let payloadOffset = offset + 34 + nameLength;
  let streamKey = channelName;

  if (recordVersion === 2) {
    const streamKeyLength = view.getUint16(payloadOffset, true);
    payloadOffset += 2;
    streamKey = decoder.decode(new Uint8Array(buffer, payloadOffset, streamKeyLength));
    payloadOffset += streamKeyLength;
  }

  return {
    packet: {
      streamKey,
      channelName,
      channelId,
      sampleRate,
      unixMillis,
      durationMillis,
      packet: new Uint8Array(buffer, payloadOffset, packetLength).slice()
    },
    nextOffset: payloadOffset + packetLength
  };
}

function parseRecordingBytes(buffer: ArrayBuffer): RecordingBytes[] {
  const view = new DataView(buffer);
  if (view.getUint32(0, true) !== RECORDING_MAGIC) return [];
  const version = view.getUint16(4, true);
  if (version !== 1) return [];

  const count = view.getUint16(6, true);
  let offset = 8;
  const records: RecordingBytes[] = [];
  for (let i = 0; i < count; i++) {
    const parsed = parseRecordingRecord(buffer, offset);
    if (!parsed) break;
    records.push(parsed.bytes);
    offset = parsed.nextOffset;
  }
  return records;
}

function parseRecordingRecord(buffer: ArrayBuffer, offset: number): { bytes: RecordingBytes; nextOffset: number } | null {
  if (offset + 16 > buffer.byteLength) return null;
  const view = new DataView(buffer);
  const recordVersion = view.getUint16(offset, true);
  if (recordVersion !== 1) return null;

  const channelId = view.getUint16(offset + 2, true);
  const recordingOffset = view.getUint32(offset + 4, true);
  const payloadLength = view.getUint32(offset + 8, true);
  const nameLength = view.getUint16(offset + 12, true);
  const fileLength = view.getUint16(offset + 14, true);
  const nameOffset = offset + 16;
  const fileOffset = nameOffset + nameLength;
  const payloadOffset = fileOffset + fileLength;
  const nextOffset = payloadOffset + payloadLength;
  if (nextOffset > buffer.byteLength) return null;

  const channelName = decoder.decode(new Uint8Array(buffer, nameOffset, nameLength));
  const fileName = decoder.decode(new Uint8Array(buffer, fileOffset, fileLength));
  return {
    bytes: {
      channelId,
      channelName,
      recordingUrl: `/airband/recordings/${channelName}/${fileName}`,
      offset: recordingOffset,
      data: new Uint8Array(buffer, payloadOffset, payloadLength).slice()
    },
    nextOffset
  };
}
