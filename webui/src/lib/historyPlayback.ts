import type { OpusPacket, PlaybackMode } from './types';

export class HistoryPlaybackTracker {
  private readonly modes = new Map<string, PlaybackMode>();
  private readonly channels = new Map<number, string[]>();
  private readonly streams = new Map<number, Set<string>>();
  private readonly serverFinished = new Set<number>();

  start(playbackId: number, channels: string[], mode: PlaybackMode) {
    this.modes.set(this.modeKey(playbackId), mode);
    this.channels.set(playbackId, channels);
  }

  modeFor(streamKey: string) {
    const id = this.idFromStreamKey(streamKey);
    return id === null ? null : this.modes.get(this.modeKey(id)) ?? null;
  }

  trackPacket(packet: OpusPacket) {
    const id = this.idFromStreamKey(packet.streamKey);
    if (id === null) return;
    let streams = this.streams.get(id);
    if (!streams) {
      streams = new Set();
      this.streams.set(id, streams);
    }
    streams.add(packet.streamKey);
  }

  streamIdle(streamKey: string) {
    const id = this.idFromStreamKey(streamKey);
    if (id === null) return null;
    const streams = this.streams.get(id);
    if (streams) {
      streams.delete(streamKey);
      if (streams.size === 0) {
        this.streams.delete(id);
      }
    }
    return this.completeIfDrained(id);
  }

  serverDone(playbackId: number) {
    this.serverFinished.add(playbackId);
    return this.completeIfDrained(playbackId);
  }

  clear() {
    this.modes.clear();
    this.channels.clear();
    this.streams.clear();
    this.serverFinished.clear();
  }

  idFromStreamKey(streamKey: string) {
    const match = /^history:(\d+):/.exec(streamKey);
    return match ? Number(match[1]) : null;
  }

  channelFromStreamKey(streamKey: string) {
    return streamKey.replace(/^history:\d+:/, '');
  }

  private completeIfDrained(playbackId: number) {
    if (!this.serverFinished.has(playbackId)) return null;
    const streams = this.streams.get(playbackId);
    if (streams?.size) return null;
    const channels = this.channels.get(playbackId) ?? [];
    this.modes.delete(this.modeKey(playbackId));
    this.channels.delete(playbackId);
    this.serverFinished.delete(playbackId);
    return { playbackId, channels };
  }

  private modeKey(playbackId: number) {
    return `history:${playbackId}`;
  }
}
