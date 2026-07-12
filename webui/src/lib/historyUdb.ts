import { SparseHttpRangeCache, type HttpRangeFetchResult } from './httpRangeCache';
import { RecordingFileDirectory, type RecordingFile } from './historyRecordingFiles';
import type { Utterance } from './types';

type HistoryResult = {
  beforeMillis: number;
  limit: number;
  newestMillis: number;
  oldestMillis: number;
  hasOlder: boolean;
  utterances: Utterance[];
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

type UdbTailRecords = {
  count: number;
  startRecord: number;
  records: UdbRecord[];
};

const UDB_HEADER_BYTES = 4;
const UDB_RECORD_BYTES = 16;
const UDB_CHUNK_BYTES = 50 * 1024;
const DEFAULT_RECENT_HISTORY_SEARCH_MILLIS = 2 * 60 * 60 * 1000;
const DURATION_UNIT_MILLIS = 100;
const DURATION_INDEFINITE = 0xffff;

export class HistoryUdbIndex {
  constructor(private readonly directory: RecordingFileDirectory,
              private readonly ranges: SparseHttpRangeCache) {
  }

  async recent(channels: string[], beforeMillis: number, limit: number) {
    const safeBefore = Number.isFinite(beforeMillis) ? beforeMillis : Number.POSITIVE_INFINITY;
    const files = await this.directory.list(channels);
    const utterances: Utterance[] = [];
    let hasOlder = false;
    for (const file of files) {
      const result = await this.readRecentFileUtterances(file, safeBefore, limit);
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

  async index(channels: string[], fromMillis: number, toMillis: number) {
    const files = await this.directory.list(channels);
    const utterances: Utterance[] = [];
    for (const file of files) {
      utterances.push(...await this.readAllFileUtterances(file, fromMillis, toMillis));
    }
    return utterances.sort((a, b) => a.startMillis - b.startMillis || a.channel.localeCompare(b.channel));
  }

  private async readRecentFileUtterances(file: RecordingFile, beforeMillis: number, limit: number) {
    let endByteExclusive: number | null = null;
    const utterances: Utterance[] = [];
    let hasOlder = false;

    while (utterances.length < limit) {
      let result: HttpRangeFetchResult;
      if (endByteExclusive === null) {
        result = await this.ranges.fetchSuffix(file.dbUrl, UDB_CHUNK_BYTES);
      } else {
        result = await this.ranges.fetchRange(file.dbUrl, Math.max(0, endByteExclusive - UDB_CHUNK_BYTES),
          endByteExclusive, { blockSize: UDB_CHUNK_BYTES });
      }
      if (result.total === null || result.total <= UDB_HEADER_BYTES) break;
      const parsed = this.parseUdbRecords(result.data, result.start);
      utterances.push(...this.recordsToUtterances(file, parsed.records, parsed.records.length)
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

  private async readAllFileUtterances(file: RecordingFile, fromMillis: number, toMillis: number) {
    const recordsPerChunk = Math.max(1, Math.floor(UDB_CHUNK_BYTES / UDB_RECORD_BYTES));
    const tail = await this.readTailRecords(file, recordsPerChunk);
    const count = tail.count;
    if (count <= 0) return [];

    // Near real time, the tail page is usually already cached or cheap to fetch;
    // scanning backward avoids a binary search that would touch old UDB ranges.
    const preferTailScan = toMillis >= Date.now() - DEFAULT_RECENT_HISTORY_SEARCH_MILLIS;
    if (preferTailScan) {
      const tailOldestMillis = tail.records[0]?.startMillis ?? Number.POSITIVE_INFINITY;
      if (tail.records.length && tailOldestMillis <= toMillis) {
        return this.readAllFileUtterancesBackward(file, fromMillis, toMillis,
          tail.startRecord, tail.records, count, recordsPerChunk);
      }
    }

    const firstAfterFrom = await this.lowerBoundRecordStart(file, count, fromMillis);
    let startRecord = Math.max(0, firstAfterFrom - 1);
    const utterances: Utterance[] = [];

    while (startRecord < count) {
      const endRecordExclusive = Math.min(count, startRecord + recordsPerChunk);
      const records = await this.readRecords(file, startRecord, endRecordExclusive, true, count);
      if (!records.length) break;
      const emitCount = Math.min(records.length, endRecordExclusive - startRecord);
      const chunkUtterances = this.recordsToUtterances(file, records, emitCount)
        .filter(utterance => utterance.endMillis >= fromMillis && utterance.startMillis <= toMillis);
      utterances.push(...chunkUtterances);
      const lastEmittedRecord = records[Math.max(0, emitCount - 1)];
      if (lastEmittedRecord && lastEmittedRecord.startMillis > toMillis) break;
      startRecord = endRecordExclusive;
    }
    return utterances;
  }

  private async readTailRecords(file: RecordingFile, recordsPerChunk: number): Promise<UdbTailRecords> {
    const knownTotal = this.ranges.knownTotal(file.dbUrl);
    if (knownTotal !== null) {
      const count = this.udbRecordCountFromTotal(knownTotal);
      const startRecord = Math.max(0, count - recordsPerChunk);
      return {
        count,
        startRecord,
        records: count > 0 ? await this.readRecords(file, startRecord, count, false, count) : []
      };
    }

    const result = await this.ranges.fetchSuffix(file.dbUrl, UDB_CHUNK_BYTES);
    if (result.total === null) {
      throw new Error(`Range response for ${file.dbUrl} did not include a total size.`);
    }
    const count = this.udbRecordCountFromTotal(result.total);
    const parsed = this.parseUdbRecords(result.data, result.start);
    const startRecord = parsed.firstRecordOffset === null
      ? count
      : this.recordIndexForOffset(parsed.firstRecordOffset);
    return {
      count,
      startRecord,
      records: parsed.records.slice(0, Math.max(0, count - startRecord))
    };
  }

  private async readAllFileUtterancesBackward(file: RecordingFile, fromMillis: number, toMillis: number,
                                              tailStartRecord: number, tailRecords: UdbRecord[],
                                              count: number, recordsPerChunk: number) {
    let startRecord = tailStartRecord;
    let records = tailRecords;
    let emitCount = count - tailStartRecord;
    const utterances: Utterance[] = [];

    while (records.length) {
      const chunkUtterances = this.recordsToUtterances(file, records, emitCount)
        .filter(utterance => utterance.endMillis >= fromMillis && utterance.startMillis <= toMillis);
      utterances.unshift(...chunkUtterances);
      if (records[0].startMillis <= fromMillis || startRecord <= 0) break;
      const nextStartRecord = Math.max(0, startRecord - recordsPerChunk);
      emitCount = startRecord - nextStartRecord;
      records = await this.readRecords(file, nextStartRecord, startRecord, true, count);
      startRecord = nextStartRecord;
    }
    return utterances.sort((a, b) => a.startMillis - b.startMillis || a.channel.localeCompare(b.channel));
  }

  private udbRecordCountFromTotal(total: number) {
    return Math.max(0, Math.floor((total - UDB_HEADER_BYTES) / UDB_RECORD_BYTES));
  }

  private recordIndexForOffset(offset: number) {
    return Math.max(0, Math.floor((offset - UDB_HEADER_BYTES) / UDB_RECORD_BYTES));
  }

  private async lowerBoundRecordStart(file: RecordingFile, count: number, targetMillis: number) {
    let low = 0;
    let high = count;
    while (low < high) {
      const mid = Math.floor((low + high) / 2);
      const record = (await this.readRecords(file, mid, mid + 1, false, count))[0];
      if (!record || record.startMillis >= targetMillis) {
        high = mid;
      } else {
        low = mid + 1;
      }
    }
    return low;
  }

  private async readRecords(file: RecordingFile, startRecord: number, endRecordExclusive: number,
                            includeLookahead: boolean, count: number) {
    // One lookahead record gives the next Opus byte offset, which is the end
    // offset for the last emitted utterance in this batch.
    const fetchEndRecordExclusive = includeLookahead
      ? Math.min(count, endRecordExclusive + 1)
      : endRecordExclusive;
    if (fetchEndRecordExclusive <= startRecord) return [];
    const start = UDB_HEADER_BYTES + startRecord * UDB_RECORD_BYTES;
    const end = UDB_HEADER_BYTES + fetchEndRecordExclusive * UDB_RECORD_BYTES;
    const bytes = await this.fetchRangeCached(file.dbUrl, start, end, UDB_CHUNK_BYTES);
    return this.parseUdbRecords(bytes, start).records;
  }

  private parseUdbRecords(buffer: ArrayBuffer, absoluteStart: number): ParsedUdbRecords {
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

  private recordsToUtterances(file: RecordingFile, records: UdbRecord[], emitCount: number) {
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

  private async fetchRangeCached(url: string, start: number, end: number, blockBytes: number, signal?: AbortSignal) {
    return (await this.ranges.fetchRange(url, start, end, { blockSize: blockBytes, signal })).data;
  }
}
