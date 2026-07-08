const statusEl = document.querySelector("#status");
const channelsEl = document.querySelector("#channels");
const recentEl = document.querySelector("#recent");
const filterEl = document.querySelector("#filter");
const historyEl = document.querySelector("#history");
const selected = new Set();
const live = new Set();
const decoders = new Map();
const packetQueues = new Map();
const playClocks = new Map();
let channels = [];
let socket;
let audioContext;
let historyOriginMillis = 0;
let historyOriginAudioTime = 0;
let webCodecsSupported = false;

const HISTORY_START_DELAY_SECONDS = 1.0;
const MAX_DECODE_QUEUE = 48;
const MAX_PACKET_QUEUE = 500;

function connect() {
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  socket = new WebSocket(`${protocol}//${location.host}/airband/ws`);
  socket.binaryType = "arraybuffer";
  socket.addEventListener("open", () => {
    statusEl.textContent = "Connected";
    send({ type: "list_channels" });
  });
  socket.addEventListener("close", () => {
    statusEl.textContent = "Disconnected; reconnecting...";
    setTimeout(connect, 1500);
  });
  socket.addEventListener("message", event => {
    if (typeof event.data === "string") handleJson(JSON.parse(event.data));
    else handlePacket(event.data);
  });
}

function send(message) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(message));
  }
}

function handleJson(message) {
  if (message.type === "channels") {
    channels = message.channels;
    render(channelsEl, channels);
    render(recentEl, message.recent);
  } else if (message.type === "history_index") {
    historyEl.textContent = message.utterances.map(u =>
      `${u.channel}: ${zulu(u.startMillis)} -> ${zulu(u.endMillis)}`
    ).join("\n") || "No recordings in range";
  } else if (message.type === "history_started") {
    if (!ensureAudio()) return;
    historyOriginMillis = message.fromMillis;
    historyOriginAudioTime = audioContext.currentTime + HISTORY_START_DELAY_SECONDS;
    historyEl.textContent = `Streaming ${message.frames} historical packets...`;
  } else if (message.type === "history_finished") {
    historyEl.textContent = "Historical playback finished";
    historyOriginMillis = 0;
  } else if (message.type === "history_stopped") {
    historyEl.textContent = "Historical playback stopped";
    historyOriginMillis = 0;
  } else if (message.type === "error") {
    statusEl.textContent = message.message;
  }
}

function render(root, list) {
  const term = filterEl.value.trim().toLowerCase();
  root.replaceChildren();
  for (const channel of list) {
    if (term && !channel.name.toLowerCase().includes(term)) continue;
    const card = document.createElement("div");
    card.className = "channel";
    const title = document.createElement("strong");
    title.textContent = channel.name;
    const meta = document.createElement("span");
    meta.textContent = `${(channel.frequencyHz / 1e6).toFixed(6)} MHz ${channel.active ? "active" : ""}`;
    const select = document.createElement("button");
    select.textContent = selected.has(channel.name) ? "Selected" : "Select";
    select.className = selected.has(channel.name) ? "active" : "";
    select.onclick = () => {
      if (selected.has(channel.name)) selected.delete(channel.name);
      else selected.add(channel.name);
      render(channelsEl, channels);
    };
    const listen = document.createElement("button");
    listen.textContent = live.has(channel.name) ? "Listening" : "Listen";
    listen.className = live.has(channel.name) ? "live" : "";
    listen.onclick = () => toggleLive(channel.name);
    card.append(title, meta, select, listen);
    root.append(card);
  }
}

function toggleLive(name) {
  if (!ensureAudio()) return;
  if (live.has(name)) {
    live.delete(name);
    send({ type: "unsubscribe_live", channels: [name] });
  } else {
    live.add(name);
    send({ type: "subscribe_live", channels: [name] });
  }
  render(channelsEl, channels);
}

function handlePacket(buffer) {
  if (!ensureAudio()) return;
  const view = new DataView(buffer);
  if (view.getUint32(0, true) !== 0x4a424f50) return;
  const version = view.getUint16(4, true);
  if (version === 1) {
    parsePacketRecord(buffer, 4);
  } else if (version === 2) {
    const count = view.getUint16(6, true);
    let offset = 8;
    for (let i = 0; i < count; i++) {
      offset = parsePacketRecord(buffer, offset);
    }
  }
}

function parsePacketRecord(buffer, offset) {
  const view = new DataView(buffer);
  const recordVersion = view.getUint16(offset, true);
  if (recordVersion !== 1) return buffer.byteLength;
  const channelId = view.getUint16(offset + 2, true);
  const sampleRate = view.getUint32(offset + 4, true);
  const unixMillis = Number(view.getBigInt64(offset + 8, true));
  const durationMillis = view.getUint32(offset + 16, true);
  const packetLength = view.getUint32(offset + 20, true);
  const sequence = Number(view.getBigInt64(offset + 24, true));
  const nameLength = view.getUint16(offset + 32, true);
  const payloadOffset = offset + 34 + nameLength;
  const name = new TextDecoder().decode(new Uint8Array(buffer, offset + 34, nameLength));
  const packet = new Uint8Array(buffer, payloadOffset, packetLength).slice();
  enqueuePacket(name, channelId, sampleRate, unixMillis, durationMillis, packet);
  return payloadOffset + packetLength;
}

function enqueuePacket(name, channelId, sampleRate, unixMillis, durationMillis, packet) {
  let queue = packetQueues.get(name);
  if (!queue) {
    queue = [];
    packetQueues.set(name, queue);
  }
  queue.push({ name, channelId, sampleRate, unixMillis, durationMillis, packet });
  while (queue.length > MAX_PACKET_QUEUE) {
    queue.shift();
  }
  drainDecoder(name);
}

function drainDecoder(name) {
  const queue = packetQueues.get(name);
  if (!queue || queue.length === 0) return;
  const decoder = decoderFor(name, queue[0].channelId, queue[0].sampleRate);
  while (queue.length > 0 && decoder.decodeQueueSize < MAX_DECODE_QUEUE) {
    const item = queue.shift();
    decoder.decode(new globalThis.EncodedAudioChunk({
      type: "key",
      timestamp: item.unixMillis * 1000,
      duration: item.durationMillis * 1000,
      data: item.packet
    }));
  }
  if (queue.length > 0) {
    setTimeout(() => drainDecoder(name), 20);
  }
}

function decoderFor(name, channelId, sampleRate) {
  let decoder = decoders.get(name);
  if (decoder) return decoder;
  decoder = new globalThis.AudioDecoder({
    output: frame => {
      playFrame(name, frame);
      drainDecoder(name);
    },
    error: error => {
      statusEl.textContent = error.message;
      setTimeout(() => drainDecoder(name), 100);
    }
  });
  decoder.configure({
    codec: "opus",
    sampleRate,
    numberOfChannels: 1
  });
  decoders.set(name, decoder);
  return decoder;
}

function playFrame(name, frame) {
  const samples = new Float32Array(frame.numberOfFrames);
  frame.copyTo(samples, { planeIndex: 0 });
  const audio = audioContext.createBuffer(1, frame.numberOfFrames, frame.sampleRate);
  audio.copyToChannel(samples, 0);
  const source = audioContext.createBufferSource();
  source.buffer = audio;
  source.connect(audioContext.destination);
  const targetMillis = frame.timestamp / 1000;
  let startTime;
  if (historyOriginMillis && targetMillis < Date.now() - 1000) {
    startTime = historyOriginAudioTime + (targetMillis - historyOriginMillis) / 1000;
  } else {
    startTime = liveStartTime(name, targetMillis);
  }
  source.start(Math.max(audioContext.currentTime, startTime));
  frame.close();
}

function liveStartTime(name, targetMillis) {
  let clock = playClocks.get(name);
  if (!clock || targetMillis < clock.lastTargetMillis || clock.nextAudioTime < audioContext.currentTime) {
    clock = {
      nextAudioTime: audioContext.currentTime + 0.01,
      lastTargetMillis: targetMillis
    };
    playClocks.set(name, clock);
  }
  const startTime = Math.max(audioContext.currentTime, clock.nextAudioTime);
  clock.nextAudioTime = startTime + Math.max(0, targetMillis - clock.lastTargetMillis) / 1000;
  clock.lastTargetMillis = targetMillis;
  return startTime;
}

function ensureAudio() {
  if (!webCodecsSupported) {
    webCodecsSupported = typeof globalThis.AudioDecoder === "function"
      && typeof globalThis.EncodedAudioChunk === "function";
  }
  if (!webCodecsSupported) {
    statusEl.textContent = "WebCodecs AudioDecoder is unavailable. Use HTTPS or localhost and a Chromium build with WebCodecs enabled.";
    return false;
  }
  if (!audioContext) {
    audioContext = new AudioContext();
  }
  audioContext.resume();
  return true;
}

function zulu(millis) {
  return new Date(millis).toISOString().replace(".000Z", "Z");
}

function localMillis(selector) {
  const value = document.querySelector(selector).value;
  return value ? new Date(value).getTime() : 0;
}

filterEl.addEventListener("input", () => render(channelsEl, channels));
document.querySelector("#refresh").onclick = () => send({ type: "list_channels" });
document.querySelector("#index").onclick = () => send({
  type: "history_index",
  channels: [...selected],
  fromMillis: localMillis("#from"),
  toMillis: localMillis("#to") || Date.now()
});
document.querySelector("#play-history").onclick = () => {
  if (!ensureAudio()) return;
  send({
    type: "play_history",
    channels: [...selected],
    fromMillis: localMillis("#from"),
    toMillis: localMillis("#to") || Date.now()
  });
};
document.querySelector("#stop-history").onclick = () => send({ type: "stop_history" });

connect();
