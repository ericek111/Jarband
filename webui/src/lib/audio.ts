import type { OpusPacket, PlaybackMode } from './types';

type Clock = {
  nextAudioTime: number;
  lastTargetMillis: number;
};

type FrameMetadata = {
  channelName: string;
};

export class AudioEngine {
  private context: AudioContext | null = null;
  private decoders = new Map<string, AudioDecoder>();
  private queues = new Map<string, OpusPacket[]>();
  private frameMetadata = new Map<string, FrameMetadata[]>();
  private clocks = new Map<string, Clock>();
  private sources = new Map<string, Set<AudioBufferSourceNode>>();
  private onFrameScheduled: ((streamKey: string, targetMillis: number, startTime: number,
    durationSeconds: number, channelName: string) => void) | null = null;
  private onStreamIdle: ((streamKey: string) => void) | null = null;

  private readonly maxDecodeQueue = 48;
  private readonly maxPacketQueue = 500;

  supported() {
    return typeof globalThis.AudioDecoder === 'function'
      && typeof globalThis.EncodedAudioChunk === 'function';
  }

  ensure() {
    if (!this.supported()) {
      throw new Error('WebCodecs AudioDecoder is unavailable. Use HTTPS or localhost and a Chromium build with WebCodecs enabled.');
    }
    this.context ??= new AudioContext();
    void this.context.resume();
  }

  enqueue(packet: OpusPacket, playbackMode: PlaybackMode | null) {
    this.ensure();
    const queue = this.queues.get(packet.streamKey) ?? [];
    queue.push(packet);
    if (!playbackMode) {
      while (queue.length > this.maxPacketQueue) queue.shift();
    }
    this.queues.set(packet.streamKey, queue);
    this.drain(packet.streamKey, playbackMode);
  }

  onScheduled(callback: (streamKey: string, targetMillis: number, startTime: number,
    durationSeconds: number, channelName: string) => void) {
    this.onFrameScheduled = callback;
  }

  onIdle(callback: (streamKey: string) => void) {
    this.onStreamIdle = callback;
  }

  flush(streamKey: string) {
    this.queues.delete(streamKey);
    this.clocks.delete(streamKey);
    this.frameMetadata.delete(streamKey);

    const decoder = this.decoders.get(streamKey);
    if (decoder) {
      decoder.close();
      this.decoders.delete(streamKey);
    }

    const sources = this.sources.get(streamKey);
    if (sources) {
      for (const source of sources) {
        try {
          source.stop();
        } catch {
          // Already ended or not yet fully scheduled.
        }
      }
      sources.clear();
      this.sources.delete(streamKey);
    }
  }

  flushPrefix(prefix: string) {
    const keys = new Set<string>();
    for (const key of this.queues.keys()) keys.add(key);
    for (const key of this.clocks.keys()) keys.add(key);
    for (const key of this.decoders.keys()) keys.add(key);
    for (const key of this.sources.keys()) keys.add(key);
    for (const key of keys) {
      if (key.startsWith(prefix)) {
        this.flush(key);
      }
    }
  }

  private drain(streamKey: string, playbackMode: PlaybackMode | null) {
    const queue = this.queues.get(streamKey);
    if (!queue?.length) {
      this.checkIdle(streamKey);
      return;
    }
    const decoder = this.decoderFor(streamKey, queue[0].sampleRate, playbackMode);

    while (queue.length > 0 && decoder.decodeQueueSize < this.maxDecodeQueue) {
      const packet = queue.shift()!;
      this.metadataQueueFor(streamKey).push({ channelName: packet.channelName });
      decoder.decode(new EncodedAudioChunk({
        type: 'key',
        timestamp: packet.unixMillis * 1000,
        duration: packet.durationMillis * 1000,
        data: packet.packet
      }));
    }
    if (queue.length > 0) {
      setTimeout(() => this.drain(streamKey, playbackMode), 20);
    } else {
      this.checkIdle(streamKey);
    }
  }

  private decoderFor(streamKey: string, sampleRate: number, playbackMode: PlaybackMode | null) {
    const existing = this.decoders.get(streamKey);
    if (existing) return existing;

    const decoder = new AudioDecoder({
      output: frame => {
        const metadata = this.frameMetadata.get(streamKey)?.shift();
        this.playFrame(streamKey, frame, playbackMode, metadata);
        this.drain(streamKey, playbackMode);
      },
      error: () => setTimeout(() => this.drain(streamKey, playbackMode), 100)
    });
    decoder.configure({ codec: 'opus', sampleRate, numberOfChannels: 1 });
    this.decoders.set(streamKey, decoder);
    return decoder;
  }

  private playFrame(streamKey: string, frame: AudioData, playbackMode: PlaybackMode | null,
                    metadata?: FrameMetadata) {
    if (!this.context) return;
    const samples = new Float32Array(frame.numberOfFrames);
    frame.copyTo(samples, { planeIndex: 0 });
    const audio = this.context.createBuffer(1, frame.numberOfFrames, frame.sampleRate);
    audio.copyToChannel(samples, 0);
    const source = this.context.createBufferSource();
    source.buffer = audio;
    source.connect(this.context.destination);
    this.trackSource(streamKey, source);

    const targetMillis = frame.timestamp / 1000;
    const durationSeconds = frame.duration ? frame.duration / 1_000_000 : audio.duration;
    const speed = playbackMode?.speed ?? 1;
    source.playbackRate.value = speed;
    const playedDurationSeconds = durationSeconds / speed;
    const startTime = playbackMode?.realtime
      ? playbackMode.originAudioTime + (targetMillis - playbackMode.originMillis) / (1000 * speed)
      : this.liveStartTime(streamKey, targetMillis, playedDurationSeconds);
    const scheduledTime = Math.max(this.context.currentTime, startTime);
    this.onFrameScheduled?.(streamKey, targetMillis, scheduledTime, playedDurationSeconds, metadata?.channelName ?? streamKey);
    source.start(scheduledTime);
    frame.close();
  }

  private metadataQueueFor(streamKey: string) {
    let queue = this.frameMetadata.get(streamKey);
    if (!queue) {
      queue = [];
      this.frameMetadata.set(streamKey, queue);
    }
    return queue;
  }

  private trackSource(streamKey: string, source: AudioBufferSourceNode) {
    let sources = this.sources.get(streamKey);
    if (!sources) {
      sources = new Set();
      this.sources.set(streamKey, sources);
    }
    sources.add(source);
    source.onended = () => {
      sources!.delete(source);
      if (sources!.size === 0) {
        this.sources.delete(streamKey);
      }
      this.checkIdle(streamKey);
    };
  }

  isIdle(streamKey: string) {
    const queue = this.queues.get(streamKey);
    const sources = this.sources.get(streamKey);
    const decoder = this.decoders.get(streamKey);
    return !queue?.length && !sources?.size && (!decoder || decoder.decodeQueueSize === 0);
  }

  currentTime() {
    return this.context?.currentTime ?? 0;
  }

  private checkIdle(streamKey: string) {
    if (this.isIdle(streamKey)) {
      this.onStreamIdle?.(streamKey);
    }
  }

  private liveStartTime(streamKey: string, targetMillis: number, durationSeconds: number) {
    if (!this.context) return 0;
    let clock = this.clocks.get(streamKey);
    if (!clock || targetMillis < clock.lastTargetMillis || clock.nextAudioTime < this.context.currentTime) {
      clock = { nextAudioTime: this.context.currentTime + 0.05, lastTargetMillis: targetMillis };
      this.clocks.set(streamKey, clock);
    }
    const startTime = Math.max(this.context.currentTime, clock.nextAudioTime);
    clock.nextAudioTime = startTime + Math.max(0, durationSeconds);
    clock.lastTargetMillis = targetMillis;
    return startTime;
  }

  audioTime(delaySeconds = 0) {
    this.ensure();
    return this.context!.currentTime + delaySeconds;
  }
}
