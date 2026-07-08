interface EncodedAudioChunkInit {
  type: 'key' | 'delta';
  timestamp: number;
  duration?: number;
  data: BufferSource;
}

declare class EncodedAudioChunk {
  constructor(init: EncodedAudioChunkInit);
}

interface AudioDecoderInit {
  output: (frame: AudioData) => void;
  error: (error: DOMException) => void;
}

interface AudioDecoderConfig {
  codec: string;
  sampleRate: number;
  numberOfChannels: number;
}

declare class AudioDecoder {
  readonly decodeQueueSize: number;
  constructor(init: AudioDecoderInit);
  configure(config: AudioDecoderConfig): void;
  decode(chunk: EncodedAudioChunk): void;
  flush(): Promise<void>;
  close(): void;
}

interface AudioDataCopyToOptions {
  planeIndex: number;
}

declare class AudioData {
  readonly numberOfFrames: number;
  readonly sampleRate: number;
  readonly timestamp: number;
  readonly duration: number | null;
  copyTo(destination: Float32Array, options: AudioDataCopyToOptions): void;
  close(): void;
}

interface Window {
  AudioDecoder?: typeof AudioDecoder;
  EncodedAudioChunk?: typeof EncodedAudioChunk;
}
