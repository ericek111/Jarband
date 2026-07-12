import type { OpusPacket, PlaybackMode } from './types';

type Clock = {
  nextAudioTime: number;
  lastTargetMillis: number;
};

type FrameMetadata = {
  channelName: string;
};

type PcmFrame = {
  samples: Float32Array<ArrayBuffer>;
  sampleRate: number;
  targetMillis: number;
  channelName: string;
};

type PcmBuffer = {
  frames: PcmFrame[];
  sampleCount: number;
  sampleRate: number;
  playbackMode: PlaybackMode | null;
  timer: ReturnType<typeof setTimeout> | null;
};

export class AudioEngine {
  private context: AudioContext | null = null;
  private decoders = new Map<string, AudioDecoder>();
  private queues = new Map<string, OpusPacket[]>();
  private drainTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private frameMetadata = new Map<string, FrameMetadata[]>();
  private pcmBuffers = new Map<string, PcmBuffer>();
  private clocks = new Map<string, Clock>();
  private sources = new Map<string, Set<AudioBufferSourceNode>>();
  private onFrameScheduled: ((streamKey: string, targetMillis: number, startTime: number,
    durationSeconds: number, channelName: string) => void) | null = null;
  private onStreamIdle: ((streamKey: string) => void) | null = null;

  private readonly maxDecodeQueue = 8;
  private readonly maxPacketQueue = 500;
  private readonly maxScheduleAheadSeconds = 0.45;
  private readonly historyPcmOutputChunkSeconds = 0.16;
  private readonly minHistoryPcmInputSeconds = 0.12;
  private readonly maxHistoryPcmInputSeconds = 0.64;
  private readonly livePcmChunkSeconds = 0.04;
  private readonly pcmFlushDelayMillis = 35;
  private readonly historyBoundaryFadeSeconds = 0.0025;

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
    this.clearDrainTimer(streamKey);
    this.queues.delete(streamKey);
    this.clocks.delete(streamKey);
    this.frameMetadata.delete(streamKey);
    this.clearPcmBuffer(streamKey);

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
    for (const key of this.pcmBuffers.keys()) keys.add(key);
    for (const key of this.drainTimers.keys()) keys.add(key);
    for (const key of keys) {
      if (key.startsWith(prefix)) {
        this.flush(key);
      }
    }
  }

  private drain(streamKey: string, playbackMode: PlaybackMode | null) {
    this.clearDrainTimer(streamKey);
    const queue = this.queues.get(streamKey);
    if (!queue?.length) {
      this.checkIdle(streamKey);
      return;
    }
    const decoder = this.decoderFor(streamKey, queue[0].sampleRate, playbackMode);

    if (playbackMode && this.bufferedAheadSeconds(streamKey) >= this.maxScheduleAheadSeconds) {
      this.deferDrain(streamKey, playbackMode);
      return;
    }

    while (queue.length > 0 && decoder.decodeQueueSize < this.maxDecodeQueue
      && this.bufferedAheadSeconds(streamKey) < this.maxScheduleAheadSeconds) {
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
      this.deferDrain(streamKey, playbackMode);
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
    if (!this.context) {
      frame.close();
      return;
    }
    const samples = new Float32Array(frame.numberOfFrames);
    frame.copyTo(samples, { planeIndex: 0 });
    this.bufferPcmFrame(streamKey, {
      samples,
      sampleRate: frame.sampleRate,
      targetMillis: frame.timestamp / 1000,
      channelName: metadata?.channelName ?? streamKey
    }, playbackMode);
    frame.close();
  }

  private bufferPcmFrame(streamKey: string, frame: PcmFrame, playbackMode: PlaybackMode | null) {
    let buffer = this.pcmBuffers.get(streamKey);
    if (buffer && (buffer.sampleRate !== frame.sampleRate || buffer.playbackMode !== playbackMode)) {
      this.flushPcmBuffer(streamKey);
      buffer = undefined;
    }
    if (!buffer) {
      buffer = { frames: [], sampleCount: 0, sampleRate: frame.sampleRate, playbackMode, timer: null };
      this.pcmBuffers.set(streamKey, buffer);
    }

    buffer.frames.push(frame);
    buffer.sampleCount += frame.samples.length;

    const targetSamples = this.pcmTargetSamples(frame.sampleRate, playbackMode);
    if (buffer.sampleCount >= targetSamples) {
      this.flushPcmBuffer(streamKey);
      return;
    }

    if (!buffer.timer) {
      buffer.timer = setTimeout(() => this.flushPcmBuffer(streamKey), this.pcmFlushDelayMillis);
    }
  }

  private flushPcmBuffer(streamKey: string) {
    const buffer = this.pcmBuffers.get(streamKey);
    if (!buffer) return;
    this.pcmBuffers.delete(streamKey);
    if (buffer.timer) clearTimeout(buffer.timer);
    if (!buffer.frames.length || !this.context) {
      this.checkIdle(streamKey);
      return;
    }

    const sourceSamples = this.concatFrames(buffer.frames, buffer.sampleCount);
    const playbackMode = buffer.playbackMode;
    const speed = playbackMode ? this.normalizedPlaybackSpeed(playbackMode.speed) : 1;
    const renderedSamples = this.timeScaleSpeech(sourceSamples, speed, buffer.sampleRate);
    this.scheduleSamples(streamKey, renderedSamples, buffer.sampleRate, buffer.frames[0].targetMillis,
      buffer.frames[0].channelName, playbackMode, speed);
    this.drain(streamKey, playbackMode);
  }

  private concatFrames(frames: PcmFrame[], sampleCount: number) {
    const samples = new Float32Array(sampleCount);
    let offset = 0;
    for (const frame of frames) {
      samples.set(frame.samples, offset);
      offset += frame.samples.length;
    }
    return samples;
  }

  private pcmTargetSamples(sampleRate: number, playbackMode: PlaybackMode | null) {
    if (!playbackMode) {
      return Math.max(1, Math.round(sampleRate * this.livePcmChunkSeconds));
    }

    // The stretcher works best with a stable output chunk duration. Faster
    // playback therefore needs more input audio per pass, not smaller chunks.
    const speed = this.normalizedPlaybackSpeed(playbackMode.speed);
    const inputSeconds = clamp(this.historyPcmOutputChunkSeconds * speed,
      this.minHistoryPcmInputSeconds, this.maxHistoryPcmInputSeconds);
    return Math.max(1, Math.round(sampleRate * inputSeconds));
  }

  private scheduleSamples(streamKey: string, samples: Float32Array<ArrayBuffer>, sampleRate: number,
                          targetMillis: number, channelName: string, playbackMode: PlaybackMode | null,
                          speed: number) {
    if (!this.context) return;
    const audio = this.context.createBuffer(1, samples.length, sampleRate);
    audio.copyToChannel(samples, 0);
    const source = this.context.createBufferSource();
    source.buffer = audio;

    const playedDurationSeconds = audio.duration;
    const startTime = playbackMode?.realtime
      ? playbackMode.originAudioTime + (targetMillis - playbackMode.originMillis) / (1000 * speed)
      : this.liveStartTime(streamKey, targetMillis, playedDurationSeconds);
    const scheduledTime = Math.max(this.context.currentTime, startTime);
    const gain = this.connectSource(source, scheduledTime, playedDurationSeconds, playbackMode !== null);
    this.trackSource(streamKey, source, gain);
    if (playbackMode?.realtime) {
      this.rememberScheduledTime(streamKey, targetMillis, scheduledTime, playedDurationSeconds);
    }
    this.onFrameScheduled?.(streamKey, targetMillis, scheduledTime, playedDurationSeconds, channelName);
    source.start(scheduledTime);
  }

  private timeScaleSpeech(samples: Float32Array<ArrayBuffer>, speed: number,
                          sampleRate: number): Float32Array<ArrayBuffer> {
    if (!Number.isFinite(speed) || Math.abs(speed - 1) < 0.01 || samples.length < 32) {
      return samples;
    }
    const targetLength = Math.max(1, Math.round(samples.length / speed));
    if (targetLength === samples.length) return samples;

    // AudioBufferSourceNode.playbackRate resamples and shifts pitch. For radio
    // voice playback, WSOLA-style grains keep the original sample rate and
    // change duration by skipping/reusing similar waveform slices instead.
    const preferredGrain = Math.round(sampleRate * 0.03);
    const grainLength = Math.max(16, Math.min(preferredGrain, samples.length, targetLength));
    const overlapLength = Math.max(8, Math.min(Math.floor(grainLength * 0.5), grainLength - 1));
    const hopOut = Math.max(1, grainLength - overlapLength);
    const hopIn = hopOut * speed;
    const output = new Float32Array(targetLength);
    const firstCopy = Math.min(grainLength, samples.length, targetLength);
    output.set(samples.subarray(0, firstCopy), 0);

    let sourcePosition = hopIn;
    let outputPosition = hopOut;
    while (outputPosition < targetLength) {
      const sourceIndex = this.bestOverlapSourceIndex(samples, output, outputPosition,
        Math.round(sourcePosition), grainLength, overlapLength, sampleRate);
      const copyLength = Math.min(grainLength, targetLength - outputPosition, samples.length - sourceIndex);
      this.overlapAdd(output, outputPosition, samples, sourceIndex, copyLength, overlapLength);
      sourcePosition += hopIn;
      outputPosition += hopOut;
    }
    return output;
  }

  private connectSource(source: AudioBufferSourceNode, startTime: number, durationSeconds: number,
                        smoothBoundaries: boolean) {
    if (!this.context) return null;
    if (!smoothBoundaries || durationSeconds <= 0) {
      source.connect(this.context.destination);
      return null;
    }

    const gain = this.context.createGain();
    source.connect(gain);
    gain.connect(this.context.destination);

    // Separate AudioBufferSourceNodes can click if the stretched waveform has a
    // non-zero boundary. A short envelope masks that without changing speed.
    const fadeSeconds = Math.min(this.historyBoundaryFadeSeconds, durationSeconds / 2);
    const endTime = startTime + durationSeconds;
    const fadeOutStart = Math.max(startTime + fadeSeconds, endTime - fadeSeconds);
    gain.gain.setValueAtTime(0, startTime);
    gain.gain.linearRampToValueAtTime(1, startTime + fadeSeconds);
    gain.gain.setValueAtTime(1, fadeOutStart);
    gain.gain.linearRampToValueAtTime(0, endTime);
    return gain;
  }

  private bestOverlapSourceIndex(samples: Float32Array<ArrayBuffer>, output: Float32Array<ArrayBuffer>,
                                 outputPosition: number, nominalSourceIndex: number, grainLength: number,
                                 overlapLength: number, sampleRate: number) {
    const maxSourceIndex = Math.max(0, samples.length - grainLength);
    const searchRadius = Math.min(Math.round(sampleRate * 0.008), maxSourceIndex);
    const from = clamp(nominalSourceIndex - searchRadius, 0, maxSourceIndex);
    const to = clamp(nominalSourceIndex + searchRadius, 0, maxSourceIndex);
    let bestIndex = clamp(nominalSourceIndex, 0, maxSourceIndex);
    let bestScore = -Infinity;

    // The local correlation search avoids clicky overlaps when a grain boundary
    // lands in the middle of a voiced waveform cycle.
    for (let candidate = from; candidate <= to; candidate++) {
      let cross = 0;
      let outputEnergy = 0;
      let inputEnergy = 0;
      for (let i = 0; i < overlapLength && outputPosition + i < output.length; i++) {
        const existing = output[outputPosition + i];
        const next = samples[candidate + i];
        cross += existing * next;
        outputEnergy += existing * existing;
        inputEnergy += next * next;
      }
      const score = cross / Math.max(1e-12, Math.sqrt(outputEnergy * inputEnergy));
      if (score > bestScore) {
        bestScore = score;
        bestIndex = candidate;
      }
    }
    return bestIndex;
  }

  private overlapAdd(output: Float32Array<ArrayBuffer>, outputPosition: number,
                     samples: Float32Array<ArrayBuffer>, sourceIndex: number, copyLength: number,
                     overlapLength: number) {
    for (let i = 0; i < copyLength; i++) {
      const outputIndex = outputPosition + i;
      if (i < overlapLength && outputIndex < output.length) {
        const fadeIn = (i + 1) / (overlapLength + 1);
        output[outputIndex] = output[outputIndex] * (1 - fadeIn) + samples[sourceIndex + i] * fadeIn;
      } else {
        output[outputIndex] = samples[sourceIndex + i];
      }
    }
  }

  private metadataQueueFor(streamKey: string) {
    let queue = this.frameMetadata.get(streamKey);
    if (!queue) {
      queue = [];
      this.frameMetadata.set(streamKey, queue);
    }
    return queue;
  }

  private trackSource(streamKey: string, source: AudioBufferSourceNode, gain: GainNode | null) {
    let sources = this.sources.get(streamKey);
    if (!sources) {
      sources = new Set();
      this.sources.set(streamKey, sources);
    }
    sources.add(source);
    source.onended = () => {
      try {
        source.disconnect();
        gain?.disconnect();
      } catch {
        // Disconnecting an already torn-down graph is harmless.
      }
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
    const pcm = this.pcmBuffers.get(streamKey);
    return !queue?.length && !sources?.size && !pcm?.sampleCount
      && (!decoder || decoder.decodeQueueSize === 0);
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

  private rememberScheduledTime(streamKey: string, targetMillis: number, startTime: number,
                                durationSeconds: number) {
    this.clocks.set(streamKey, {
      nextAudioTime: startTime + Math.max(0, durationSeconds),
      lastTargetMillis: targetMillis
    });
  }

  private bufferedAheadSeconds(streamKey: string) {
    if (!this.context) return 0;
    const scheduledAhead = Math.max(0, (this.clocks.get(streamKey)?.nextAudioTime ?? 0) - this.context.currentTime);
    const pcm = this.pcmBuffers.get(streamKey);
    const pcmSpeed = pcm?.playbackMode ? this.normalizedPlaybackSpeed(pcm.playbackMode.speed) : 1;
    const bufferedPcm = pcm ? pcm.sampleCount / pcm.sampleRate / pcmSpeed : 0;
    return scheduledAhead + bufferedPcm;
  }

  private normalizedPlaybackSpeed(speed: number) {
    return Number.isFinite(speed) ? clamp(speed, 0.5, 4) : 1;
  }

  private deferDrain(streamKey: string, playbackMode: PlaybackMode | null) {
    if (this.drainTimers.has(streamKey)) return;
    const timer = setTimeout(() => {
      this.drainTimers.delete(streamKey);
      this.drain(streamKey, playbackMode);
    }, 25);
    this.drainTimers.set(streamKey, timer);
  }

  private clearDrainTimer(streamKey: string) {
    const timer = this.drainTimers.get(streamKey);
    if (!timer) return;
    clearTimeout(timer);
    this.drainTimers.delete(streamKey);
  }

  private clearPcmBuffer(streamKey: string) {
    const buffer = this.pcmBuffers.get(streamKey);
    if (buffer?.timer) clearTimeout(buffer.timer);
    this.pcmBuffers.delete(streamKey);
  }

  audioTime(delaySeconds = 0) {
    this.ensure();
    return this.context!.currentTime + delaySeconds;
  }
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}
