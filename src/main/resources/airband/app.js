const statusEl = document.querySelector("#status");
const channelsEl = document.querySelector("#channels");
const recentEl = document.querySelector("#recent");
const filterEl = document.querySelector("#filter");
const historyEl = document.querySelector("#history");
const filterChipsEl = document.querySelector("#filter-chips");
const realtimeGapsEl = document.querySelector("#realtime-gaps");
const channelsDetails = document.querySelector("details");
const selected = new Set();
const live = new Set();
const decoders = new Map();
const packetQueues = new Map();
const playClocks = new Map();
const playbackModes = new Map();
const playbackTimers = new Map();
const rowPlaybackTimers = new Map();
const channelNodes = new WeakMap();
let channels = [];
let recentUtterances = [];
let historyPage = 0;
let socket;
let audioContext;
let webCodecsSupported = false;
let displayRefreshTimer;

const HISTORY_PAGE_SIZE = 20;
const RECENT_ACTIVITY_WINDOW_MILLIS = 180_000;
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
    displayRefreshTimer = setInterval(refreshDisplay, 1000);
  });
  socket.addEventListener("close", () => {
    statusEl.textContent = "Disconnected; reconnecting...";
    clearInterval(displayRefreshTimer);
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
    renderChannelsIfOpen();
    render(recentEl, visibleRecent(message.recent));
  } else if (message.type === "activity") {
    updateChannel(message.channel);
    updateChannelTile(message.channel.id);
    render(recentEl, visibleRecent(channels));
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
    const modeKey = `history:${message.playbackId}`;
    playbackModes.set(modeKey, {
      realtime: Boolean(message.realtime),
      originMillis: message.fromMillis,
      originAudioTime: audioContext.currentTime + 0.15
    });
    armPlaybackExpiry(message.playbackId, message);
    statusEl.textContent = `Streaming ${message.frames} historical packets...`;
  } else if (message.type === "history_finished") {
    statusEl.textContent = "Historical playback finished";
  } else if (message.type === "history_stopped") {
    playbackModes.clear();
    for (const timer of playbackTimers.values()) clearTimeout(timer);
    playbackTimers.clear();
    clearPlayingRows();
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
    root.append(channelTile(channel));
  }
}

function channelTile(channel) {
  const card = document.createElement("div");
  card.className = "channel";
  const title = document.createElement("strong");
  const meta = document.createElement("span");
  const select = document.createElement("button");
  const listen = document.createElement("button");
  select.onclick = () => {
    if (selected.has(channel.name)) selected.delete(channel.name);
    else selected.add(channel.name);
    updateAllTileButtons();
    renderFilterChips();
    loadRecentHistory(0);
  };
  listen.onclick = () => toggleLive(channel.name);
  card.append(title, meta, select, listen);
  channelNodes.set(card, { title, meta, select, listen });
  updateTile(card, channel);
  return card;
}

function updateTile(card, channel) {
  const nodes = channelNodes.get(card);
  card.dataset.channelId = String(channel.id);
  card.className = `channel${channel.active ? " active-channel" : ""}`;
  nodes.title.textContent = channel.name;
  nodes.meta.textContent = lastActiveText(channel);
  nodes.select.textContent = selected.has(channel.name) ? "Filtering" : "Filter history";
  nodes.select.className = selected.has(channel.name) ? "active" : "";
  nodes.listen.textContent = live.has(channel.name) ? "Listening" : "Listen";
  nodes.listen.className = live.has(channel.name) ? "live" : "";
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
  updateChannelTileByName(name);
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
  const mode = playbackModeFor(name);
  if (mode?.realtime) {
    startTime = mode.originAudioTime + (targetMillis - mode.originMillis) / 1000;
  } else {
    startTime = liveStartTime(name, targetMillis, frame.duration ? frame.duration / 1_000_000 : audio.duration);
  }
  source.start(Math.max(audioContext.currentTime, startTime));
  frame.close();
}

function playbackModeFor(streamKey) {
  if (!streamKey.startsWith("history:")) {
    return null;
  }
  const parts = streamKey.split(":");
  return playbackModes.get(`${parts[0]}:${parts[1]}`);
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
    .filter(channel => channel.active || Date.now() - channel.lastActivityMillis <= RECENT_ACTIVITY_WINDOW_MILLIS)
    .sort((a, b) => a.frequencyHz - b.frequencyHz);
}

function updateChannel(update) {
  const index = channels.findIndex(channel => channel.id === update.id);
  if (index >= 0) {
    channels[index] = { ...channels[index], ...update };
  } else {
    channels.push(update);
    channels.sort((a, b) => a.frequencyHz - b.frequencyHz);
  }
}

function renderChannelsIfOpen() {
  if (channelsDetails.open) {
    render(channelsEl, channels);
  } else {
    channelsEl.replaceChildren();
  }
}

function updateChannelTile(channelId) {
  const channel = channels.find(channel => channel.id === channelId);
  if (!channel) return;
  for (const root of [channelsEl, recentEl]) {
    for (const card of root.querySelectorAll(`[data-channel-id="${channelId}"]`)) {
      updateTile(card, channel);
    }
  }
}

function updateChannelTileByName(name) {
  const channel = channels.find(channel => channel.name === name);
  if (channel) updateChannelTile(channel.id);
}

function updateAllTileButtons() {
  for (const root of [channelsEl, recentEl]) {
    for (const card of root.querySelectorAll(".channel")) {
      const channel = channels.find(item => String(item.id) === card.dataset.channelId);
      if (channel) updateTile(card, channel);
    }
  }
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
  updateRangeInputs(message.utterances);
  const list = document.createElement("div");
  list.className = "history-list";
  for (const utterance of message.utterances) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "utterance";
    const channel = document.createElement("strong");
    channel.textContent = utterance.channel;
    const time = document.createElement("span");
    time.textContent = zulu(utterance.startMillis);
    const length = document.createElement("span");
    length.textContent = `${duration(utterance)}s`;
    const action = document.createElement("span");
    action.className = "utterance-action";
    action.textContent = "Play";
    row.append(channel, time, length);
    row.append(action);
    row.dataset.utteranceKey = utteranceKey(utterance);
    row.onclick = () => playUtterance(utterance, row);
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

function updateRangeInputs(utterances) {
  if (utterances.length === 0) {
    return;
  }
  const from = Math.min(...utterances.map(utterance => utterance.startMillis));
  const to = Math.max(...utterances.map(utterance => utterance.endMillis));
  document.querySelector("#from").value = localDateTime(from);
  document.querySelector("#to").value = localDateTime(to);
}

function localDateTime(millis) {
  const date = new Date(millis);
  const offsetMillis = date.getTimezoneOffset() * 60_000;
  return new Date(millis - offsetMillis).toISOString().slice(0, 16);
}

function renderFilterChips() {
  filterChipsEl.replaceChildren();
  if (selected.size === 0) {
    const empty = document.createElement("span");
    empty.className = "filter-empty";
    empty.textContent = "History filter: all channels";
    filterChipsEl.append(empty);
    return;
  }
  for (const name of [...selected].sort()) {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "filter-chip";
    chip.textContent = `${name} x`;
    chip.onclick = () => {
      selected.delete(name);
      updateAllTileButtons();
      renderFilterChips();
      loadRecentHistory(0);
    };
    filterChipsEl.append(chip);
  }
}

function armPlaybackExpiry(id, playback) {
  const oldTimer = playbackTimers.get(id);
  if (oldTimer) clearTimeout(oldTimer);
  const realtimeMillis = Math.max(0, playback.toMillis - playback.fromMillis);
  const compactMillis = Math.max(0, playback.frames * 20);
  const playbackMillis = playback.realtime ? realtimeMillis : compactMillis;
  const timer = setTimeout(() => {
    playbackModes.delete(`history:${id}`);
    playbackTimers.delete(id);
  }, Math.min(Math.max(playbackMillis + 750, 1000), 3_600_000));
  playbackTimers.set(id, timer);
}

function duration(utterance) {
  return Math.max(0, (utterance.endMillis - utterance.startMillis) / 1000).toFixed(1);
}

function playUtterance(utterance, row) {
  if (!ensureAudio()) return;
  markUtterancePlaying(utterance, row);
  send({
    type: "play_utterance",
    channels: [utterance.channel],
    fromMillis: utterance.startMillis,
    toMillis: utterance.endMillis,
    realtime: false
  });
}

function utteranceKey(utterance) {
  return `${utterance.channel}:${utterance.startMillis}:${utterance.endMillis}`;
}

function markUtterancePlaying(utterance, row) {
  const key = utteranceKey(utterance);
  for (const existing of utteranceRows(key)) {
    existing.classList.add("playing-row");
    const action = existing.querySelector(".utterance-action");
    if (action) action.textContent = "Playing";
  }
  const oldTimer = rowPlaybackTimers.get(key);
  if (oldTimer) clearTimeout(oldTimer);
  const timer = setTimeout(() => clearPlayingRow(key), Math.max(1000, utterance.endMillis - utterance.startMillis + 750));
  rowPlaybackTimers.set(key, timer);
}

function clearPlayingRow(key) {
  rowPlaybackTimers.delete(key);
  for (const row of utteranceRows(key)) {
    row.classList.remove("playing-row");
    const action = row.querySelector(".utterance-action");
    if (action) action.textContent = "Play";
  }
}

function utteranceRows(key) {
  return [...historyEl.querySelectorAll(".utterance")]
    .filter(row => row.dataset.utteranceKey === key);
}

function clearPlayingRows() {
  for (const timer of rowPlaybackTimers.values()) clearTimeout(timer);
  rowPlaybackTimers.clear();
  for (const row of historyEl.querySelectorAll(".playing-row")) {
    row.classList.remove("playing-row");
    const action = row.querySelector(".utterance-action");
    if (action) action.textContent = "Play";
  }
}

function refreshDisplay() {
  for (const card of recentEl.querySelectorAll(".channel")) {
    const channel = channels.find(item => String(item.id) === card.dataset.channelId);
    if (channel) updateTile(card, channel);
  }
  const visibleIds = new Set(visibleRecent(channels).map(channel => String(channel.id)));
  for (const card of recentEl.querySelectorAll(".channel")) {
    if (!visibleIds.has(card.dataset.channelId)) {
      card.remove();
    }
  }
}

filterEl.addEventListener("input", renderChannelsIfOpen);
channelsDetails.addEventListener("toggle", renderChannelsIfOpen);
document.querySelector("#refresh").onclick = () => {
  send({ type: "list_channels" });
  loadRecentHistory(historyPage);
};
document.querySelector("#play-history").onclick = () => {
  if (!ensureAudio()) return;
  send({
    type: "play_history",
    channels: historyChannels(),
    fromMillis: localMillis("#from"),
    toMillis: localMillis("#to") || Date.now(),
    realtime: realtimeGapsEl.checked
  });
};
document.querySelector("#stop-history").onclick = () => send({ type: "stop_history" });

renderFilterChips();
connect();

function historyChannels() {
  return selected.size > 0 ? [...selected] : channels.map(channel => channel.name);
}
