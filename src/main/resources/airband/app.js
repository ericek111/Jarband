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
let recentUtterances = [];
let historyPage = 0;
let socket;
let audioContext;
let webCodecsSupported = false;
let channelRefreshTimer;

const HISTORY_PAGE_SIZE = 20;
const MAX_DECODE_QUEUE = 48;
const MAX_PACKET_QUEUE = 500;

function connect() {
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  socket = new WebSocket(`${protocol}//${location.host}/airband/ws`);
  socket.binaryType = "arraybuffer";
  socket.addEventListener("open", () => {
    statusEl.textContent = "Connected";
    send({ type: "list_channels" });
    loadRecentHistory(0);
    channelRefreshTimer = setInterval(() => send({ type: "list_channels" }), 1000);
  });
  socket.addEventListener("close", () => {
    statusEl.textContent = "Disconnected; reconnecting...";
    clearInterval(channelRefreshTimer);
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
    render(recentEl, visibleRecent(message.recent));
  } else if (message.type === "history_index") {
    historyEl.textContent = message.utterances.map(u =>
      `${u.channel}: ${zulu(u.startMillis)} -> ${zulu(u.endMillis)}`
    ).join("\n") || "No recordings in range";
  } else if (message.type === "recent_history") {
    historyPage = message.page;
    recentUtterances = message.utterances;
    renderHistory(message);
  } else if (message.type === "history_started") {
    if (!ensureAudio()) return;
    statusEl.textContent = `Streaming ${message.frames} historical packets...`;
  } else if (message.type === "history_finished") {
    statusEl.textContent = "Historical playback finished";
  } else if (message.type === "history_stopped") {
    statusEl.textContent = "Historical playback stopped";
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
    card.className = `channel${channel.active ? " active-channel" : ""}`;
    const title = document.createElement("strong");
    title.textContent = channel.name;
    const meta = document.createElement("span");
    meta.textContent = lastActiveText(channel);
    const select = document.createElement("button");
    select.textContent = selected.has(channel.name) ? "Filtering" : "Filter history";
    select.className = selected.has(channel.name) ? "active" : "";
    select.onclick = () => {
      if (selected.has(channel.name)) selected.delete(channel.name);
      else selected.add(channel.name);
      render(channelsEl, channels);
      render(recentEl, visibleRecent(channels));
      loadRecentHistory(0);
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
  if (recordVersion !== 1 && recordVersion !== 2) return buffer.byteLength;
  const channelId = view.getUint16(offset + 2, true);
  const sampleRate = view.getUint32(offset + 4, true);
  const unixMillis = Number(view.getBigInt64(offset + 8, true));
  const durationMillis = view.getUint32(offset + 16, true);
  const packetLength = view.getUint32(offset + 20, true);
  const sequence = Number(view.getBigInt64(offset + 24, true));
  const nameLength = view.getUint16(offset + 32, true);
  const name = new TextDecoder().decode(new Uint8Array(buffer, offset + 34, nameLength));
  let payloadOffset = offset + 34 + nameLength;
  let streamKey = name;
  if (recordVersion === 2) {
    const streamKeyLength = view.getUint16(payloadOffset, true);
    payloadOffset += 2;
    streamKey = new TextDecoder().decode(new Uint8Array(buffer, payloadOffset, streamKeyLength));
    payloadOffset += streamKeyLength;
  }
  const packet = new Uint8Array(buffer, payloadOffset, packetLength).slice();
  enqueuePacket(streamKey, name, channelId, sampleRate, unixMillis, durationMillis, packet);
  return payloadOffset + packetLength;
}

function enqueuePacket(streamKey, name, channelId, sampleRate, unixMillis, durationMillis, packet) {
  let queue = packetQueues.get(streamKey);
  if (!queue) {
    queue = [];
    packetQueues.set(streamKey, queue);
  }
  queue.push({ streamKey, name, channelId, sampleRate, unixMillis, durationMillis, packet });
  while (queue.length > MAX_PACKET_QUEUE) {
    queue.shift();
  }
  drainDecoder(streamKey);
}

function drainDecoder(streamKey) {
  const queue = packetQueues.get(streamKey);
  if (!queue || queue.length === 0) return;
  const decoder = decoderFor(streamKey, queue[0].sampleRate);
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
    setTimeout(() => drainDecoder(streamKey), 20);
  }
}

function decoderFor(streamKey, sampleRate) {
  let decoder = decoders.get(streamKey);
  if (decoder) return decoder;
  decoder = new globalThis.AudioDecoder({
    output: frame => {
      playFrame(streamKey, frame);
      drainDecoder(streamKey);
    },
    error: error => {
      statusEl.textContent = error.message;
      setTimeout(() => drainDecoder(streamKey), 100);
    }
  });
  decoder.configure({
    codec: "opus",
    sampleRate,
    numberOfChannels: 1
  });
  decoders.set(streamKey, decoder);
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
  startTime = liveStartTime(name, targetMillis, frame.duration ? frame.duration / 1_000_000 : audio.duration);
  source.start(Math.max(audioContext.currentTime, startTime));
  frame.close();
}

function liveStartTime(name, targetMillis, durationSeconds) {
  let clock = playClocks.get(name);
  if (!clock || targetMillis < clock.lastTargetMillis || clock.nextAudioTime < audioContext.currentTime) {
    clock = {
      nextAudioTime: audioContext.currentTime + 0.05,
      lastTargetMillis: targetMillis
    };
    playClocks.set(name, clock);
  }
  const startTime = Math.max(audioContext.currentTime, clock.nextAudioTime);
  clock.nextAudioTime = startTime + Math.max(0, durationSeconds);
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

function loadRecentHistory(page) {
  send({ type: "recent_history", page, pageSize: HISTORY_PAGE_SIZE, channels: [...selected] });
}

function visibleRecent(list) {
  return list
    .filter(channel => channel.lastActivityMillis > 0)
    .filter(channel => selected.size === 0 || selected.has(channel.name))
    .sort((a, b) => b.lastActivityMillis - a.lastActivityMillis);
}

function lastActiveText(channel) {
  if (channel.active) {
    return "active now";
  }
  if (!channel.lastActivityMillis) {
    return "not active yet";
  }
  const seconds = Math.max(0, Math.round((Date.now() - channel.lastActivityMillis) / 1000));
  if (seconds < 60) {
    return `active ${seconds}s ago`;
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return `active ${minutes}m ago`;
  }
  return `active ${Math.round(minutes / 60)}h ago`;
}

function renderHistory(message) {
  historyEl.replaceChildren();
  const list = document.createElement("div");
  list.className = "history-list";
  for (const utterance of message.utterances) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "utterance";
    row.textContent = `${utterance.channel}  ${zulu(utterance.startMillis)}  ${duration(utterance)}s`;
    row.onclick = () => playUtterance(utterance);
    list.append(row);
  }
  const pager = document.createElement("div");
  pager.className = "pager";
  const prev = document.createElement("button");
  prev.type = "button";
  prev.textContent = "Prev";
  prev.disabled = message.page <= 0;
  prev.onclick = () => loadRecentHistory(message.page - 1);
  const next = document.createElement("button");
  next.type = "button";
  next.textContent = "Next";
  next.disabled = (message.page + 1) * message.pageSize >= message.total;
  next.onclick = () => loadRecentHistory(message.page + 1);
  const label = document.createElement("span");
  label.textContent = `Page ${message.page + 1} of ${Math.max(1, Math.ceil(message.total / message.pageSize))}`;
  pager.append(prev, label, next);
  historyEl.append(list, pager);
}

function duration(utterance) {
  return Math.max(0, (utterance.endMillis - utterance.startMillis) / 1000).toFixed(1);
}

function playUtterance(utterance) {
  if (!ensureAudio()) return;
  send({
    type: "play_utterance",
    channels: [utterance.channel],
    fromMillis: utterance.startMillis,
    toMillis: utterance.endMillis
  });
}

filterEl.addEventListener("input", () => render(channelsEl, channels));
document.querySelector("#refresh").onclick = () => {
  send({ type: "list_channels" });
  loadRecentHistory(historyPage);
};
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
