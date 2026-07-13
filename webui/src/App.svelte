<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { SvelteMap } from 'svelte/reactivity';
  import ChannelTile from './components/ChannelTile.svelte';
  import HistoryTimeline from './components/HistoryTimeline.svelte';
  import { AudioEngine, type AudioRoute } from './lib/audio';
  import { channelAccentColor } from './lib/channelColors';
  import {
    appendHistoryRecordingBytes,
    fetchHistoryIndex,
    fetchRecentHistory,
    fetchUtterancePacketWindow,
    fetchUtterancePackets,
    historyRangeCacheStats,
    rememberHistoryRecordingFile,
    type HistoryPacketWindowRequest
  } from './lib/historyHttp';
  import { RowPlaybackHighlighter, utteranceKey } from './lib/rowPlayback';
  import { opusPacketsToWav } from './lib/wav';
  import type { Channel, OpusPacket, PlaybackMode, RecordingBytes, ServerMessage, Utterance } from './lib/types';
  import { connectSocket, send } from './lib/ws';

  const historyPageSize = 20;
  const recentWindowMillis = 180_000;
  const defaultTimelineWindowMillis = 60 * 60 * 1000;
  const minTimelineWindowMillis = 60 * 1000;
  const historySkipMillis = 5_000;
  const playbackFetchWindowMillis = 30_000;
  const halfHourMillis = 30 * 60 * 1000;
  const playbackVolumeStorageKey = 'jarband.playbackVolume';

  type ScheduledHistoryFrame = {
    channel: string;
    targetMillis: number;
    startAtMillis: number;
    endAtMillis: number;
  };

  type HistoryUtteranceCursor = {
    nextOffset: number;
    nextMillis: number;
    complete: boolean;
  };

  type CompactPlaybackInterval = {
    startMillis: number;
    endMillis: number;
    playbackStartMillis: number;
  };

  type HistoryPlaybackSession = {
    playbackId: number;
    channels: string[];
    utterances: Utterance[];
    originMillis: number;
    compact: boolean;
    compactIntervals: CompactPlaybackInterval[];
    playbackMode: PlaybackMode;
    jumpRealtimeOnFinish: boolean;
    abort: AbortController;
    nextMillis: number;
    cursors: Map<string, HistoryUtteranceCursor>;
    activeStreams: Set<string>;
    fetching: boolean;
  };

  type HistorySelectionOptions = {
    playRealtimeWhenStarting?: boolean;
  };

  let socket: WebSocket | null = null;
  let status = 'Connecting...';
  let socketSubscriptions = new Set<string>();
  const channelsById = new SvelteMap<number, Channel>();
  let channelVersion = 0;
  let selected = new Set<string>();
  let live = new Set<string>();
  const historyAudioRoutes = new SvelteMap<string, AudioRoute>();
  let filter = '';
  let recentHistory: Utterance[] = [];
  let historyHasOlder = false;
  let skipSilence = true;
  let showHistoryList = false;
  let historyDateValue = '';
  let historyHourSlot = 0;
  let timelineFromMillis = 0;
  let timelineToMillis = 0;
  let timelineFollowRealtime = false;
  let playheadMillis = 0;
  let historyPlaying = false;
  let historyPaused = false;
  let realtimePlayback = false;
  let playbackSpeed = 1;
  let playbackVolume = 1;
  let wideView = false;
  let playbackClockOriginMillis = 0;
  let playbackClockStartedAt = 0;
  let timelineLoadTimer: ReturnType<typeof setTimeout> | null = null;
  let historyWindowRequestId = 0;
  let now = Date.now();
  let rangeCacheBytes = 0;
  let lastRangeCacheStatsAt = 0;
  let channelsOpen = false;

  const audio = new AudioEngine();
  const rowHighlighter = new RowPlaybackHighlighter(rows => playingRows = rows);
  let scheduledHistoryFrames: ScheduledHistoryFrame[] = [];
  let historySession: HistoryPlaybackSession | null = null;
  let nextPlaybackId = 1;
  let playingRows = new Set<string>();
  let replayingLast = new Set<string>();
  let tickTimer: ReturnType<typeof setInterval>;

  const routeOptions: { route: AudioRoute; label: string; description: string }[] = [
    { route: 'left', label: 'L', description: 'Route to left earcup' },
    { route: 'right', label: 'R', description: 'Route to right earcup' },
    { route: 'both', label: 'M', description: 'Route to both earcups' }
  ];

  $: channelList = sortedChannels(channelVersion);
  $: recordedChannels = channelList.filter(channel => channel.lastActivityMillis > 0);
  $: filteredChannels = recordedChannels.filter(channel => channel.name.toLowerCase().includes(filter.trim().toLowerCase()));
  $: recentChannels = recordedChannels
    .filter(channel => channel.active || now - channel.lastActivityMillis <= recentWindowMillis)
    .sort((a, b) => a.frequencyHz - b.frequencyHz);
  $: timelineSpanMillis = Math.max(minTimelineWindowMillis, timelineToMillis - timelineFromMillis);
  $: activeTimelineChannels = channelList
    .filter(channel => selected.has(channel.name) && channel.active && channel.lastActivityMillis > 0)
    .map(channel => ({ name: channel.name, startMillis: channel.lastActivityMillis }));

  onMount(() => {
    playbackVolume = storedPlaybackVolume(playbackVolume);
    audio.setVolume(playbackVolume);
    audio.onScheduled(handleFrameScheduled);
    audio.onIdle(handleStreamIdle);
    socket = connectSocket(handleMessage, handlePacket, handleRecordingBytes, next => {
      status = next;
      if (next === 'Connected') {
        socketSubscriptions = new Set();
        syncSocketSubscriptions();
      }
    }, nextSocket => socket = nextSocket);
    tickTimer = setInterval(() => {
      now = Date.now();
      if (now - lastRangeCacheStatsAt >= 500) {
        rangeCacheBytes = historyRangeCacheStats().bytes;
        lastRangeCacheStatsAt = now;
      }
      updateRealtimeTimelineWindow();
      updateTimelinePlaybackClock();
    }, 100);
    return () => {
      socket?.close();
      clearInterval(tickTimer);
      if (timelineLoadTimer) clearTimeout(timelineLoadTimer);
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
    if (!shouldPlayIncomingPacket(packet)) {
      return;
    }
    try {
      audio.enqueue(packet, playbackModeFor(packet.streamKey));
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
    }
  }

  function handleRecordingBytes(bytes: RecordingBytes) {
    appendHistoryRecordingBytes(bytes.recordingUrl, bytes.offset, bytes.data);
    const session = historySession;
    if (session && !session.fetching && session.activeStreams.size === 0 && bytes.recordingUrl.endsWith('.opus')) {
      void queueNextHistoryWindow(session);
    }
  }

  function playbackModeFor(streamKey: string) {
    return historySession && isSessionStream(streamKey, historySession)
      ? historySession.playbackMode
      : null;
  }

  function shouldPlayIncomingPacket(packet: OpusPacket) {
    if (packet.streamKey.startsWith('history:')) return true;
    if (live.has(packet.channelName)) return true;
    return selected.has(packet.channelName) && realtimePlayback && historyPlaying && !historyPaused;
  }

  function updateChannel(update: Channel) {
    channelsById.set(update.id, { ...(channelsById.get(update.id) ?? update), ...update });
    channelVersion += 1;
  }

  function sortedChannels(_version: number) {
    return Array.from(channelsById.values()).sort((a, b) => a.frequencyHz - b.frequencyHz);
  }

  function showChannelHistory(name: string) {
    updateHistorySelection(new Set([name]), { playRealtimeWhenStarting: true });
  }

  function addChannelHistoryFilter(name: string) {
    const next = new Set(selected);
    next.add(name);
    updateHistorySelection(next);
  }

  function removeSelected(name: string) {
    const next = new Set(selected);
    next.delete(name);
    updateHistorySelection(next);
  }

  function updateHistorySelection(next: Set<string>, options: HistorySelectionOptions = {}) {
    const previous = selected;
    const hadSelection = previous.size > 0;
    const wasPlaying = historyPlaying;
    const wasPaused = historyPaused;
    const wasRealtime = realtimePlayback && historyPlaying && !historyPaused;
    const resumeMillis = currentTimelinePlayhead();

    if (next.size === 0) {
      clearHistoryPlaybackState(true);
      selected = next;
      syncSocketSubscriptions();
      resetHistoryWindow();
      return;
    }

    if (sameSet(previous, next)) {
      return;
    }

    const removedChannels = [...previous].filter(name => !next.has(name));
    const addedChannels = [...next].filter(name => !previous.has(name));
    selected = next;
    syncSocketSubscriptions();

    for (const channel of removedChannels) {
      removeHistoryChannelFromPlayback(channel);
      if (!live.has(channel)) {
        audio.flush(channel);
      }
    }

    if (!hadSelection) {
      resetHistoryWindow();
      if (options.playRealtimeWhenStarting) {
        startRealtimeHistory();
      }
      return;
    }

    playheadMillis = resumeMillis;
    void loadHistoryWindow();

    if (!wasPlaying || wasPaused) {
      historyPaused = wasPaused;
      return;
    }

    if (wasRealtime) {
      historyPlaying = true;
      historyPaused = false;
      realtimePlayback = true;
      playbackClockOriginMillis = 0;
      playbackClockStartedAt = 0;
      return;
    }

    if (addedChannels.length > 0) {
      seekHistoryTo(resumeMillis, false);
    }
  }

  function channelAudioRoute(name: string) {
    return historyAudioRoutes.get(name) ?? 'both';
  }

  function setChannelAudioRoute(name: string, route: AudioRoute) {
    historyAudioRoutes.set(name, route);
    audio.setChannelRoute(name, route);
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
    } else {
      next.add(name);
    }
    live = next;
    syncSocketSubscriptions();
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
    realtimePlayback = false;
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
    const endMillis = Date.now();
    playheadMillis = 0;
    setHistoryWindow(endMillis - defaultTimelineWindowMillis, endMillis);
  }

  function startRealtimeHistory() {
    historyPlaying = true;
    historyPaused = false;
    realtimePlayback = true;
    playheadMillis = timelineToMillis || Date.now();
  }

  function loadOlderHistory() {
    if (selected.size === 0) return;
    shiftHistoryWindow(-timelineSpanMillis);
  }

  function loadNewerHistory() {
    if (selected.size === 0) return;
    shiftHistoryWindow(timelineSpanMillis);
  }

  function filterHistoryAtUtterance(utterance: Utterance) {
    clearHistoryPlaybackState(true);
    selected = new Set([utterance.channel]);
    syncSocketSubscriptions();
    const halfWindow = defaultTimelineWindowMillis / 2;
    setHistoryWindow(utterance.startMillis - halfWindow, utterance.startMillis + halfWindow);
  }

  function syncSocketSubscriptions() {
    if (socket?.readyState !== WebSocket.OPEN) return;
    const desired = new Set([...live, ...selected]);
    const subscribe = [...desired].filter(name => !socketSubscriptions.has(name));
    const unsubscribe = [...socketSubscriptions].filter(name => !desired.has(name));
    if (subscribe.length) {
      send(socket, { type: 'subscribe_live', channels: subscribe });
    }
    if (unsubscribe.length) {
      send(socket, { type: 'unsubscribe_live', channels: unsubscribe });
    }
    socketSubscriptions = desired;
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
    realtimePlayback = false;
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
    realtimePlayback = false;
    const channels = [...selected];
    void fetchHistoryIndex(channels, utterance.startMillis, Date.now())
      .then(utterances => playHistoryUtterances(utterances, channels, utterance.startMillis, !skipSilence, {
        jumpRealtimeOnFinish: skipSilence
      }))
      .catch(error => status = error instanceof Error ? error.message : String(error));
  }

  function setHistoryWindow(fromMillis: number, toMillis: number, loadDelayMillis = 0) {
    const latestTo = Date.now();
    const followsRealtime = toMillis >= latestTo - 1000;
    const safeTo = Math.min(latestTo, Math.max(fromMillis + minTimelineWindowMillis, toMillis));
    const safeFrom = Math.max(0, Math.min(fromMillis, safeTo - minTimelineWindowMillis));
    timelineFromMillis = safeFrom;
    timelineToMillis = safeTo;
    timelineFollowRealtime = followsRealtime;
    if (!playheadMillis) {
      playheadMillis = safeTo;
    }
    syncHistoryRangeControls((safeFrom + safeTo) / 2);
    if (timelineLoadTimer) clearTimeout(timelineLoadTimer);
    if (loadDelayMillis < 0) {
      return;
    }
    if (loadDelayMillis > 0) {
      timelineLoadTimer = setTimeout(() => {
        timelineLoadTimer = null;
        void loadHistoryWindow();
      }, loadDelayMillis);
    } else {
      void loadHistoryWindow();
    }
  }

  function updateRealtimeTimelineWindow() {
    if (!timelineFollowRealtime || timelineToMillis <= timelineFromMillis) return;
    const latestTo = Date.now();
    const span = timelineSpanMillis;
    timelineToMillis = latestTo;
    timelineFromMillis = Math.max(0, latestTo - span);
    if (!historyPlaying || historyPaused || scheduledHistoryFrames.length === 0) {
      playheadMillis = timelineToMillis;
    }
    syncHistoryRangeControls((timelineFromMillis + timelineToMillis) / 2);
  }

  async function loadHistoryWindow() {
    if (selected.size === 0) {
      historyWindowRequestId += 1;
      recentHistory = [];
      historyHasOlder = false;
      return;
    }
    const requestId = ++historyWindowRequestId;
    try {
      const utterances = await fetchHistoryIndex([...selected], timelineFromMillis, timelineToMillis);
      if (requestId !== historyWindowRequestId) return;
      recentHistory = utterances.slice().sort((a, b) => b.startMillis - a.startMillis || a.channel.localeCompare(b.channel));
      historyHasOlder = true;
      refreshVisiblePlaybackHighlights();
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
    }
  }

  function shiftHistoryWindow(deltaMillis: number) {
    const span = timelineSpanMillis;
    const latestTo = Date.now();
    let nextFrom = timelineFromMillis + deltaMillis;
    let nextTo = timelineToMillis + deltaMillis;
    if (nextTo > latestTo) {
      nextTo = latestTo;
      nextFrom = latestTo - span;
    }
    setHistoryWindow(nextFrom, nextTo);
  }

  function seekHistoryTo(targetMillis: number, clampToTimeline = true) {
    try {
      audio.ensure();
    } catch (error) {
      status = error instanceof Error ? error.message : String(error);
      return;
    }
    clearHistoryPlaybackState(true);
    realtimePlayback = false;
    const boundedTargetMillis = clampToTimeline ? clamp(targetMillis, timelineFromMillis, timelineToMillis) : Math.max(0, targetMillis);
    playheadMillis = boundedTargetMillis;
    const channels = [...selected];
    const playbackToMillis = Date.now();
    void fetchHistoryIndex(channels, boundedTargetMillis, playbackToMillis)
      .then(utterances => {
        if (!utterances.length) {
          status = 'No recordings after seek point';
          historyPaused = true;
          return;
        }
        return playHistoryUtterances(utterances, channels, boundedTargetMillis, !skipSilence, {
          jumpRealtimeOnFinish: skipSilence
        });
      })
      .catch(error => status = error instanceof Error ? error.message : String(error));
  }

  function toggleTimelinePlayback() {
    if (historyPlaying) {
      pauseTimelinePlayback();
      return;
    }
    seekHistoryTo(playheadMillis || timelineFromMillis);
  }

  function pauseTimelinePlayback() {
    playheadMillis = currentTimelinePlayhead();
    clearHistoryPlaybackState(true);
    historyPaused = true;
    status = 'Historical playback paused';
  }

  function skipTimeline(deltaMillis: number) {
    const targetMillis = clamp(currentTimelinePlayhead() + deltaMillis, timelineFromMillis, timelineToMillis);
    playheadMillis = targetMillis;
    if (historyPlaying) {
      seekHistoryTo(targetMillis);
    }
  }

  function previewTimelinePlayhead(millis: number) {
    playheadMillis = clamp(millis, timelineFromMillis, timelineToMillis);
  }

  function handleTimelineSeek(targetMillis: number) {
    playheadMillis = targetMillis;
    if (historyPlaying) {
      seekHistoryTo(targetMillis);
    } else {
      historyPaused = true;
    }
  }

  function changePlaybackSpeed(speed: number) {
    const nextSpeed = clamp(speed, 0.5, 4);
    if (nextSpeed === playbackSpeed) return;
    const currentPlayhead = currentTimelinePlayhead();
    playbackSpeed = nextSpeed;
    if (!historyPlaying) return;

    playbackClockOriginMillis = currentPlayhead;
    playbackClockStartedAt = performance.now();
    const session = historySession;
    if (session) {
      session.playbackMode.speed = nextSpeed;
      session.playbackMode.originMillis = currentPlayhead;
      session.playbackMode.originAudioTime = audio.audioTime(0.05);
    }
  }

  function changePlaybackVolume(volume: number) {
    playbackVolume = clamp(volume, 0, 1);
    audio.setVolume(playbackVolume);
    persistPlaybackVolume(playbackVolume);
  }

  function changeTopPlaybackVolume(event: Event) {
    changePlaybackVolume(Number((event.currentTarget as HTMLInputElement).value));
  }

  function toggleWideView() {
    wideView = !wideView;
  }

  function changeSkipSilence(nextSkipSilence: boolean) {
    skipSilence = nextSkipSilence;
    if (historyPlaying) {
      seekHistoryTo(currentTimelinePlayhead());
    }
  }

  function changeShowHistoryList(nextShowHistoryList: boolean) {
    showHistoryList = nextShowHistoryList;
  }

  function leaveRealtimeNavigation() {
    playheadMillis = currentTimelinePlayhead();
  }

  function downloadUtterance(utterance: Utterance) {
    void downloadHistoryUtterance(utterance);
  }

  function stopHistory() {
    clearHistoryPlaybackState(true);
    historyPaused = false;
    status = 'Historical playback stopped';
  }

  function jumpToRealtime() {
    clearHistoryPlaybackState(true);
    resetHistoryWindow();
    startRealtimeHistory();
    status = 'Historical playback at real time';
  }

  function clearHistoryPlaybackState(flushAudio: boolean) {
    const keepPlayheadMillis = currentTimelinePlayhead();
    historySession?.abort.abort();
    historySession = null;
    if (flushAudio) {
      audio.flushPrefix('history:');
    }
    replayingLast = new Set();
    scheduledHistoryFrames = [];
    rowHighlighter.clear();
    historyPlaying = false;
    realtimePlayback = false;
    playbackClockOriginMillis = 0;
    playbackClockStartedAt = 0;
    if (flushAudio && keepPlayheadMillis > 0) {
      playheadMillis = keepPlayheadMillis;
    }
  }

  function removeHistoryChannelFromPlayback(channel: string) {
    const session = historySession;
    if (!session) return;

    const streamKey = historyStreamKey(session, channel);
    audio.flush(streamKey);
    session.activeStreams.delete(streamKey);
    session.channels = session.channels.filter(item => item !== channel);

    const removedKeys = new Set(session.utterances
      .filter(utterance => utterance.channel === channel)
      .map(utteranceKey));
    session.utterances = session.utterances.filter(utterance => utterance.channel !== channel);
    for (const key of removedKeys) {
      session.cursors.delete(key);
    }

    scheduledHistoryFrames = scheduledHistoryFrames.filter(frame => frame.channel !== channel);
    rowHighlighter.clear();
    refreshVisiblePlaybackHighlights();

    const next = new Set(replayingLast);
    next.delete(channel);
    replayingLast = next;

    if (session.activeStreams.size === 0 && !session.fetching) {
      void queueNextHistoryWindow(session);
    }
  }

  function handleStreamIdle(streamKey: string) {
    const session = historySession;
    if (isSessionStream(streamKey, session)) {
      session.activeStreams.delete(streamKey);
      if (session.activeStreams.size === 0) {
        void queueNextHistoryWindow(session);
      }
    }
  }

  function completePlayback(session: HistoryPlaybackSession) {
    if (session !== historySession) return false;
    const next = new Set(replayingLast);
    for (const channel of session.channels) next.delete(channel);
    replayingLast = next;
    scheduledHistoryFrames = [];
    rowHighlighter.clear();
    historyPlaying = realtimePlayback && !historyPaused;
    historyPaused = false;
    playbackClockOriginMillis = 0;
    playbackClockStartedAt = 0;
    historySession = null;
    if (session.jumpRealtimeOnFinish) {
      jumpToRealtime();
      return true;
    }
    status = 'Historical playback finished';
    return true;
  }

  async function playHistoryUtterances(utterances: Utterance[], channels: string[], originMillis: number, realtime: boolean,
                                      options: { jumpRealtimeOnFinish?: boolean } = {}) {
    const playbackId = nextPlaybackId++;
    const abort = new AbortController();
    const playbackMode = {
      realtime: true,
      originMillis,
      originAudioTime: audio.audioTime(0.15),
      speed: playbackSpeed
    } satisfies PlaybackMode;
    const compact = !realtime;
    historyPlaying = true;
    historyPaused = false;
    playbackClockOriginMillis = originMillis;
    playbackClockStartedAt = performance.now();
    playheadMillis = originMillis;
    const uniqueUtterances = [...new Map(utterances.map(utterance => [utteranceKey(utterance), utterance])).values()]
      .filter(utterance => utterance.endMillis >= originMillis || utterance.startMillis >= originMillis)
      .sort((a, b) => a.startMillis - b.startMillis || a.channel.localeCompare(b.channel));
    historySession = {
      playbackId,
      channels,
      utterances: uniqueUtterances,
      originMillis,
      compact,
      compactIntervals: compact ? buildCompactPlaybackIntervals(uniqueUtterances, originMillis) : [],
      playbackMode,
      jumpRealtimeOnFinish: options.jumpRealtimeOnFinish ?? false,
      abort,
      nextMillis: originMillis,
      cursors: new Map(),
      activeStreams: new Set(),
      fetching: false
    };
    status = `Fetching history near ${utcTime(originMillis)}...`;
    await queueNextHistoryWindow(historySession);
  }

  async function queueNextHistoryWindow(session: HistoryPlaybackSession | null) {
    if (!session || session !== historySession || session.abort.signal.aborted || session.fetching) {
      return;
    }
    session.fetching = true;
    try {
      while (session === historySession && !session.abort.signal.aborted) {
        const window = nextHistoryWindow(session);
        if (!window) {
          completePlayback(session);
          return;
        }

        const chunk = await fetchUtterancePacketWindow(window.requests, window.horizonMillis,
          session.originMillis, session.abort.signal);
        const activeChannels = new Set(session.channels);
        const requestsById = new Map(window.requests.map(request => [request.id, request]));
        const relevantUpdates = chunk.updates.filter(update => {
          const request = requestsById.get(update.id);
          return !!request && activeChannels.has(request.utterance.channel);
        });
        const advanced = relevantUpdates.some(update => {
          const request = requestsById.get(update.id);
          const previousOffset = session.cursors.get(update.id)?.nextOffset
            ?? request?.byteOffset
            ?? request?.utterance.startOffset
            ?? 0;
          return update.nextOffset > previousOffset;
        });
        for (const update of relevantUpdates) {
          session.cursors.set(update.id, {
            nextOffset: update.nextOffset,
            nextMillis: update.nextMillis,
            complete: update.complete
          });
        }
        session.nextMillis = nextHistoryWindowStart(session, window.horizonMillis);
        const playablePackets = chunk.packets.filter(packet => activeChannels.has(packet.channelName));
        if (!playablePackets.length) {
          if (advanced || chunk.packets.length || !relevantUpdates.length) {
            continue;
          }
          if (relevantUpdates.some(update => !update.complete)) {
            status = 'Waiting for recorded audio bytes...';
            return;
          }
          continue;
        }

        const streams = new Set(playablePackets.map(packet => packet.streamKey));
        for (const streamKey of streams) {
          session.activeStreams.add(streamKey);
        }
        for (const packet of playablePackets) {
          if (session.abort.signal.aborted) break;
          const playbackPacket = historyPlaybackPacket(session, packet);
          if (playbackPacket) {
            audio.enqueue(playbackPacket, session.playbackMode);
          }
        }
        status = `Queued ${playablePackets.length} historical packets`;
        return;
      }
    } catch (error) {
      if (!session.abort.signal.aborted && session === historySession) {
        status = error instanceof Error ? error.message : String(error);
      }
    } finally {
      session.fetching = false;
    }
  }

  function nextHistoryWindow(session: HistoryPlaybackSession) {
    return nextTimelineHistoryWindow(session);
  }

  function nextTimelineHistoryWindow(session: HistoryPlaybackSession) {
    let startMillis = session.nextMillis;
    let horizonMillis = startMillis + playbackFetchWindowMillis;
    let requests = historyWindowRequests(session, startMillis, horizonMillis);
    if (!requests.length) {
      const next = session.utterances.find(utterance =>
        utterance.startMillis > startMillis && !session.cursors.get(utteranceKey(utterance))?.complete);
      if (!next) return null;
      startMillis = next.startMillis;
      horizonMillis = startMillis + playbackFetchWindowMillis;
      requests = historyWindowRequests(session, startMillis, horizonMillis);
    }
    return requests.length ? { horizonMillis, requests } : null;
  }

  function historyWindowRequests(session: HistoryPlaybackSession, startMillis: number, horizonMillis: number) {
    const requests: HistoryPacketWindowRequest[] = [];
    for (const utterance of session.utterances) {
      if (utterance.startMillis > horizonMillis || utterance.endMillis < startMillis) continue;
      const id = utteranceKey(utterance);
      const cursor = session.cursors.get(id);
      if (cursor?.complete || !utterance.opusUrl || utterance.startOffset === undefined) continue;
      requests.push({
        id,
        utterance,
        streamKey: historyStreamKey(session, utterance.channel),
        byteOffset: cursor?.nextOffset,
        packetMillis: cursor?.nextMillis
      });
    }
    return requests;
  }

  function historyPlaybackPacket(session: HistoryPlaybackSession, packet: OpusPacket) {
    if (!session.compact) return packet;
    const playbackMillis = compactPlaybackMillis(session, packet.unixMillis);
    return playbackMillis === null ? null : { ...packet, playbackMillis };
  }

  function buildCompactPlaybackIntervals(utterances: Utterance[], originMillis: number) {
    const intervals: { startMillis: number; endMillis: number }[] = [];
    for (const utterance of utterances) {
      const startMillis = Math.max(originMillis, utterance.startMillis);
      const endMillis = utteranceEffectiveEndMillis(utterance);
      if (endMillis <= startMillis) continue;
      const previous = intervals[intervals.length - 1];
      if (previous && startMillis <= previous.endMillis) {
        previous.endMillis = Math.max(previous.endMillis, endMillis);
      } else {
        intervals.push({ startMillis, endMillis });
      }
    }

    let playbackStartMillis = originMillis;
    return intervals.map(interval => {
      const compactInterval = { ...interval, playbackStartMillis };
      playbackStartMillis += interval.endMillis - interval.startMillis;
      return compactInterval;
    });
  }

  function compactPlaybackMillis(session: HistoryPlaybackSession, packetMillis: number) {
    for (const interval of session.compactIntervals) {
      if (packetMillis < interval.startMillis) {
        return interval.playbackStartMillis;
      }
      if (packetMillis <= interval.endMillis) {
        return interval.playbackStartMillis + packetMillis - interval.startMillis;
      }
    }
    return null;
  }

  function originalMillisForCompactPlayback(session: HistoryPlaybackSession, playbackMillis: number) {
    for (const interval of session.compactIntervals) {
      const playbackEndMillis = interval.playbackStartMillis + interval.endMillis - interval.startMillis;
      if (playbackMillis < interval.playbackStartMillis) return interval.startMillis;
      if (playbackMillis <= playbackEndMillis) {
        return interval.startMillis + playbackMillis - interval.playbackStartMillis;
      }
    }
    const last = session.compactIntervals[session.compactIntervals.length - 1];
    return last?.endMillis ?? playbackMillis;
  }

  function utteranceEffectiveEndMillis(utterance: Utterance) {
    const durationEndMillis = utterance.durationMillis && utterance.durationMillis > 0
      ? utterance.startMillis + utterance.durationMillis
      : utterance.endMillis;
    return Math.max(utterance.startMillis, durationEndMillis);
  }

  function historyStreamKey(session: HistoryPlaybackSession, channel: string) {
    return `history:${session.playbackId}:${channel}`;
  }

  function nextHistoryWindowStart(session: HistoryPlaybackSession, horizonMillis: number) {
    const incompleteMillis = [...session.cursors.values()]
      .filter(cursor => !cursor.complete && cursor.nextMillis < horizonMillis)
      .map(cursor => cursor.nextMillis);
    return incompleteMillis.length ? Math.min(...incompleteMillis) : horizonMillis;
  }

  function isSessionStream(streamKey: string, session: HistoryPlaybackSession | null): session is HistoryPlaybackSession {
    return !!session && streamKey.startsWith(`history:${session.playbackId}:`);
  }

  function channelFromHistoryStreamKey(streamKey: string) {
    return streamKey.replace(/^history:\d+:/, '');
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

  function handleFrameScheduled(streamKey: string, targetMillis: number, startTime: number,
                                durationSeconds: number, channelName: string) {
    if (!streamKey.startsWith('history:')) return;
    const channel = channelName || channelFromHistoryStreamKey(streamKey);
    const delayMillis = Math.max(0, (startTime - audio.currentTime()) * 1000);
    const startAtMillis = performance.now() + delayMillis;
    const endAtMillis = startAtMillis + durationSeconds * 1000 + 150;
    scheduledHistoryFrames = [
      ...scheduledHistoryFrames.filter(frame => frame.endAtMillis > performance.now()),
      {
        channel,
        targetMillis,
        startAtMillis,
        endAtMillis
      }
    ];
    const utterance = recentHistory.find(item =>
      item.channel === channel
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

  function updateTimelinePlaybackClock() {
    if (!historyPlaying) return;
    playheadMillis = currentTimelinePlayhead();
  }

  function currentTimelinePlayhead() {
    const nowMillis = performance.now();
    const activeFrame = scheduledHistoryFrames
      .filter(frame => frame.startAtMillis <= nowMillis && frame.endAtMillis >= nowMillis)
      .sort((a, b) => b.startAtMillis - a.startAtMillis)[0];
    if (activeFrame) {
      return activeFrame.targetMillis;
    }
    if (historyPlaying && playbackClockOriginMillis > 0 && playbackClockStartedAt > 0) {
      const playbackMillis = playbackClockOriginMillis + (nowMillis - playbackClockStartedAt) * playbackSpeed;
      return historySession?.compact ? originalMillisForCompactPlayback(historySession, playbackMillis) : playbackMillis;
    }
    if (realtimePlayback && historyPlaying && !historyPaused) {
      return Date.now();
    }
    return playheadMillis || timelineToMillis || Date.now();
  }

  function appendLiveUtterance(utterance: Utterance) {
    rememberHistoryRecordingFile(utterance);
    if (selected.size === 0 || !selected.has(utterance.channel)) {
      return;
    }
    if (timelineFollowRealtime && timelineToMillis > timelineFromMillis) {
      const latestTo = Math.max(Date.now(), utterance.endMillis, utterance.startMillis);
      const span = timelineSpanMillis;
      timelineToMillis = latestTo;
      timelineFromMillis = Math.max(0, latestTo - span);
      syncHistoryRangeControls((timelineFromMillis + timelineToMillis) / 2);
    }
    if (utterance.endMillis < timelineFromMillis || utterance.startMillis > timelineToMillis) {
      return;
    }
    const key = utteranceKey(utterance);
    recentHistory = [utterance, ...recentHistory.filter(item => utteranceKey(item) !== key)]
      .sort((a, b) => b.startMillis - a.startMillis)
      .slice(0, historyPageSize * 50);
    historyHasOlder = true;
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

  function utcDateValue(millis: number) {
    if (!Number.isFinite(millis)) return '';
    return new Date(millis).toISOString().slice(0, 10);
  }

  function utcDayStartMillis(value: string) {
    if (!value) return 0;
    const [year, month, day] = value.split('-').map(Number);
    if (!year || !month || !day) return 0;
    return Date.UTC(year, month - 1, day);
  }

  function hourSlotLabel(slot: number) {
    const totalMinutes = clamp(Math.round(slot), 0, 47) * 30;
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    return `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}Z`;
  }

  function syncHistoryRangeControls(centerMillis: number) {
    if (!Number.isFinite(centerMillis)) return;
    historyDateValue = utcDateValue(centerMillis);
    const dayStart = utcDayStartMillis(historyDateValue);
    historyHourSlot = clamp(Math.round((centerMillis - dayStart) / halfHourMillis), 0, 47);
  }

  function centerHistoryWindowFromControls(loadDelayMillis = 0) {
    if (!historyDateValue) {
      historyDateValue = utcDateValue(Date.now());
    }
    const centerMillis = utcDayStartMillis(historyDateValue) + historyHourSlot * halfHourMillis;
    setHistoryWindow(centerMillis - halfHourMillis, centerMillis + halfHourMillis, loadDelayMillis);
  }

  function changeHistoryDate() {
    centerHistoryWindowFromControls();
  }

  function changeHistoryHour(event: Event) {
    historyHourSlot = Number((event.currentTarget as HTMLInputElement).value);
    centerHistoryWindowFromControls(250);
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

  function historyChannelColor(channel: string) {
    return selected.size > 1 && selected.has(channel) ? channelAccentColor(channel) : null;
  }

  function historyRangeLabel() {
    if (!timelineFromMillis || !timelineToMillis) return 'No recordings';
    const fromDate = utcDate(timelineFromMillis);
    const toDate = utcDate(timelineToMillis);
    const count = recentHistory.length === 1 ? '1 recording' : `${recentHistory.length} recordings`;
    if (fromDate === toDate) {
      return `${fromDate} ${utcTime(timelineFromMillis)} - ${utcTime(timelineToMillis)} · ${count}`;
    }
    return `${fromDate} ${utcTime(timelineFromMillis)} - ${toDate} ${utcTime(timelineToMillis)} · ${count}`;
  }

  function clamp(value: number, min: number, max: number) {
    return Math.min(max, Math.max(min, value));
  }

  function sameSet(left: Set<string>, right: Set<string>) {
    if (left.size !== right.size) return false;
    for (const item of left) {
      if (!right.has(item)) return false;
    }
    return true;
  }

  function storedPlaybackVolume(fallback: number) {
    try {
      const rawVolume = localStorage.getItem(playbackVolumeStorageKey);
      if (rawVolume === null) return fallback;
      const volume = Number(rawVolume);
      return Number.isFinite(volume) ? clamp(volume, 0, 1) : fallback;
    } catch {
      return fallback;
    }
  }

  function persistPlaybackVolume(volume: number) {
    try {
      localStorage.setItem(playbackVolumeStorageKey, String(volume));
    } catch {
      // Storage can be unavailable in private or locked-down browser contexts.
    }
  }
</script>

<main class:wide-view={wideView}>
  <section class="recent-section">
    <div class="section-heading">
      <div class="section-heading-main">
        <h2>Recent activity</h2>
        <label class="top-volume-control">
          <span>Vol.: {Math.round(playbackVolume * 100)} %</span>
          <input value={playbackVolume} type="range" min="0" max="1" step="0.01"
            aria-label="Playback volume" oninput={changeTopPlaybackVolume} />
        </label>
        <button type="button" class:active={wideView} class="view-toggle"
          aria-pressed={wideView}
          title={wideView ? 'Switch to narrow view' : 'Switch to wide view'}
          onclick={toggleWideView}>
          Wide
        </button>
      </div>
      <div class="status">{status}</div>
    </div>
    <div class="channel-grid">
      {#each recentChannels as channel (channel.id)}
        <ChannelTile name={channel.name} active={channel.active} lastActiveLabel={lastActiveText(channel)}
          selected={selected.has(channel.name)} historyColor={historyChannelColor(channel.name)}
          live={live.has(channel.name)}
          replaying={replayingLast.has(channel.name)}
          onHistory={showChannelHistory} onAddFilter={addChannelHistoryFilter} onRemoveFilter={removeSelected}
          onLive={toggleLive} onReplayLast={toggleLastUtterance} />
      {/each}
    </div>
  </section>

  <details bind:open={channelsOpen}>
    <summary>Channels</summary>
    {#if channelsOpen}
      <div class="channels-controls">
        <input bind:value={filter} type="search" placeholder="Filter channels" />
        <button type="button" onclick={() => send(socket, { type: 'list_channels' })}>Refresh</button>
      </div>
      <div class="channel-grid">
        {#each filteredChannels as channel (channel.id)}
          <ChannelTile name={channel.name} active={channel.active} lastActiveLabel={lastActiveText(channel)}
            selected={selected.has(channel.name)} historyColor={historyChannelColor(channel.name)}
            live={live.has(channel.name)}
            replaying={replayingLast.has(channel.name)}
            onHistory={showChannelHistory} onAddFilter={addChannelHistoryFilter} onRemoveFilter={removeSelected}
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
          <div class="filter-chip">
            <button type="button" class="filter-chip-remove" aria-label={`Remove ${name} from history filter`}
              onclick={() => removeSelected(name)}>
              <span aria-hidden="true">×</span>
            </button>
            <span class="filter-chip-name" style:color={historyChannelColor(name)}>{name}</span>
            <div class="route-buttons" aria-label={`Audio route for ${name}`}>
              {#each routeOptions as option}
                <button type="button" class:active={channelAudioRoute(name) === option.route}
                  aria-label={`${option.description} for ${name}`}
                  onclick={() => setChannelAudioRoute(name, option.route)}>
                  {option.label}
                </button>
              {/each}
            </div>
          </div>
        {/each}
      {/if}
    </div>

    <div class="history-controls">
      <label class="date-control">
        <input bind:value={historyDateValue} type="date" aria-label="Timeline date in Zulu"
          onchange={changeHistoryDate} />
      </label>
      <label class="hour-control">
        <span>{hourSlotLabel(historyHourSlot)}</span>
        <input value={historyHourSlot} type="range" min="0" max="47" step="1"
          aria-label="Timeline center time in Zulu" oninput={changeHistoryHour} />
      </label>
    </div>

    <HistoryTimeline utterances={recentHistory} fromMillis={timelineFromMillis} toMillis={timelineToMillis}
      {playheadMillis} selected={selected.size > 0} outlineBlocks={selected.size > 1}
      playing={historyPlaying}
      {playbackSpeed} {skipSilence} showList={showHistoryList}
      skipMillis={historySkipMillis} cacheBytes={rangeCacheBytes}
      activeChannels={activeTimelineChannels} nowMillis={now} realtime={realtimePlayback && historyPlaying && !historyPaused}
      onNavigate={leaveRealtimeNavigation} onWindowChange={setHistoryWindow} onPreviewPlayhead={previewTimelinePlayhead}
      onSeek={handleTimelineSeek} onTogglePlayback={toggleTimelinePlayback}
      onSkip={skipTimeline} onStop={jumpToRealtime}
      onSpeedChange={changePlaybackSpeed}
      onSkipSilenceChange={changeSkipSilence} onShowListChange={changeShowHistoryList} />

    {#if showHistoryList}
      <div class="history-list">
        {#each recentHistory as utterance, index (utteranceKey(utterance))}
          {#if showDateSeparator(index)}
            <div class="history-date">{utcDate(utterance.startMillis)}</div>
          {/if}
          <div class:playing-row={playingRows.has(utteranceKey(utterance))} class="utterance">
            <span class="utterance-channel" role="link" tabindex="0"
              style:color={historyChannelColor(utterance.channel)}
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
    {/if}

    <div class="pager">
      <button type="button" disabled={selected.size === 0 || timelineToMillis >= now - 1000} onclick={loadNewerHistory}>Newer</button>
      <span>{historyRangeLabel()}</span>
      <button type="button" disabled={!historyHasOlder || selected.size === 0} onclick={loadOlderHistory}>Older</button>
    </div>
  </section>
</main>
