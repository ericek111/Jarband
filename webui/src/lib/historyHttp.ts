import { SparseHttpRangeCache, type HttpRangeFetchResult } from './httpRangeCache';
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

type ParsedUdbRecords = {
  records: UdbRecord[];
  firstRecordOffset: number | null;
};

export type HistoryPacketChunk = {
  packets: OpusPacket[];
  nextOffset: number;
  nextMillis: number;
  complete: boolean;
};

const decoder = new TextDecoder();
const UDB_HEADER_BYTES = 4;
const UDB_RECORD_BYTES = 16;
const UDB_CHUNK_BYTES = 50 * 1024;
const OPUS_CHUNK_BYTES = 64 * 1024;
const defaultRecentHistorySearchMillis = 2 * 60 * 60 * 1000;
const DURATION_UNIT_MILLIS = 100;
const DURATION_INDEFINITE = 0xffff;
const rangeCache = new SparseHttpRangeCache();
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
  if (!utterance.opusUrl || utterance.startOffset === undefined) {
    throw new Error('History row is missing Opus byte-range metadata.');
  }
  const bytes = utterance.endOffset === undefined
    ? (await rangeCache.fetchOpenEnded(utterance.opusUrl, utterance.startOffset, { signal })).data
    : (await rangeCache.fetchRange(utterance.opusUrl, utterance.startOffset, utterance.endOffset, { signal })).data;
  return parseOggOpusPackets(bytes, utterance, streamKey);
}

export async function fetchUtterancePacketChunk(utterance: Utterance, streamKey: string,
                                                byteOffset: number | undefined,
                                                packetMillis: number | undefined,
                                                signal: AbortSignal | undefined,
                                                minPacketMillis: number): Promise<HistoryPacketChunk> {
  if (!utterance.opusUrl || utterance.startOffset === undefined) {
    throw new Error('History row is missing Opus byte-range metadata.');
  }
  const start = byteOffset ?? utterance.startOffset;
  const endLimit = utterance.endOffset ?? Number.POSITIVE_INFINITY;
  if (start >= endLimit) {
    return {
      packets: [],
      nextOffset: start,
      nextMillis: packetMillis ?? utterance.startMillis,
      complete: true
    };
  }

  const requestedEnd = Math.min(start + OPUS_CHUNK_BYTES, endLimit);
  const result = await rangeCache.fetchRangeAvailable(utterance.opusUrl, start, requestedEnd, { signal });
  const parsed = parseOggOpusPacketChunk(result.data, utterance, streamKey,
    packetMillis ?? utterance.startMillis, minPacketMillis);
  const nextOffset = result.start + parsed.consumedBytes;
  const complete = parsed.consumedBytes === 0
    || nextOffset >= endLimit
    || (result.total !== null && nextOffset >= result.total);
  return {
    packets: parsed.packets,
    nextOffset,
    nextMillis: parsed.nextMillis,
    complete
  };
}

export function invalidateRecordingFilesCache(channels?: string[]) {
  if (!channels?.length) {
    recordingFilesCache.clear();
    rangeCache.clear();
    return;
  }
  const changed = new Set(channels);
  for (const key of recordingFilesCache.keys()) {
    if (key.split('\u0000').some(channel => changed.has(channel))) {
      recordingFilesCache.delete(key);
    }
  }
}

export function historyRangeCacheStats() {
  return rangeCache.stats();
}

export function appendHistoryRecordingBytes(recordingUrl: string, offset: number, data: Uint8Array) {
  if (data.byteLength === 0) {
    rangeCache.truncate(recordingUrl, offset);
    return;
  }
  const end = offset + data.byteLength;
  rangeCache.putRange(recordingUrl, offset, data, Math.max(end, rangeCache.knownTotal(recordingUrl) ?? 0),
    { overwrite: recordingUrl.endsWith('.udb') });
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
  const recordsPerChunk = Math.max(1, Math.floor(UDB_CHUNK_BYTES / UDB_RECORD_BYTES));
  let endByteExclusive: number | null = null;
  const utterances: Utterance[] = [];
  let hasOlder = false;

  while (utterances.length < limit) {
    let result: HttpRangeFetchResult;
    if (endByteExclusive === null) {
      result = await rangeCache.fetchSuffix(file.dbUrl, UDB_CHUNK_BYTES);
    } else {
      result = await rangeCache.fetchRange(file.dbUrl, Math.max(0, endByteExclusive - UDB_CHUNK_BYTES),
        endByteExclusive, { blockSize: UDB_CHUNK_BYTES });
    }
    if (result.total === null || result.total <= UDB_HEADER_BYTES) break;
    const parsed = parseUdbRecords(result.data, result.start);
    utterances.push(...recordsToUtterances(file, parsed.records, parsed.records.length)
      .filter(utterance => utterance.startMillis < beforeMillis));
    if (result.start <= UDB_HEADER_BYTES) break;
    const nextEndByteExclusive: number = parsed.firstRecordOffset ?? result.start;
    hasOlder = nextEndByteExclusive > UDB_HEADER_BYTES;
    if (nextEndByteExclusive <= UDB_HEADER_BYTES) break;
    endByteExclusive = nextEndByteExclusive;
  }
  return {
    utterances,
    hasOlder
  } satisfies RecentFileResult;
}

async function readAllFileUtterances(file: RecordingFile, fromMillis: number, toMillis: number) {
  const count = await udbRecordCount(file);
  if (count <= 0) return [];
  const recordsPerChunk = Math.max(1, Math.floor(UDB_CHUNK_BYTES / UDB_RECORD_BYTES));
  const preferTailScan = toMillis >= Date.now() - defaultRecentHistorySearchMillis;

  if (preferTailScan) {
    const tailStartRecord = Math.max(0, count - recordsPerChunk);
    const tailRecords = await readRecords(file, tailStartRecord, count, false, count);
    const tailOldestMillis = tailRecords[0]?.startMillis ?? Number.POSITIVE_INFINITY;
    if (tailRecords.length && tailOldestMillis <= toMillis) {
      return readAllFileUtterancesBackward(file, fromMillis, toMillis, tailStartRecord, tailRecords, count, recordsPerChunk);
    }
  }

  const firstAfterFrom = await lowerBoundRecordStart(file, count, fromMillis);
  let startRecord = Math.max(0, firstAfterFrom - 1);
  const utterances: Utterance[] = [];

  while (startRecord < count) {
    const endRecordExclusive = Math.min(count, startRecord + recordsPerChunk);
    const records = await readRecords(file, startRecord, endRecordExclusive, true, count);
    if (!records.length) break;
    const emitCount = Math.min(records.length, endRecordExclusive - startRecord);
    const chunkUtterances = recordsToUtterances(file, records, emitCount)
      .filter(utterance => utterance.endMillis >= fromMillis && utterance.startMillis <= toMillis);
    utterances.push(...chunkUtterances);
    const lastEmittedRecord = records[Math.max(0, emitCount - 1)];
    if (lastEmittedRecord && lastEmittedRecord.startMillis > toMillis) break;
    startRecord = endRecordExclusive;
  }
  return utterances;
}

async function readAllFileUtterancesBackward(file: RecordingFile, fromMillis: number, toMillis: number,
                                             tailStartRecord: number, tailRecords: UdbRecord[],
                                             count: number, recordsPerChunk: number) {
  let startRecord = tailStartRecord;
  let records = tailRecords;
  let emitCount = count - tailStartRecord;
  const utterances: Utterance[] = [];

  while (records.length) {
    const chunkUtterances = recordsToUtterances(file, records, emitCount)
      .filter(utterance => utterance.endMillis >= fromMillis && utterance.startMillis <= toMillis);
    utterances.unshift(...chunkUtterances);
    if (records[0].startMillis <= fromMillis || startRecord <= 0) break;
    const nextStartRecord = Math.max(0, startRecord - recordsPerChunk);
    emitCount = startRecord - nextStartRecord;
    records = await readRecords(file, nextStartRecord, startRecord, true, count);
    startRecord = nextStartRecord;
  }
  return utterances.sort((a, b) => a.startMillis - b.startMillis || a.channel.localeCompare(b.channel));
}

async function udbRecordCount(file: RecordingFile) {
  const knownTotal = rangeCache.knownTotal(file.dbUrl);
  if (knownTotal !== null) {
    return Math.max(0, Math.floor((knownTotal - UDB_HEADER_BYTES) / UDB_RECORD_BYTES));
  }
  const result = await rangeCache.fetchSuffix(file.dbUrl, 1);
  if (result.total === null) {
    throw new Error(`Range response for ${file.dbUrl} did not include a total size.`);
  }
  return Math.max(0, Math.floor((result.total - UDB_HEADER_BYTES) / UDB_RECORD_BYTES));
}

async function lowerBoundRecordStart(file: RecordingFile, count: number, targetMillis: number) {
  let low = 0;
  let high = count;
  while (low < high) {
    const mid = Math.floor((low + high) / 2);
    const record = (await readRecords(file, mid, mid + 1, false, count))[0];
    if (!record || record.startMillis >= targetMillis) {
      high = mid;
    } else {
      low = mid + 1;
    }
  }
  return low;
}

async function readRecords(file: RecordingFile, startRecord: number, endRecordExclusive: number,
                           includeLookahead: boolean, count: number) {
  const fetchEndRecordExclusive = includeLookahead
    ? Math.min(count, endRecordExclusive + 1)
    : endRecordExclusive;
  if (fetchEndRecordExclusive <= startRecord) return [];
  const start = UDB_HEADER_BYTES + startRecord * UDB_RECORD_BYTES;
  const end = UDB_HEADER_BYTES + fetchEndRecordExclusive * UDB_RECORD_BYTES;
  const bytes = await fetchRangeCached(file.dbUrl, start, end, UDB_CHUNK_BYTES);
  return parseUdbRecords(bytes, start).records;
}

function parseUdbRecords(buffer: ArrayBuffer, absoluteStart: number): ParsedUdbRecords {
  const view = new DataView(buffer);
  const records: UdbRecord[] = [];
  const firstPossible = Math.max(UDB_HEADER_BYTES, absoluteStart);
  const misalignment = (firstPossible - UDB_HEADER_BYTES) % UDB_RECORD_BYTES;
  const firstRecordOffset = firstPossible + (misalignment === 0 ? 0 : UDB_RECORD_BYTES - misalignment);
  for (let absoluteOffset = firstRecordOffset; absoluteOffset + UDB_RECORD_BYTES <= absoluteStart + view.byteLength;
       absoluteOffset += UDB_RECORD_BYTES) {
    const offset = absoluteOffset - absoluteStart;
    records.push({
      startMillis: Number(view.getBigUint64(offset, true)),
      opusOffset: view.getUint32(offset + 8, true),
      durationUnits: view.getUint16(offset + 12, true),
      averageSnrQ8_8: view.getInt16(offset + 14, true)
    });
  }
  return {
    records,
    firstRecordOffset: records.length ? firstRecordOffset : null
  };
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
      startOffset: record.opusOffset,
      endOffset: records[index + 1]?.opusOffset,
      sampleRate: file.sampleRate,
      frameMillis: file.frameMillis
    } satisfies Utterance;
  }).filter(utterance => utterance.endOffset === undefined || utterance.endOffset > utterance.startOffset);
}

async function fetchRangeCached(url: string, start: number, end: number, blockBytes: number, signal?: AbortSignal) {
  return (await rangeCache.fetchRange(url, start, end, { blockSize: blockBytes, signal })).data;
}

function parseOggOpusPackets(buffer: ArrayBuffer, utterance: Utterance, streamKey: string) {
  return parseOggOpusPacketChunk(buffer, utterance, streamKey, utterance.startMillis, Number.NEGATIVE_INFINITY).packets;
}

function parseOggOpusPacketChunk(buffer: ArrayBuffer, utterance: Utterance, streamKey: string,
                                 startPacketMillis: number, minPacketMillis: number) {
  const bytes = new Uint8Array(buffer);
  const packets: OpusPacket[] = [];
  let offset = 0;
  let partial = new Uint8Array(0);
  let packetMillis = startPacketMillis;
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

function isHeaderPacket(packet: Uint8Array) {
  const prefix = decoder.decode(packet.subarray(0, 8));
  return prefix === 'OpusHead' || prefix === 'OpusTags';
}
