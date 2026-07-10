<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { SvelteMap } from 'svelte/reactivity';
  import ChannelTile from './components/ChannelTile.svelte';
  import { AudioEngine } from './lib/audio';
  import { fetchHistoryIndex, fetchRecentHistory, fetchUtterancePackets } from './lib/historyHttp';
  import { HistoryPlaybackTracker } from './lib/historyPlayback';
  import { RowPlaybackHighlighter, utteranceKey } from './lib/rowPlayback';
  import { opusPacketsToWav } from './lib/wav';
  import type { Channel, OpusPacket, ServerMessage, Utterance } from './lib/types';
  import { connectSocket, send } from './lib/ws';

  const historyPageSize = 20;
  const recentWindowMillis = 180_000;

  type ScheduledHistoryFrame = {
    channel: string;
    targetMillis: number;
    startAtMillis: number;
    endAtMillis: number;
  };

  let socket: WebSocket | null = null;
  let status = 'Connecting...';
  const channelsById = new SvelteMap<number, Channel>();
  let channelVersion = 0;
  let selected = new Set<string>();
  let live = new Set<string>();
  let filter = '';
  let recentHistory: Utterance[] = [];
  let historyBeforeMillis = Number.POSITIVE_INFINITY;
  let historyBackStack: number[] = [];
  let historyHasOlder = false;
  let realtimeGaps = false;
  let fromValue = '';
  let toValue = '';
  let now = Date.now();
  let channelsOpen = false;

  const audio = new AudioEngine();
  const historyPlayback = new HistoryPlaybackTracker();
  const rowHighlighter = new RowPlaybackHighlighter(rows => playingRows = rows);
  let scheduledHistoryFrames: ScheduledHistoryFrame[] = [];
  let historyAbort: AbortController | null = null;
  let nextPlaybackId = 1;
  let playingRows = new Set<string>();
  let replayingLast = new Set<string>();
  let tickTimer: ReturnType<typeof setInterval>;

  $: channelList = sortedChannels(channelVersion);
  $: filteredChannels = channelList.filter(channel => channel.name.toLowerCase().includes(filter.trim().toLowerCase()));
  $: recentChannels = channelList
    .filter(channel => channel.lastActivityMillis > 0)
    .filter(channel => channel.active || now - channel.lastActivityMillis <= recentWindowMillis)
    .sort((a, b) => a.frequencyHz - b.frequencyHz);

  onMount(() => {
    audio.onScheduled(handleFrameScheduled);
    audio.onIdle(handleStreamIdle);
    socket = connectSocket(handleMessage, handlePacket, next => status = next);
    tickTimer = setInterval(() => now = Date.now(), 1000);
    return () => {
      socket?.close();
      clearInterval(tickTimer);
      rowHighlighter.clear();
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
        resetHistoryWindow();
        break;
      case 'activity':
        updateChannel(message.channel);
        break;
      case 'utterance_closed':
        appendLiveUtterance(message.utterance);
        break;
      case 'error':
        replayingLast = new Set();
        status = message.message;
        break;
    }
  }

  function handlePacket(packet: OpusPacket) {
    try {
      audio.enqueue(packet, playbackModeFor(packet.streamKey));
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
    }
  }

  function playbackModeFor(streamKey: string) {
    return historyPlayback.modeFor(streamKey);
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
    resetHistoryWindow();
  }

  function addChannelHistoryFilter(name: string) {
    const next = new Set(selected);
    next.add(name);
    selected = next;
    resetHistoryWindow();
  }

  function removeSelected(name: string) {
    const next = new Set(selected);
    next.delete(name);
    selected = next;
    resetHistoryWindow();
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
    void fetchRecentHistory([name], Number.POSITIVE_INFINITY, 1)
      .then(result => {
        if (!result.utterances.length) {
          replayingLast = new Set();
          status = `No recording for ${name}`;
          return;
        }
        return playHistoryUtterances(result.utterances, [name], result.utterances[0].startMillis, false);
      })
      .catch(error => {
        replayingLast = new Set();
        status = error instanceof Error ? error.message : String(error);
      });
  }

  function resetHistoryWindow() {
    historyBackStack = [];
    loadRecentHistory(Number.POSITIVE_INFINITY);
  }

  async function loadRecentHistory(beforeMillis = historyBeforeMillis) {
    if (selected.size === 0) {
      recentHistory = [];
      historyHasOlder = false;
      historyBeforeMillis = Number.POSITIVE_INFINITY;
      fromValue = '';
      toValue = '';
      return;
    }
    try {
      const message = await fetchRecentHistory([...selected], beforeMillis, historyPageSize);
      historyBeforeMillis = message.beforeMillis > 0 ? message.beforeMillis : Number.POSITIVE_INFINITY;
      historyHasOlder = message.hasOlder;
      recentHistory = message.utterances;
      updateRangeInputs(message.utterances);
      refreshVisiblePlaybackHighlights();
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
    }
  }

  function loadOlderHistory() {
    if (!recentHistory.length) return;
    const oldest = Math.min(...recentHistory.map(utterance => utterance.startMillis).filter(Number.isFinite));
    if (!Number.isFinite(oldest)) return;
    historyBackStack = [...historyBackStack, historyBeforeMillis];
    loadRecentHistory(oldest);
  }

  function loadNewerHistory() {
    const previous = historyBackStack.at(-1);
    if (previous === undefined) return;
    historyBackStack = historyBackStack.slice(0, -1);
    loadRecentHistory(previous);
  }

  function filterHistoryAtUtterance(utterance: Utterance) {
    selected = new Set([utterance.channel]);
    historyBackStack = [Number.POSITIVE_INFINITY];
    loadRecentHistory(utterance.startMillis + 1);
  }

  function filterHistoryFromKey(event: KeyboardEvent, utterance: Utterance) {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    filterHistoryAtUtterance(utterance);
  }

  function playUtterance(utterance: Utterance) {
    try {
      audio.ensure();
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
      return;
    }
    clearHistoryPlaybackState(true);
    rowHighlighter.mark(utterance);
    void playHistoryUtterances([utterance], [utterance.channel], utterance.startMillis, false);
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
    const channels = [...selected];
    void fetchHistoryIndex(channels, utterance.startMillis, Date.now())
      .then(utterances => playHistoryUtterances(utterances, channels, utterance.startMillis, realtimeGaps))
      .catch(error => status = error instanceof Error ? error.message : String(error));
  }

  function downloadUtterance(utterance: Utterance) {
    void downloadHistoryUtterance(utterance);
  }

  function stopHistory() {
    clearHistoryPlaybackState(true);
    status = 'Historical playback stopped';
  }

  function clearHistoryPlaybackState(flushAudio: boolean) {
    historyAbort?.abort();
    historyAbort = null;
    if (flushAudio) {
      audio.flushPrefix('history:');
    }
    historyPlayback.clear();
    replayingLast = new Set();
    scheduledHistoryFrames = [];
    rowHighlighter.clear();
  }

  function handleStreamIdle(streamKey: string) {
    completePlayback(historyPlayback.streamIdle(streamKey));
  }

  function completePlayback(completed: { playbackId: number; channels: string[] } | null) {
    if (!completed) return;
    const next = new Set(replayingLast);
    for (const channel of completed.channels) next.delete(channel);
    replayingLast = next;
    scheduledHistoryFrames = [];
    rowHighlighter.clear();
    status = 'Historical playback finished';
  }

  async function playHistoryUtterances(utterances: Utterance[], channels: string[], originMillis: number, realtime: boolean) {
    const playbackId = nextPlaybackId++;
    const abort = new AbortController();
    historyAbort = abort;
    historyPlayback.start(playbackId, channels, {
      realtime,
      originMillis,
      originAudioTime: audio.audioTime(0.15)
    });
    status = `Fetching ${utterances.length} historical recordings...`;
    try {
      const packets = (await Promise.all(utterances.map(utterance =>
        fetchUtterancePackets(utterance, `history:${playbackId}:${utterance.channel}`, abort.signal)
      ))).flat().sort((a, b) => a.unixMillis - b.unixMillis || a.channelName.localeCompare(b.channelName));
      for (const packet of packets) {
        if (abort.signal.aborted) break;
        historyPlayback.trackPacket(packet);
        audio.enqueue(packet, playbackModeFor(packet.streamKey));
      }
      completePlayback(historyPlayback.serverDone(playbackId));
      status = `Queued ${packets.length} historical packets`;
    } catch (error) {
      if (!abort.signal.aborted) {
        status = error instanceof Error ? error.message : String(error);
      }
    } finally {
      if (historyAbort === abort) {
        historyAbort = null;
      }
    }
  }

  async function downloadHistoryUtterance(utterance: Utterance) {
    try {
      status = `Preparing ${utterance.channel}-${utterance.startMillis}.wav...`;
      const packets = await fetchUtterancePackets(utterance, `download:${utterance.channel}`);
      const wav = await opusPacketsToWav(packets);
      const url = URL.createObjectURL(wav);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${utterance.channel}-${utterance.startMillis}.wav`;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 30_000);
      status = `Downloaded ${link.download}`;
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
    }
  }

  function handleFrameScheduled(streamKey: string, targetMillis: number, startTime: number, durationSeconds: number) {
    if (!streamKey.startsWith('history:')) return;
    const delayMillis = Math.max(0, (startTime - audio.currentTime()) * 1000);
    const startAtMillis = performance.now() + delayMillis;
    const endAtMillis = startAtMillis + durationSeconds * 1000 + 150;
    scheduledHistoryFrames = [
      ...scheduledHistoryFrames.filter(frame => frame.endAtMillis > performance.now()),
      {
        channel: historyPlayback.channelFromStreamKey(streamKey),
        targetMillis,
        startAtMillis,
        endAtMillis
      }
    ];
    const utterance = recentHistory.find(item =>
      item.channel === historyPlayback.channelFromStreamKey(streamKey)
        && targetMillis >= item.startMillis
        && targetMillis <= item.endMillis);
    if (!utterance) return;
    rowHighlighter.mark(utterance, delayMillis, durationSeconds * 1000 + 150);
  }

  function refreshVisiblePlaybackHighlights() {
    const nowMillis = performance.now();
    scheduledHistoryFrames = scheduledHistoryFrames.filter(frame => frame.endAtMillis > nowMillis);
    for (const utterance of recentHistory) {
      const frame = scheduledHistoryFrames.find(item =>
        item.channel === utterance.channel
          && item.targetMillis >= utterance.startMillis
          && item.targetMillis <= utterance.endMillis);
      if (!frame) continue;
      rowHighlighter.mark(utterance,
        Math.max(0, frame.startAtMillis - nowMillis),
        Math.max(250, frame.endAtMillis - Math.max(nowMillis, frame.startAtMillis)));
    }
  }

  function updateRangeInputs(utterances: Utterance[]) {
    if (!utterances.length) return;
    const starts = utterances.map(utterance => utterance.startMillis).filter(Number.isFinite);
    const ends = utterances.map(utterance => utterance.endMillis).filter(Number.isFinite);
    if (starts.length) fromValue = localDateTime(Math.min(...starts));
    if (ends.length) toValue = localDateTime(Math.max(...ends));
  }

  function appendLiveUtterance(utterance: Utterance) {
    if (selected.size > 0 && !selected.has(utterance.channel)) {
      return;
    }
    if (Number.isFinite(historyBeforeMillis)) {
      return;
    }
    const key = utteranceKey(utterance);
    recentHistory = [utterance, ...recentHistory.filter(item => utteranceKey(item) !== key)]
      .sort((a, b) => b.startMillis - a.startMillis)
      .slice(0, historyPageSize);
    historyHasOlder = historyHasOlder || recentHistory.length === historyPageSize;
    updateRangeInputs(recentHistory);
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

  function utcTime(millis: number) {
    if (!Number.isFinite(millis)) return '';
    const iso = new Date(millis).toISOString();
    return `${iso.slice(11, 19)}.${iso.charAt(20)}Z`;
  }

  function utcDate(millis: number) {
    if (!Number.isFinite(millis)) return '';
    return new Date(millis).toISOString().slice(0, 10);
  }

  function showDateSeparator(index: number) {
    if (index <= 0) return true;
    return utcDate(recentHistory[index].startMillis) !== utcDate(recentHistory[index - 1].startMillis);
  }

  function localDateTime(millis: number) {
    if (!Number.isFinite(millis)) return '';
    const date = new Date(millis);
    return new Date(millis - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  }

  function localMillis(value: string) {
    return value ? new Date(value).getTime() : 0;
  }

  function duration(utterance: Utterance) {
    const millis = utterance.durationMillis && utterance.durationMillis > 0
      ? utterance.durationMillis
      : utterance.endMillis - utterance.startMillis;
    return Number.isFinite(millis) ? Math.max(0, millis / 1000).toFixed(1) : '?';
  }

  function snr(utterance: Utterance) {
    return Number.isFinite(utterance.averageSnrDb) ? `${utterance.averageSnrDb!.toFixed(1)} dB` : '-';
  }

  function historyRangeLabel() {
    if (!recentHistory.length) return 'No recordings';
    const starts = recentHistory.map(utterance => utterance.startMillis).filter(Number.isFinite);
    if (!starts.length) return 'No valid timestamps';
    const newest = Math.max(...starts);
    const oldest = Math.min(...starts);
    const newestDate = utcDate(newest);
    const oldestDate = utcDate(oldest);
    if (newestDate === oldestDate) {
      return `${oldestDate} ${utcTime(oldest)} - ${utcTime(newest)}`;
    }
    return `${oldestDate} ${utcTime(oldest)} - ${newestDate} ${utcTime(newest)}`;
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
        <span class="filter-empty">Select a channel to show history</span>
      {:else}
        {#each [...selected].sort() as name}
          <button type="button" class="filter-chip" aria-label={`Remove ${name} from history filter`}
            onclick={() => removeSelected(name)}>
            <span aria-hidden="true" class="filter-chip-remove">×</span>
            <span>{name}</span>
          </button>
        {/each}
      {/if}
    </div>

    <div class="history-controls">
      <label>From <input bind:value={fromValue} type="datetime-local" /></label>
      <label>To <input bind:value={toValue} type="datetime-local" /></label>
      <label class="checkbox"><input bind:checked={realtimeGaps} type="checkbox" /> Real-time gaps</label>
    </div>

    <div class="history-list">
      {#each recentHistory as utterance, index (utteranceKey(utterance))}
        {#if showDateSeparator(index)}
          <div class="history-date">{utcDate(utterance.startMillis)}</div>
        {/if}
        <div class:playing-row={playingRows.has(utteranceKey(utterance))} class="utterance">
          <span class="utterance-channel" role="link" tabindex="0"
            aria-label={`Filter history to ${utterance.channel}`}
            onclick={() => filterHistoryAtUtterance(utterance)}
            onkeydown={(event) => filterHistoryFromKey(event, utterance)}>
            {utterance.channel}
          </span>
          <span>{utcTime(utterance.startMillis)}</span>
          <span>{duration(utterance)}s</span>
          <span>{snr(utterance)}</span>
          <div class="utterance-actions">
            <button type="button" class="icon-button" aria-label="Play or stop this recording"
              onclick={() => toggleUtterancePlayback(utterance)}>
              <span class="icon-symbol">{playingRows.has(utteranceKey(utterance)) ? '■' : '▶'}</span>
            </button>
            <button type="button" class="icon-button" aria-label="Play recordings since this time"
              onclick={() => playSince(utterance)}>
              <span class="icon-symbol">⤒</span>
            </button>
            <button type="button" class="icon-button" aria-label="Download this recording as WAV"
              onclick={() => downloadUtterance(utterance)}>
              <span class="icon-symbol">↓</span>
            </button>
          </div>
        </div>
      {/each}
    </div>

    <div class="pager">
      <button type="button" disabled={historyBackStack.length === 0} onclick={loadNewerHistory}>Newer</button>
      <span>{historyRangeLabel()}</span>
      <button type="button" disabled={!historyHasOlder} onclick={loadOlderHistory}>Older</button>
    </div>
  </section>
</main>
