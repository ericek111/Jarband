import type { OpusPacket, Utterance } from './types';

type HistoryResult = {
  beforeMillis: number;
  limit: number;
  newestMillis: number;
  oldestMillis: number;
  hasOlder: boolean;
  utterances: Utterance[];
};

type RecordingFile = {
  channel: string;
  dbUrl: string;
  opusUrl: string;
  dbSize: number;
  opusSize: number;
  sampleRate: number;
  frameMillis: number;
};

type UdbRecord = {
  startMillis: number;
  opusOffset: number;
  durationUnits: number;
  averageSnrQ8_8: number;
};

type RecentFileResult = {
  utterances: Utterance[];
  hasOlder: boolean;
};

type RangeBlock = {
  start: number;
  end: number;
  data: ArrayBuffer;
};

const decoder = new TextDecoder();
const UDB_HEADER_BYTES = 4;
const UDB_RECORD_BYTES = 16;
const UDB_CHUNK_BYTES = 50 * 1024;
const OPUS_CHUNK_BYTES = 500 * 1024;
const MAX_CACHED_BLOCKS_PER_FILE = 8;
const DURATION_UNIT_MILLIS = 100;
const DURATION_INDEFINITE = 0xffff;
const rangeCache = new Map<string, RangeBlock[]>();
const recordingFilesCache = new Map<string, Promise<RecordingFile[]>>();

export async function fetchRecentHistory(channels: string[], beforeMillis: number, limit: number) {
  const safeBefore = Number.isFinite(beforeMillis) ? beforeMillis : Number.POSITIVE_INFINITY;
  const files = await fetchRecordingFiles(channels);
  const utterances: Utterance[] = [];
  let hasOlder = false;
  for (const file of files) {
    const result = await readRecentFileUtterances(file, safeBefore, limit);
    utterances.push(...result.utterances);
    hasOlder ||= result.hasOlder;
  }
  utterances.sort((a, b) => b.startMillis - a.startMillis || a.channel.localeCompare(b.channel));
  const page = utterances.slice(0, limit);
  hasOlder ||= utterances.length > limit;
  return {
    beforeMillis: Number.isFinite(beforeMillis) ? beforeMillis : 0,
    limit,
    newestMillis: page[0]?.startMillis ?? 0,
    oldestMillis: page.at(-1)?.startMillis ?? 0,
    hasOlder,
    utterances: page
  } satisfies HistoryResult;
}

export async function fetchHistoryIndex(channels: string[], fromMillis: number, toMillis: number) {
  const files = await fetchRecordingFiles(channels);
  const utterances: Utterance[] = [];
  for (const file of files) {
    utterances.push(...await readAllFileUtterances(file, fromMillis, toMillis));
  }
  return utterances.sort((a, b) => a.startMillis - b.startMillis || a.channel.localeCompare(b.channel));
}

export async function fetchUtterancePackets(utterance: Utterance, streamKey: string, signal?: AbortSignal) {
  if (!utterance.opusUrl || utterance.startOffset === undefined || utterance.endOffset === undefined) {
    throw new Error('History row is missing Opus byte-range metadata.');
  }
  const fileSize = utterance.opusSize ?? Number.MAX_SAFE_INTEGER;
  const bytes = await fetchRangeCached(utterance.opusUrl, fileSize,
    utterance.startOffset, utterance.endOffset, OPUS_CHUNK_BYTES, signal);
  return parseOggOpusPackets(bytes, utterance, streamKey);
}

function historyParams(channels: string[]) {
  const params = new URLSearchParams();
  for (const channel of channels) {
    params.append('channel', channel);
  }
  return params;
}

async function fetchRecordingFiles(channels: string[]) {
  const key = channelsKey(channels);
  const cached = recordingFilesCache.get(key);
  if (cached) return cached;

  const request = fetch(`/airband/api/recording-files?${historyParams(channels)}`)
    .then(response => {
      if (!response.ok) throw new Error(`Recording file list failed: ${response.status}`);
      return response.json();
    })
    .then((body: { files: RecordingFile[] }) => body.files)
    .catch(error => {
      recordingFilesCache.delete(key);
      throw error;
    });
  recordingFilesCache.set(key, request);
  return request;
}

function channelsKey(channels: string[]) {
  return channels.slice().sort().join('\u0000');
}

async function readRecentFileUtterances(file: RecordingFile, beforeMillis: number, limit: number) {
  if (file.dbSize <= UDB_HEADER_BYTES) return { utterances: [], hasOlder: false } satisfies RecentFileResult;
  const recordsPerChunk = Math.max(1, Math.floor(UDB_CHUNK_BYTES / UDB_RECORD_BYTES));
  let endRecordExclusive = recordCount(file);
  const utterances: Utterance[] = [];

  while (endRecordExclusive > 0 && utterances.length < limit) {
    const startRecord = Math.max(0, endRecordExclusive - recordsPerChunk);
    const expectedRecords = endRecordExclusive - startRecord;
    const records = await readRecords(file, startRecord, endRecordExclusive, true);
    utterances.push(...recordsToUtterances(file, records, expectedRecords)
      .filter(utterance => utterance.startMillis < beforeMillis));
    endRecordExclusive = startRecord;
    if (records.length < expectedRecords) break;
  }
  return {
    utterances,
    hasOlder: endRecordExclusive > 0
  } satisfies RecentFileResult;
}

async function readAllFileUtterances(file: RecordingFile, fromMillis: number, toMillis: number) {
  const count = recordCount(file);
  if (count <= 0) return [];
  const records = await readRecords(file, 0, count, false);
  return recordsToUtterances(file, records, records.length)
    .filter(utterance => utterance.endMillis >= fromMillis && utterance.startMillis <= toMillis);
}

async function readRecords(file: RecordingFile, startRecord: number, endRecordExclusive: number, includeLookahead: boolean) {
  const count = recordCount(file);
  const fetchEndRecordExclusive = includeLookahead
    ? Math.min(count, endRecordExclusive + 1)
    : endRecordExclusive;
  const start = UDB_HEADER_BYTES + startRecord * UDB_RECORD_BYTES;
  const end = UDB_HEADER_BYTES + fetchEndRecordExclusive * UDB_RECORD_BYTES;
  const bytes = await fetchRangeCached(file.dbUrl, file.dbSize, start, end, UDB_CHUNK_BYTES);
  return parseUdbRecords(bytes);
}

function parseUdbRecords(buffer: ArrayBuffer) {
  const view = new DataView(buffer);
  const records: UdbRecord[] = [];
  for (let offset = 0; offset + UDB_RECORD_BYTES <= view.byteLength; offset += UDB_RECORD_BYTES) {
    records.push({
      startMillis: Number(view.getBigUint64(offset, true)),
      opusOffset: view.getUint32(offset + 8, true),
      durationUnits: view.getUint16(offset + 12, true),
      averageSnrQ8_8: view.getInt16(offset + 14, true)
    });
  }
  return records;
}

function recordsToUtterances(file: RecordingFile, records: UdbRecord[], emitCount: number) {
  return records.slice(0, emitCount).map((record, index) => {
    const durationMillis = record.durationUnits === DURATION_INDEFINITE
      ? -1
      : record.durationUnits * DURATION_UNIT_MILLIS;
    return {
      channel: file.channel,
      startMillis: record.startMillis,
      endMillis: durationMillis > 0 ? record.startMillis + durationMillis : record.startMillis,
      durationMillis,
      averageSnrDb: record.averageSnrQ8_8 / 256,
      opusUrl: file.opusUrl,
      opusSize: file.opusSize,
      startOffset: record.opusOffset,
      endOffset: records[index + 1]?.opusOffset ?? file.opusSize,
      sampleRate: file.sampleRate,
      frameMillis: file.frameMillis
    } satisfies Utterance;
  }).filter(utterance => utterance.endOffset > utterance.startOffset);
}

function recordCount(file: RecordingFile) {
  return Math.max(0, Math.floor((file.dbSize - UDB_HEADER_BYTES) / UDB_RECORD_BYTES));
}

async function fetchRangeCached(url: string, fileSize: number, start: number, end: number,
                                blockBytes: number, signal?: AbortSignal) {
  const cached = cachedSlice(url, start, end);
  if (cached) return cached;

  const alignedStart = Math.max(0, Math.floor(start / blockBytes) * blockBytes);
  const alignedEnd = Math.min(fileSize, Math.ceil(end / blockBytes) * blockBytes);
  const blockStart = alignedEnd === fileSize && alignedEnd - alignedStart <= blockBytes
    ? Math.max(0, fileSize - blockBytes)
    : alignedStart;
  const blockEnd = alignedEnd;
  const rangeLength = blockEnd - blockStart;
  const range = blockEnd === fileSize
    ? `bytes=-${rangeLength}`
    : `bytes=${blockStart}-${blockEnd - 1}`;

  const response = await fetch(url, { signal, headers: { Range: range } });
  if (!response.ok && response.status !== 206) {
    throw new Error(`Range request failed: ${response.status}`);
  }
  const data = await response.arrayBuffer();
  addCachedBlock(url, { start: blockStart, end: blockStart + data.byteLength, data });
  const fetched = cachedSlice(url, start, end);
  if (!fetched) {
    throw new Error('Fetched range did not cover requested bytes.');
  }
  return fetched;
}

function cachedSlice(url: string, start: number, end: number) {
  const block = rangeCache.get(url)?.find(item => item.start <= start && item.end >= end);
  if (!block) return null;
  return block.data.slice(start - block.start, end - block.start);
}

function addCachedBlock(url: string, block: RangeBlock) {
  const blocks = rangeCache.get(url) ?? [];
  blocks.push(block);
  blocks.sort((a, b) => b.end - b.start - (a.end - a.start));
  while (blocks.length > MAX_CACHED_BLOCKS_PER_FILE) {
    blocks.pop();
  }
  rangeCache.set(url, blocks);
}

function parseOggOpusPackets(buffer: ArrayBuffer, utterance: Utterance, streamKey: string) {
  const bytes = new Uint8Array(buffer);
  const packets: OpusPacket[] = [];
  let offset = 0;
  let partial = new Uint8Array(0);
  let packetMillis = utterance.startMillis;
  const frameMillis = utterance.frameMillis ?? 20;
  const sampleRate = utterance.sampleRate ?? 16_000;

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
      const next = new Uint8Array(partial.length + len);
      next.set(partial, 0);
      next.set(bytes.subarray(cursor, cursor + len), partial.length);
      cursor += len;
      partial = next;

      if (len < 255) {
        if (!isHeaderPacket(partial)) {
          packets.push({
            streamKey,
            channelName: utterance.channel,
            channelId: 0,
            sampleRate,
            unixMillis: packetMillis,
            durationMillis: frameMillis,
            packet: partial.slice()
          });
          packetMillis += frameMillis;
        }
        partial = new Uint8Array(0);
      }
    }
    offset = payloadOffset + payloadSize;
  }
  return packets;
}

function isHeaderPacket(packet: Uint8Array) {
  const prefix = decoder.decode(packet.subarray(0, 8));
  return prefix === 'OpusHead' || prefix === 'OpusTags';
}
