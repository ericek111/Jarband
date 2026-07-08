import type { OpusPacket } from './types';

export async function opusPacketsToWav(packets: OpusPacket[]) {
  if (!packets.length) {
    throw new Error('No Opus packets to decode.');
  }
  if (typeof globalThis.AudioDecoder !== 'function') {
    throw new Error('WebCodecs AudioDecoder is unavailable.');
  }

  const chunks: Float32Array[] = [];
  let sampleRate = packets[0].sampleRate;
  const decoder = new AudioDecoder({
    output: frame => {
      sampleRate = frame.sampleRate;
      const samples = new Float32Array(frame.numberOfFrames);
      frame.copyTo(samples, { planeIndex: 0 });
      chunks.push(samples);
      frame.close();
    },
    error: error => {
      throw error;
    }
  });

  decoder.configure({ codec: 'opus', sampleRate, numberOfChannels: 1 });
  for (const packet of packets) {
    decoder.decode(new EncodedAudioChunk({
      type: 'key',
      timestamp: packet.unixMillis * 1000,
      duration: packet.durationMillis * 1000,
      data: packet.packet
    }));
  }
  await decoder.flush();
  decoder.close();

  const sampleCount = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const pcmBytes = sampleCount * 2;
  const wav = new ArrayBuffer(44 + pcmBytes);
  const view = new DataView(wav);
  writeAscii(view, 0, 'RIFF');
  view.setUint32(4, 36 + pcmBytes, true);
  writeAscii(view, 8, 'WAVE');
  writeAscii(view, 12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeAscii(view, 36, 'data');
  view.setUint32(40, pcmBytes, true);

  let offset = 44;
  for (const chunk of chunks) {
    for (const sample of chunk) {
      const clipped = Math.max(-1, Math.min(1, sample));
      view.setInt16(offset, clipped < 0 ? clipped * 0x8000 : clipped * 0x7fff, true);
      offset += 2;
    }
  }
  return new Blob([wav], { type: 'audio/wav' });
}

function writeAscii(view: DataView, offset: number, text: string) {
  for (let i = 0; i < text.length; i++) {
    view.setUint8(offset + i, text.charCodeAt(i));
  }
}
