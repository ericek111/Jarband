export type Channel = {
  id: number;
  name: string;
  frequencyHz: number;
  lastActivityMillis: number;
  active: boolean;
};

export type Utterance = {
  channel: string;
  frequencyHz: number;
  startMillis: number;
  endMillis: number;
};

export type ServerMessage =
  | { type: 'channels'; channels: Channel[]; recent: Channel[] }
  | { type: 'activity'; channel: Channel }
  | { type: 'utterance_closed'; utterance: Utterance }
  | { type: 'recent_history'; page: number; pageSize: number; total: number; utterances: Utterance[] }
  | { type: 'history_started'; frames: number; fromMillis: number; toMillis: number; playbackId: number; realtime: boolean; channels: string[] }
  | { type: 'history_finished'; playbackId: number }
  | { type: 'history_stopped' }
  | { type: 'download_started'; downloadId: number; frames: number; filename: string }
  | { type: 'download_finished'; downloadId: number }
  | { type: 'subscribed' | 'unsubscribed' }
  | { type: 'error'; message: string };

export type OpusPacket = {
  streamKey: string;
  channelName: string;
  channelId: number;
  sampleRate: number;
  unixMillis: number;
  durationMillis: number;
  packet: Uint8Array;
};

export type PlaybackMode = {
  realtime: boolean;
  originMillis: number;
  originAudioTime: number;
};
