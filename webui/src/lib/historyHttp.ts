import { SparseHttpRangeCache } from './httpRangeCache';
import {
  HistoryOpusPackets,
  type HistoryPacketWindowRequest
} from './historyOpus';
import { RecordingFileDirectory } from './historyRecordingFiles';
import { HistoryUdbIndex } from './historyUdb';
import type { Utterance } from './types';

export {
  HISTORY_OPUS_CHUNK_BYTES,
  type HistoryPacketWindowRequest
} from './historyOpus';

const rangeCache = new SparseHttpRangeCache();
const recordingDirectory = new RecordingFileDirectory();
const historyIndex = new HistoryUdbIndex(recordingDirectory, rangeCache);
const opusPackets = new HistoryOpusPackets(rangeCache);

export function fetchRecentHistory(channels: string[], beforeMillis: number, limit: number) {
  return historyIndex.recent(channels, beforeMillis, limit);
}

export function fetchHistoryIndex(channels: string[], fromMillis: number, toMillis: number) {
  return historyIndex.index(channels, fromMillis, toMillis);
}

export function fetchUtterancePackets(utterance: Utterance, streamKey: string, signal?: AbortSignal) {
  return opusPackets.utterance(utterance, streamKey, signal);
}

export function fetchUtterancePacketWindow(requests: HistoryPacketWindowRequest[],
                                           horizonMillis: number,
                                           minPacketMillis: number,
                                           signal?: AbortSignal) {
  return opusPackets.window(requests, horizonMillis, minPacketMillis, signal);
}

export function rememberHistoryRecordingFile(utterance: Utterance) {
  recordingDirectory.remember(utterance);
}

export function historyRangeCacheStats() {
  return rangeCache.stats();
}

export function appendHistoryRecordingBytes(recordingUrl: string, offset: number, data: Uint8Array) {
  // The recorder sends an empty range when it truncates a short discarded
  // utterance from the UDB; mirror that in the sparse range cache.
  if (data.byteLength === 0) {
    rangeCache.truncate(recordingUrl, offset);
    return;
  }
  const end = offset + data.byteLength;
  rangeCache.putRange(recordingUrl, offset, data, Math.max(end, rangeCache.knownTotal(recordingUrl) ?? 0),
    { overwrite: recordingUrl.endsWith('.udb') });
}
