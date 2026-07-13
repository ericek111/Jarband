export type Channel = {
  id: number;
  name: string;
  frequencyHz: number;
  lastActivityMillis: number;
  active: boolean;
};

export type Utterance = {
  channel: string;
  startMillis: number;
  endMillis: number;
  durationMillis?: number;
  averageSnrDb?: number;
  opusUrl?: string;
  startOffset?: number;
  endOffset?: number;
  sampleRate?: number;
  frameMillis?: number;
};

export type ServerMessage =
  | { type: 'channels'; channels: Channel[]; recent: Channel[] }
  | { type: 'activity'; channel: Channel }
  | { type: 'utterance_closed'; utterance: Utterance }
  | { type: 'subscribed' | 'unsubscribed' }
  | { type: 'error'; message: string };

export type OpusPacket = {
  streamKey: string;
  channelName: string;
  channelId: number;
  sampleRate: number;
  unixMillis: number;
  playbackMillis?: number;
  durationMillis: number;
  packet: Uint8Array;
};

export type RecordingBytes = {
  channelId: number;
  channelName: string;
  recordingUrl: string;
  offset: number;
  data: Uint8Array;
};

export type PlaybackMode = {
  realtime: boolean;
  originMillis: number;
  originAudioTime: number;
  speed: number;
};
