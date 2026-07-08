<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { SvelteMap } from 'svelte/reactivity';
  import ChannelTile from './components/ChannelTile.svelte';
  import { AudioEngine } from './lib/audio';
  import type { Channel, OpusPacket, PlaybackMode, ServerMessage, Utterance } from './lib/types';
  import { connectSocket, send } from './lib/ws';

  type RowPlayback = {
    active: boolean;
    startAt: number;
    endAt: number;
    startTimer: ReturnType<typeof setTimeout> | null;
    endTimer: ReturnType<typeof setTimeout> | null;
  };

  const historyPageSize = 20;
  const recentWindowMillis = 180_000;

  let socket: WebSocket | null = null;
  let status = 'Connecting...';
  const channelsById = new SvelteMap<number, Channel>();
  let channelVersion = 0;
  let selected = new Set<string>();
  let live = new Set<string>();
  let filter = '';
  let recentHistory: Utterance[] = [];
  let historyPage = 0;
  let historyTotal = 0;
  let realtimeGaps = false;
  let fromValue = '';
  let toValue = '';
  let now = Date.now();
  let channelsOpen = false;

  const audio = new AudioEngine();
  const playbackModes = new Map<string, PlaybackMode>();
  const playbackChannels = new Map<number, string[]>();
  const playbackStreams = new Map<number, Set<string>>();
  const serverFinishedPlaybacks = new Set<number>();
  let playingRows = new Set<string>();
  const playingRowTimers = new Map<string, RowPlayback>();
  let replayingLast = new Set<string>();
  let tickTimer: ReturnType<typeof setInterval>;

  $: channelList = sortedChannels(channelVersion);
  $: filteredChannels = channelList.filter(channel => channel.name.toLowerCase().includes(filter.trim().toLowerCase()));
  $: recentChannels = channelList
    .filter(channel => channel.lastActivityMillis > 0)
    .filter(channel => channel.active || now - channel.lastActivityMillis <= recentWindowMillis)
    .sort((a, b) => a.frequencyHz - b.frequencyHz);
  $: historyPages = Math.max(1, Math.ceil(historyTotal / historyPageSize));

  onMount(() => {
    audio.onScheduled(handleFrameScheduled);
    audio.onIdle(handleStreamIdle);
    socket = connectSocket(handleMessage, handlePacket, next => status = next);
    tickTimer = setInterval(() => now = Date.now(), 1000);
    return () => {
      socket?.close();
      clearInterval(tickTimer);
      clearPlayingRows();
    };
  });

  onDestroy(() => socket?.close());

  function handleMessage(message: ServerMessage) {
    switch (message.type) {
      case 'channels':
        channelsById.clear();
        for (const channel of message.channels) {
          channelsById.set(channel.id, channel);
        }
        channelVersion += 1;
        loadRecentHistory(0);
        break;
      case 'activity':
        updateChannel(message.channel);
        break;
      case 'utterance_closed':
        appendLiveUtterance(message.utterance);
        break;
      case 'recent_history':
        historyPage = message.page;
        historyTotal = message.total;
        recentHistory = message.utterances;
        updateRangeInputs(message.utterances);
        break;
      case 'history_started': {
        const modeKey = `history:${message.playbackId}`;
        playbackModes.set(modeKey, {
          realtime: message.realtime,
          originMillis: message.fromMillis,
          originAudioTime: audio.audioTime(0.15)
        });
        playbackChannels.set(message.playbackId, message.channels);
        status = `Streaming ${message.frames} historical packets...`;
        break;
      }
      case 'history_finished':
        finishPlayback(message.playbackId);
        break;
      case 'history_stopped':
        clearHistoryPlaybackState(true);
        status = 'Historical playback stopped';
        break;
      case 'error':
        replayingLast = new Set();
        status = message.message;
        break;
    }
  }

  function handlePacket(packet: OpusPacket) {
    try {
      trackHistoryPacket(packet);
      audio.enqueue(packet, playbackModeFor(packet.streamKey));
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
    }
  }

  function playbackModeFor(streamKey: string) {
    if (!streamKey.startsWith('history:')) return null;
    const [kind, id] = streamKey.split(':');
    return playbackModes.get(`${kind}:${id}`) ?? null;
  }

  function updateChannel(update: Channel) {
    channelsById.set(update.id, { ...(channelsById.get(update.id) ?? update), ...update });
    channelVersion += 1;
  }

  function sortedChannels(_version: number) {
    return Array.from(channelsById.values()).sort((a, b) => a.frequencyHz - b.frequencyHz);
  }

  function showChannelHistory(name: string) {
    selected = new Set([name]);
    loadRecentHistory(0);
  }

  function addChannelHistoryFilter(name: string) {
    const next = new Set(selected);
    next.add(name);
    selected = next;
    loadRecentHistory(0);
  }

  function removeSelected(name: string) {
    const next = new Set(selected);
    next.delete(name);
    selected = next;
    loadRecentHistory(0);
  }

  function toggleLive(name: string) {
    try {
      audio.ensure();
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
      return;
    }
    const next = new Set(live);
    if (next.has(name)) {
      next.delete(name);
      audio.flush(name);
      send(socket, { type: 'unsubscribe_live', channels: [name] });
    } else {
      next.add(name);
      send(socket, { type: 'subscribe_live', channels: [name] });
    }
    live = next;
  }

  function toggleLastUtterance(name: string) {
    if (replayingLast.has(name)) {
      stopHistory();
      return;
    }
    try {
      audio.ensure();
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
      return;
    }
    clearHistoryPlaybackState(true);
    const next = new Set(replayingLast);
    next.add(name);
    replayingLast = next;
    send(socket, { type: 'play_last_utterance', channels: [name] });
  }

  function loadRecentHistory(page: number) {
    send(socket, { type: 'recent_history', page, pageSize: historyPageSize, channels: [...selected] });
  }

  function playUtterance(utterance: Utterance) {
    try {
      audio.ensure();
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
      return;
    }
    clearHistoryPlaybackState(true);
    markPlaying(utterance);
    send(socket, {
      type: 'play_utterance',
      channels: [utterance.channel],
      fromMillis: utterance.startMillis,
      toMillis: utterance.endMillis,
      realtime: false
    });
  }

  function toggleUtterancePlayback(utterance: Utterance) {
    if (playingRows.has(utteranceKey(utterance))) {
      stopHistory();
    } else {
      playUtterance(utterance);
    }
  }

  function playSince(utterance: Utterance) {
    try {
      audio.ensure();
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
      return;
    }
    clearHistoryPlaybackState(true);
    send(socket, {
      type: 'play_history',
      channels: selected.size ? [...selected] : channelList.map(channel => channel.name),
      fromMillis: utterance.startMillis,
      toMillis: Date.now(),
      realtime: realtimeGaps
    });
  }

  function playRange() {
    try {
      audio.ensure();
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
      return;
    }
    clearHistoryPlaybackState(true);
    send(socket, {
      type: 'play_history',
      channels: selected.size ? [...selected] : channelList.map(channel => channel.name),
      fromMillis: localMillis(fromValue),
      toMillis: localMillis(toValue) || Date.now(),
      realtime: realtimeGaps
    });
  }

  function stopHistory() {
    clearHistoryPlaybackState(true);
    send(socket, { type: 'stop_history' });
  }

  function clearHistoryPlaybackState(flushAudio: boolean) {
    if (flushAudio) {
      audio.flushPrefix('history:');
    }
    playbackModes.clear();
    playbackChannels.clear();
    playbackStreams.clear();
    serverFinishedPlaybacks.clear();
    replayingLast = new Set();
    clearPlayingRows();
  }

  function finishPlayback(playbackId: number) {
    serverFinishedPlaybacks.add(playbackId);
    completePlaybackIfDrained(playbackId);
  }

  function trackHistoryPacket(packet: OpusPacket) {
    const id = historyPlaybackId(packet.streamKey);
    if (id === null) return;
    let streams = playbackStreams.get(id);
    if (!streams) {
      streams = new Set();
      playbackStreams.set(id, streams);
    }
    streams.add(packet.streamKey);
  }

  function handleStreamIdle(streamKey: string) {
    const id = historyPlaybackId(streamKey);
    if (id === null) return;
    const streams = playbackStreams.get(id);
    if (streams) {
      streams.delete(streamKey);
      if (streams.size === 0) {
        playbackStreams.delete(id);
      }
    }
    completePlaybackIfDrained(id);
  }

  function completePlaybackIfDrained(playbackId: number) {
    if (!serverFinishedPlaybacks.has(playbackId)) return;
    const streams = playbackStreams.get(playbackId);
    if (streams?.size) return;

    const channels = playbackChannels.get(playbackId) ?? [];
    const next = new Set(replayingLast);
    for (const channel of channels) next.delete(channel);
    replayingLast = next;
    playbackModes.delete(`history:${playbackId}`);
    playbackChannels.delete(playbackId);
    serverFinishedPlaybacks.delete(playbackId);
    clearPlayingRows();
    status = 'Historical playback finished';
  }

  function handleFrameScheduled(streamKey: string, targetMillis: number, startTime: number, durationSeconds: number) {
    if (!streamKey.startsWith('history:')) return;
    const utterance = recentHistory.find(item =>
      item.channel === historyChannelName(streamKey)
        && targetMillis >= item.startMillis
        && targetMillis <= item.endMillis);
    if (!utterance) return;
    const delayMillis = Math.max(0, (startTime - audio.currentTime()) * 1000);
    markPlaying(utterance, delayMillis, durationSeconds * 1000 + 150);
  }

  function historyPlaybackId(streamKey: string) {
    const match = /^history:(\d+):/.exec(streamKey);
    return match ? Number(match[1]) : null;
  }

  function historyChannelName(streamKey: string) {
    return streamKey.replace(/^history:\d+:/, '');
  }

  function updateRangeInputs(utterances: Utterance[]) {
    if (!utterances.length) return;
    fromValue = localDateTime(Math.min(...utterances.map(utterance => utterance.startMillis)));
    toValue = localDateTime(Math.max(...utterances.map(utterance => utterance.endMillis)));
  }

  function markPlaying(utterance: Utterance, delayMillis = 0, durationMillis = utterance.endMillis - utterance.startMillis + 750) {
    const key = utteranceKey(utterance);
    const nowMillis = performance.now();
    const startAt = nowMillis + Math.max(0, delayMillis);
    const endAt = startAt + Math.max(250, durationMillis);
    let row = playingRowTimers.get(key);

    if (!row) {
      row = { active: false, startAt, endAt, startTimer: null, endTimer: null };
      playingRowTimers.set(key, row);
    } else {
      row.endAt = Math.max(row.endAt, endAt);
      if (!row.active && startAt < row.startAt) {
        row.startAt = startAt;
        if (row.startTimer) clearTimeout(row.startTimer);
        row.startTimer = null;
      }
    }

    if (row.active) {
      scheduleRowEnd(key, row);
      return;
    }

    if (!row.startTimer) {
      row.startTimer = setTimeout(() => {
        row!.startTimer = null;
        row!.active = true;
        playingRows = new Set(playingRows).add(key);
        scheduleRowEnd(key, row!);
      }, Math.max(0, row.startAt - performance.now()));
    }
  }

  function scheduleRowEnd(key: string, row: RowPlayback) {
    if (row.endTimer) clearTimeout(row.endTimer);
    row.endTimer = setTimeout(() => {
      playingRowTimers.delete(key);
      playingRows.delete(key);
      playingRows = new Set(playingRows);
    }, Math.max(50, row.endAt - performance.now()));
  }

  function appendLiveUtterance(utterance: Utterance) {
    if (selected.size > 0 && !selected.has(utterance.channel)) {
      return;
    }
    historyTotal = Math.min(100, historyTotal + 1);
    if (historyPage !== 0) {
      return;
    }
    const key = utteranceKey(utterance);
    recentHistory = [utterance, ...recentHistory.filter(item => utteranceKey(item) !== key)]
      .sort((a, b) => b.startMillis - a.startMillis)
      .slice(0, historyPageSize);
    updateRangeInputs(recentHistory);
  }

  function clearPlayingRows() {
    for (const row of playingRowTimers.values()) {
      if (row.startTimer) clearTimeout(row.startTimer);
      if (row.endTimer) clearTimeout(row.endTimer);
    }
    playingRowTimers.clear();
    playingRows = new Set();
  }

  function utteranceKey(utterance: Utterance) {
    return `${utterance.channel}:${utterance.startMillis}:${utterance.endMillis}`;
  }

  function lastActiveText(channel: Channel) {
    if (channel.active) return 'active now';
    if (!channel.lastActivityMillis) return 'not active yet';
    const seconds = Math.max(0, Math.round((now - channel.lastActivityMillis) / 1000));
    if (seconds < 60) return `active ${seconds}s ago`;
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `active ${minutes}m ago`;
    return `active ${Math.round(minutes / 60)}h ago`;
  }

  function zulu(millis: number) {
    return new Date(millis).toISOString().replace('.000Z', 'Z');
  }

  function localDateTime(millis: number) {
    const date = new Date(millis);
    return new Date(millis - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  }

  function localMillis(value: string) {
    return value ? new Date(value).getTime() : 0;
  }

  function duration(utterance: Utterance) {
    return Math.max(0, (utterance.endMillis - utterance.startMillis) / 1000).toFixed(1);
  }
</script>

<main>
  <header>
    <h1>Jarband Airband</h1>
    <div class="status">{status}</div>
  </header>

  <section class="toolbar">
    <input bind:value={filter} type="search" placeholder="Filter channels" />
    <button type="button" onclick={() => send(socket, { type: 'list_channels' })}>Refresh</button>
    <button type="button" onclick={stopHistory}>Stop history</button>
  </section>

  <section>
    <h2>Recent Activity</h2>
    <div class="channel-grid">
      {#each recentChannels as channel (channel.id)}
        <ChannelTile name={channel.name} active={channel.active} lastActiveLabel={lastActiveText(channel)}
          selected={selected.has(channel.name)} live={live.has(channel.name)}
          replaying={replayingLast.has(channel.name)}
          onHistory={showChannelHistory} onAddFilter={addChannelHistoryFilter}
          onLive={toggleLive} onReplayLast={toggleLastUtterance} />
      {/each}
    </div>
  </section>

  <details bind:open={channelsOpen}>
    <summary>Channels</summary>
    {#if channelsOpen}
      <div class="channel-grid">
        {#each filteredChannels as channel (channel.id)}
          <ChannelTile name={channel.name} active={channel.active} lastActiveLabel={lastActiveText(channel)}
            selected={selected.has(channel.name)} live={live.has(channel.name)}
            replaying={replayingLast.has(channel.name)}
            onHistory={showChannelHistory} onAddFilter={addChannelHistoryFilter}
            onLive={toggleLive} onReplayLast={toggleLastUtterance} />
        {/each}
      </div>
    {/if}
  </details>

  <section>
    <h2>Historical Playback</h2>
    <div class="filter-chips">
      {#if selected.size === 0}
        <span class="filter-empty">History filter: all channels</span>
      {:else}
        {#each [...selected].sort() as name}
          <button type="button" class="filter-chip" onclick={() => removeSelected(name)}>{name} x</button>
        {/each}
      {/if}
    </div>

    <div class="history-controls">
      <label>From <input bind:value={fromValue} type="datetime-local" /></label>
      <label>To <input bind:value={toValue} type="datetime-local" /></label>
      <label class="checkbox"><input bind:checked={realtimeGaps} type="checkbox" /> Real-time gaps</label>
    <button type="button" onclick={playRange}>Play selected</button>
    </div>

    <div class="history-list">
      {#each recentHistory as utterance (utteranceKey(utterance))}
        <div class:playing-row={playingRows.has(utteranceKey(utterance))} class="utterance">
          <strong>{utterance.channel}</strong>
          <span>{zulu(utterance.startMillis)}</span>
          <span>{duration(utterance)}s</span>
          <div class="utterance-actions">
            <button type="button" class="icon-button" aria-label="Play or stop this recording"
              onclick={() => toggleUtterancePlayback(utterance)}>
              <span class="icon-symbol">{playingRows.has(utteranceKey(utterance)) ? '■' : '▶'}</span>
            </button>
            <button type="button" class="icon-button" aria-label="Play recordings since this time"
              onclick={() => playSince(utterance)}>
              <span class="icon-symbol">⤒</span>
            </button>
          </div>
        </div>
      {/each}
    </div>

    <div class="pager">
      <button type="button" disabled={historyPage <= 0} onclick={() => loadRecentHistory(historyPage - 1)}>Prev</button>
      <span>Page {historyPage + 1} of {historyPages}</span>
      <button type="button" disabled={(historyPage + 1) * historyPageSize >= historyTotal}
        onclick={() => loadRecentHistory(historyPage + 1)}>Next</button>
    </div>
  </section>
</main>
