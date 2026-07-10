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

const decoder = new TextDecoder();
const UDB_HEADER_BYTES = 4;
const UDB_RECORD_BYTES = 16;
const UDB_CHUNK_BYTES = 10 * 1024;
const DURATION_UNIT_MILLIS = 100;
const DURATION_INDEFINITE = 0xffff;

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
  const response = await fetch(utterance.opusUrl, {
    signal,
    headers: {
      Range: `bytes=${utterance.startOffset}-${utterance.endOffset - 1}`
    }
  });
  if (!response.ok && response.status !== 206) {
    throw new Error(`Opus range request failed: ${response.status}`);
  }
  return parseOggOpusPackets(await response.arrayBuffer(), utterance, streamKey);
}

function historyParams(channels: string[]) {
  const params = new URLSearchParams();
  for (const channel of channels) {
    params.append('channel', channel);
  }
  return params;
}

async function fetchRecordingFiles(channels: string[]) {
  const response = await fetch(`/airband/api/recording-files?${historyParams(channels)}`);
  if (!response.ok) throw new Error(`Recording file list failed: ${response.status}`);
  return (await response.json() as { files: RecordingFile[] }).files;
}

async function readRecentFileUtterances(file: RecordingFile, beforeMillis: number, limit: number) {
  if (file.dbSize <= UDB_HEADER_BYTES) return { utterances: [], hasOlder: false } satisfies RecentFileResult;
  const recordsPerChunk = Math.max(1, Math.floor(UDB_CHUNK_BYTES / UDB_RECORD_BYTES));
  let endRecordExclusive = recordCount(file);
  const utterances: Utterance[] = [];

  while (endRecordExclusive > 0 && utterances.length < limit) {
    const startRecord = Math.max(0, endRecordExclusive - recordsPerChunk);
    const expectedRecords = endRecordExclusive - startRecord;
    const records = await readRecords(file, startRecord, endRecordExclusive);
    utterances.push(...recordsToUtterances(file, records, startRecord)
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
  const records = await readRecords(file, 0, count);
  return recordsToUtterances(file, records, 0)
    .filter(utterance => utterance.endMillis >= fromMillis && utterance.startMillis <= toMillis);
}

async function readRecords(file: RecordingFile, startRecord: number, endRecordExclusive: number) {
  const start = UDB_HEADER_BYTES + startRecord * UDB_RECORD_BYTES;
  const end = UDB_HEADER_BYTES + endRecordExclusive * UDB_RECORD_BYTES - 1;
  const bytes = endRecordExclusive * UDB_RECORD_BYTES - startRecord * UDB_RECORD_BYTES;
  const range = endRecordExclusive === recordCount(file)
    ? `bytes=-${bytes}`
    : `bytes=${start}-${end}`;
  const response = await fetch(file.dbUrl, { headers: { Range: range } });
  if (!response.ok && response.status !== 206) {
    throw new Error(`UDB range request failed: ${response.status}`);
  }
  return parseUdbRecords(await response.arrayBuffer());
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

function recordsToUtterances(file: RecordingFile, records: UdbRecord[], startRecord: number) {
  return records.map((record, index) => {
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
      endOffset: records[index + 1]?.opusOffset ?? file.opusSize,
      sampleRate: file.sampleRate,
      frameMillis: file.frameMillis
    } satisfies Utterance;
  }).filter(utterance => utterance.endOffset > utterance.startOffset);
}

function recordCount(file: RecordingFile) {
  return Math.max(0, Math.floor((file.dbSize - UDB_HEADER_BYTES) / UDB_RECORD_BYTES));
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
