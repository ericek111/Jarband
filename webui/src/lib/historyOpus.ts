import { SparseHttpRangeCache } from './httpRangeCache';
import type { OpusPacket, Utterance } from './types';

type PacketWindowRange = {
  request: HistoryPacketWindowRequest;
  start: number;
  parseEnd: number;
  fetchEnd: number;
  packetMillis: number;
};

export type HistoryPacketWindowRequest = {
  id: string;
  utterance: Utterance;
  streamKey: string;
  byteOffset?: number;
  packetMillis?: number;
  targetMillis?: number;
  chunkEnd?: number;
};

export type HistoryPacketWindowUpdate = {
  id: string;
  nextOffset: number;
  nextMillis: number;
  complete: boolean;
};

export type HistoryPacketWindow = {
  packets: OpusPacket[];
  updates: HistoryPacketWindowUpdate[];
};

export const HISTORY_OPUS_CHUNK_BYTES = 64 * 1024;

export class HistoryOpusPackets {
  private readonly decoder = new TextDecoder();

  constructor(private readonly ranges: SparseHttpRangeCache) {
  }

  async utterance(utterance: Utterance, streamKey: string, signal?: AbortSignal) {
    if (!utterance.opusUrl || utterance.startOffset === undefined) {
      throw new Error('History row is missing Opus byte-range metadata.');
    }
    const bytes = utterance.endOffset === undefined
      ? (await this.ranges.fetchOpenEnded(utterance.opusUrl, utterance.startOffset, { signal })).data
      : (await this.ranges.fetchRange(utterance.opusUrl, utterance.startOffset, utterance.endOffset, { signal })).data;
    return this.parsePackets(bytes, utterance, streamKey);
  }

  async window(requests: HistoryPacketWindowRequest[],
               horizonMillis: number,
               minPacketMillis: number,
               signal?: AbortSignal): Promise<HistoryPacketWindow> {
    const groups = new Map<string, HistoryPacketWindowRequest[]>();
    for (const request of requests) {
      const url = request.utterance.opusUrl;
      if (!url || request.utterance.startOffset === undefined) continue;
      const group = groups.get(url) ?? [];
      group.push(request);
      groups.set(url, group);
    }

    const chunks = await Promise.all([...groups.entries()].map(([url, group]) =>
      this.fetchWindowGroup(url, group, horizonMillis, minPacketMillis, signal)));
    return {
      packets: chunks.flatMap(chunk => chunk.packets)
        .sort((a, b) => a.unixMillis - b.unixMillis || a.channelName.localeCompare(b.channelName)),
      updates: chunks.flatMap(chunk => chunk.updates)
    };
  }

  private async fetchWindowGroup(url: string, requests: HistoryPacketWindowRequest[],
                                 horizonMillis: number, minPacketMillis: number,
                                 signal?: AbortSignal): Promise<HistoryPacketWindow> {
    const ranges = requests
      .map(request => this.packetRange(request, horizonMillis))
      .filter(range => range.parseEnd > range.start)
      .sort((a, b) => a.start - b.start || a.parseEnd - b.parseEnd);
    if (!ranges.length) return { packets: [], updates: [] };

    const start = ranges[0].start;
    let end = ranges[0].fetchEnd;
    for (const range of ranges) {
      end = Math.max(end, range.fetchEnd);
    }
    // Decode only each utterance slice, but fetch one forward span per Opus file
    // so nearby future utterances are cached without separate network requests.
    const result = await this.ranges.fetchRangeAvailable(url, start, end, { signal });
    const packets: OpusPacket[] = [];
    const updates: HistoryPacketWindowUpdate[] = [];

    for (const range of ranges) {
      if (range.start >= result.end) {
        updates.push({
          id: range.request.id,
          nextOffset: range.start,
          nextMillis: range.packetMillis,
          complete: this.rangeComplete(range, 0, range.start, range.packetMillis, result.total)
        });
        continue;
      }
      const sliceStart = range.start - result.start;
      const sliceEnd = Math.min(range.parseEnd, result.end) - result.start;
      const parsed = this.parseChunk(result.data.slice(sliceStart, sliceEnd),
        range.request.utterance, range.request.streamKey, range.packetMillis, minPacketMillis);
      const nextOffset = range.start + parsed.consumedBytes;
      packets.push(...parsed.packets);
      updates.push({
        id: range.request.id,
        nextOffset,
        nextMillis: parsed.nextMillis,
        complete: this.rangeComplete(range, parsed.consumedBytes, nextOffset, parsed.nextMillis, result.total)
      });
    }

    return { packets, updates };
  }

  private packetRange(request: HistoryPacketWindowRequest, horizonMillis: number): PacketWindowRange {
    const utterance = request.utterance;
    const start = request.byteOffset ?? utterance.startOffset ?? 0;
    const packetMillis = request.packetMillis ?? utterance.startMillis;
    // Compact playback asks for a fixed byte chunk. In that mode the caller's
    // chunk boundary, not utterance duration, controls how far we fetch/parse.
    if (request.chunkEnd !== undefined) {
      return {
        request,
        start,
        parseEnd: utterance.endOffset === undefined ? request.chunkEnd : Math.min(utterance.endOffset, request.chunkEnd),
        fetchEnd: request.chunkEnd,
        packetMillis
      };
    }

    const targetMillis = Math.max(packetMillis, request.targetMillis ?? horizonMillis);
    let parseEnd = start + HISTORY_OPUS_CHUNK_BYTES;
    if (utterance.endOffset !== undefined) {
      parseEnd = utterance.endMillis <= targetMillis
        ? utterance.endOffset
        : Math.min(utterance.endOffset,
          Math.max(start + HISTORY_OPUS_CHUNK_BYTES, this.estimatedOffsetAtMillis(utterance, targetMillis)));
    }
    return {
      request,
      start,
      parseEnd,
      fetchEnd: Math.max(parseEnd, start + HISTORY_OPUS_CHUNK_BYTES),
      packetMillis
    };
  }

  private rangeComplete(range: PacketWindowRange, consumedBytes: number,
                        nextOffset: number, nextMillis: number, total: number | null) {
    const utterance = range.request.utterance;
    if (utterance.endOffset !== undefined) return nextOffset >= utterance.endOffset;
    if (!this.isClosedUtterance(utterance)) return false;
    if (consumedBytes === 0) return total !== null && nextOffset >= total;
    return nextMillis >= utterance.endMillis || (total !== null && nextOffset >= total);
  }

  private estimatedOffsetAtMillis(utterance: Utterance, millis: number) {
    if (utterance.startOffset === undefined || utterance.endOffset === undefined) {
      return utterance.startOffset ?? 0;
    }
    const durationMillis = utterance.endMillis - utterance.startMillis;
    if (durationMillis <= 0) return utterance.startOffset;
    const fraction = clamp((millis - utterance.startMillis) / durationMillis, 0, 1);
    return utterance.startOffset + Math.ceil((utterance.endOffset - utterance.startOffset) * fraction);
  }

  private isClosedUtterance(utterance: Utterance) {
    return utterance.endMillis > utterance.startMillis
      && (utterance.durationMillis === undefined || utterance.durationMillis > 0);
  }

  private parsePackets(buffer: ArrayBuffer, utterance: Utterance, streamKey: string) {
    return this.parseChunk(buffer, utterance, streamKey, utterance.startMillis, Number.NEGATIVE_INFINITY).packets;
  }

  private parseChunk(buffer: ArrayBuffer, utterance: Utterance, streamKey: string,
                     startPacketMillis: number, minPacketMillis: number) {
    const bytes = new Uint8Array(buffer);
    const packets: OpusPacket[] = [];
    let offset = 0;
    let partial = new Uint8Array(0);
    let packetMillis = startPacketMillis;
    if (utterance.frameMillis === undefined || utterance.sampleRate === undefined) {
      throw new Error('History row is missing Opus codec metadata.');
    }
    const frameMillis = utterance.frameMillis;
    const sampleRate = utterance.sampleRate;

    while (offset + 27 <= bytes.length) {
      if (bytes[offset] !== 0x4f || bytes[offset + 1] !== 0x67
          || bytes[offset + 2] !== 0x67 || bytes[offset + 3] !== 0x53) {
        break;
      }
      const segmentCount = bytes[offset + 26];
      const segmentTableOffset = offset + 27;
      const payloadOffset = segmentTableOffset + segmentCount;
      if (payloadOffset > bytes.length) break;

      let payloadSize = 0;
      for (let i = 0; i < segmentCount; i++) {
        payloadSize += bytes[segmentTableOffset + i];
      }
      if (payloadOffset + payloadSize > bytes.length) break;

      let cursor = payloadOffset;
      for (let i = 0; i < segmentCount; i++) {
        const len = bytes[segmentTableOffset + i];
        // Ogg lacing uses 255-byte continuations; an Opus packet is complete
        // only when a lacing value below 255 is encountered.
        const next = new Uint8Array(partial.length + len);
        next.set(partial, 0);
        next.set(bytes.subarray(cursor, cursor + len), partial.length);
        cursor += len;
        partial = next;

        if (len < 255) {
          if (!this.isHeaderPacket(partial)) {
            const insideUtterance = utterance.endMillis <= utterance.startMillis || packetMillis < utterance.endMillis;
            if (insideUtterance && packetMillis + frameMillis >= minPacketMillis) {
              packets.push({
                streamKey,
                channelName: utterance.channel,
                channelId: 0,
                sampleRate,
                unixMillis: packetMillis,
                durationMillis: frameMillis,
                packet: partial.slice()
              });
            }
            packetMillis += frameMillis;
          }
          partial = new Uint8Array(0);
        }
      }
      offset = payloadOffset + payloadSize;
    }
    return { packets, consumedBytes: offset, nextMillis: packetMillis };
  }

  private isHeaderPacket(packet: Uint8Array) {
    const prefix = this.decoder.decode(packet.subarray(0, 8));
    return prefix === 'OpusHead' || prefix === 'OpusTags';
  }
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}
