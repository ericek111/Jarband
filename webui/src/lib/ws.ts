import type { OpusPacket, ServerMessage } from './types';

const MAGIC = 0x4a424f50;
const decoder = new TextDecoder();

export function connectSocket(onMessage: (message: ServerMessage) => void, onPacket: (packet: OpusPacket) => void, onStatus: (status: string) => void) {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(`${protocol}//${location.host}/airband/ws`);
  socket.binaryType = 'arraybuffer';

  socket.addEventListener('open', () => {
    onStatus('Connected');
    send(socket, { type: 'list_channels' });
  });
  socket.addEventListener('close', () => {
    onStatus('Disconnected; reconnecting...');
    setTimeout(() => connectSocket(onMessage, onPacket, onStatus), 1500);
  });
  socket.addEventListener('message', event => {
    if (typeof event.data === 'string') {
      onMessage(JSON.parse(event.data) as ServerMessage);
    } else {
      parsePackets(event.data as ArrayBuffer).forEach(onPacket);
    }
  });

  return socket;
}

export function send(socket: WebSocket | null, message: unknown) {
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(message));
  }
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
