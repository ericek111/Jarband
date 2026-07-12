import type { Utterance } from './types';

export type RecordingFile = {
  channel: string;
  dbUrl: string;
  opusUrl: string;
  sampleRate: number;
  frameMillis: number;
};

export class RecordingFileDirectory {
  private readonly cache = new Map<string, Promise<RecordingFile[]>>();

  async list(channels: string[]) {
    const key = this.cacheKey(channels);
    const cached = this.cache.get(key);
    if (cached) return cached;

    const request = fetch(`/airband/api/recording-files?${this.params(channels)}`)
      .then(response => {
        if (!response.ok) throw new Error(`Recording file list failed: ${response.status}`);
        return response.json();
      })
      .then((body: { files: RecordingFile[] }) => body.files)
      .catch(error => {
        this.cache.delete(key);
        throw error;
      });
    this.cache.set(key, request);
    return request;
  }

  remember(utterance: Utterance) {
    if (!utterance.opusUrl || utterance.sampleRate === undefined || utterance.frameMillis === undefined) return;
    const dbUrl = this.dbUrlForOpusUrl(utterance.opusUrl);
    if (!dbUrl) return;
    const file = {
      channel: utterance.channel,
      dbUrl,
      opusUrl: utterance.opusUrl,
      sampleRate: utterance.sampleRate,
      frameMillis: utterance.frameMillis
    } satisfies RecordingFile;

    // Closed-utterance messages identify the active recording file, so existing
    // file-list cache entries can learn about it without refetching recording-files.
    for (const [key, cached] of this.cache) {
      const channels = key.split('\u0000').filter(Boolean);
      if (channels.length > 0 && !channels.includes(file.channel)) continue;
      this.cache.set(key, cached.then(files => this.upsert(files, file))
        .catch(error => {
          this.cache.delete(key);
          throw error;
        }));
    }
  }

  private params(channels: string[]) {
    const params = new URLSearchParams();
    for (const channel of channels) {
      params.append('channel', channel);
    }
    return params;
  }

  private cacheKey(channels: string[]) {
    return channels.slice().sort().join('\u0000');
  }

  private upsert(files: RecordingFile[], file: RecordingFile) {
    // Keep the cached array stable when the current file is already known.
    const exists = files.some(item => item.channel === file.channel
      && item.dbUrl === file.dbUrl
      && item.opusUrl === file.opusUrl);
    return exists ? files : [file, ...files];
  }

  private dbUrlForOpusUrl(opusUrl: string) {
    // Recording pairs share a basename and directory: current.opus/current.udb.
    const marker = '.opus';
    const index = opusUrl.lastIndexOf(marker);
    if (index < 0) return null;
    return `${opusUrl.slice(0, index)}.udb${opusUrl.slice(index + marker.length)}`;
  }
}
