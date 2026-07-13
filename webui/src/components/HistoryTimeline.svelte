<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { channelAccentColor } from '../lib/channelColors';
  import { utteranceKey } from '../lib/rowPlayback';
  import type { Utterance } from '../lib/types';

  const minTimelineWindowMillis = 60 * 1000;
  const maxTimelineWindowMillis = 31 * 24 * 60 * 60 * 1000;
  const waveformWindowMillis = 24 * 60 * 60 * 1000;
  const timelineZoomStep = 1.6;
  const minTimelineSnrDb = 35;
  const maxTimelineSnrDb = 50;
  const playheadHitPixels = 10;

  type TimelineDragMode = 'seek' | 'pan';
  type ActiveTimelineChannel = {
    name: string;
    startMillis: number;
  };

  let {
    utterances,
    fromMillis,
    toMillis,
    playheadMillis,
    loading,
    selected,
    playing,
    playbackSpeed,
    skipSilence,
    skipMillis,
    cacheBytes,
    activeChannels,
    nowMillis,
    realtime,
    onNavigate,
    onWindowChange,
    onPreviewPlayhead,
    onSeek,
    onTogglePlayback,
    onSkip,
    onStop,
    onSpeedChange,
    onSkipSilenceChange
  }: {
    utterances: Utterance[];
    fromMillis: number;
    toMillis: number;
    playheadMillis: number;
    loading: boolean;
    selected: boolean;
    playing: boolean;
    playbackSpeed: number;
    skipSilence: boolean;
    skipMillis: number;
    cacheBytes: number;
    activeChannels: ActiveTimelineChannel[];
    nowMillis: number;
    realtime: boolean;
    onNavigate: () => void;
    onWindowChange: (fromMillis: number, toMillis: number, loadDelayMillis?: number) => void;
    onPreviewPlayhead: (millis: number) => void;
    onSeek: (millis: number) => void;
    onTogglePlayback: () => void;
    onSkip: (deltaMillis: number) => void;
    onStop: () => void;
    onSpeedChange: (speed: number) => void;
    onSkipSilenceChange: (skipSilence: boolean) => void;
  } = $props();

  let canvas = $state<HTMLCanvasElement | null>(null);
  let dragMode = $state<TimelineDragMode | null>(null);
  let dragStartX = $state(0);
  let dragStartFromMillis = $state(0);
  let dragStartToMillis = $state(0);
  let dragMoved = $state(false);
  let viewFromMillis = $state(0);
  let viewToMillis = $state(0);
  let drawFrame = 0;

  $effect(() => {
    if (dragMode) return;
    viewFromMillis = fromMillis;
    viewToMillis = toMillis;
  });

  let effectiveFromMillis = $derived(viewFromMillis || fromMillis);
  let effectiveToMillis = $derived(viewToMillis || toMillis);
  let spanMillis = $derived(Math.max(minTimelineWindowMillis, effectiveToMillis - effectiveFromMillis));
  let ticks = $derived(buildTimelineTicks(effectiveFromMillis, effectiveToMillis));
  let blocks = $derived(buildTimelineBlocks(utterances, activeChannels, effectiveFromMillis, effectiveToMillis, nowMillis));
  let playheadPercent = $derived(timelinePositionPercent(playheadMillis, effectiveFromMillis, effectiveToMillis));
  let effectiveRealtime = $derived(realtime);

  $effect(() => {
    scheduleDraw(canvas, ticks, blocks, playheadMillis, effectiveFromMillis, effectiveToMillis, effectiveRealtime, nowMillis);
  });

  onMount(() => {
    const redraw = () => scheduleDraw(canvas, ticks, blocks, playheadMillis,
      effectiveFromMillis, effectiveToMillis, effectiveRealtime, nowMillis);
    window.addEventListener('resize', redraw);
    return () => window.removeEventListener('resize', redraw);
  });

  onDestroy(() => {
    if (drawFrame) cancelAnimationFrame(drawFrame);
  });

  function zoomTimeline(event: WheelEvent) {
    if (!selected || toMillis <= fromMillis) return;
    event.preventDefault();
    const element = event.currentTarget as HTMLElement;
    const rect = element.getBoundingClientRect();
    const ratio = clamp((event.clientX - rect.left) / Math.max(1, rect.width), 0, 1);
    zoomTimelineAt(ratio, event.deltaY > 0 ? timelineZoomStep : 1 / timelineZoomStep, 450);
  }

  function zoomTimelineButton(direction: 'in' | 'out') {
    if (!selected || toMillis <= fromMillis) return;
    zoomTimelineAt(0.5, direction === 'out' ? timelineZoomStep : 1 / timelineZoomStep, 250);
  }

  function zoomTimelineAt(ratio: number, zoomFactor: number, loadDelayMillis = 0) {
    const anchor = effectiveFromMillis + spanMillis * ratio;
    const nextSpan = clamp(spanMillis * zoomFactor, minTimelineWindowMillis, maxTimelineWindowMillis);
    let nextFrom = anchor - nextSpan * ratio;
    let nextTo = nextFrom + nextSpan;
    [nextFrom, nextTo] = clampWindow(nextFrom, nextTo);
    viewFromMillis = nextFrom;
    viewToMillis = nextTo;
    onNavigate();
    onWindowChange(nextFrom, nextTo, loadDelayMillis);
  }

  function beginTimelineDrag(event: PointerEvent) {
    if (!selected || toMillis <= fromMillis || event.button !== 0) return;
    const element = event.currentTarget as HTMLCanvasElement;
    const rect = element.getBoundingClientRect();
    const cursorX = event.clientX - rect.left;
    const playheadX = playheadPercent === null ? Number.NaN : (playheadPercent / 100) * rect.width;
    element.setPointerCapture(event.pointerId);
    dragMode = Math.abs(cursorX - playheadX) <= playheadHitPixels ? 'seek' : 'pan';
    dragStartX = event.clientX;
    dragStartFromMillis = effectiveFromMillis;
    dragStartToMillis = effectiveToMillis;
    dragMoved = false;
    viewFromMillis = effectiveFromMillis;
    viewToMillis = effectiveToMillis;
    if (dragMode === 'pan') {
      onNavigate();
    }
    if (dragMode === 'seek') {
      onPreviewPlayhead(millisFromTimelineEvent(event, element));
    }
  }

  function moveTimelineDrag(event: PointerEvent) {
    if (!dragMode) return;
    const element = event.currentTarget as HTMLCanvasElement;
    dragMoved ||= Math.abs(event.clientX - dragStartX) > 3;
    if (dragMode === 'seek') {
      onPreviewPlayhead(millisFromTimelineEvent(event, element));
      return;
    }
    const rect = element.getBoundingClientRect();
    const deltaRatio = (event.clientX - dragStartX) / Math.max(1, rect.width);
    const deltaMillis = -deltaRatio * (dragStartToMillis - dragStartFromMillis);
    setLocalTimelineWindow(dragStartFromMillis + deltaMillis, dragStartToMillis + deltaMillis);
    onWindowChange(viewFromMillis, viewToMillis, -1);
  }

  function endTimelineDrag(event: PointerEvent) {
    if (!dragMode) return;
    const element = event.currentTarget as HTMLCanvasElement;
    if (element.hasPointerCapture(event.pointerId)) {
      element.releasePointerCapture(event.pointerId);
    }
    const mode = dragMode;
    dragMode = null;
    if (mode === 'seek') {
      onSeek(millisFromTimelineEvent(event, element));
    } else if (!dragMoved) {
      onSeek(millisFromTimelineEvent(event, element));
    } else {
      onWindowChange(viewFromMillis, viewToMillis, 350);
    }
  }

  function cancelTimelineDrag(event: PointerEvent) {
    const element = event.currentTarget as HTMLCanvasElement;
    if (element.hasPointerCapture(event.pointerId)) {
      element.releasePointerCapture(event.pointerId);
    }
    dragMode = null;
  }

  function setLocalTimelineWindow(nextFrom: number, nextTo: number) {
    [viewFromMillis, viewToMillis] = clampWindow(nextFrom, nextTo);
  }

  function clampWindow(nextFrom: number, nextTo: number): [number, number] {
    const span = nextTo - nextFrom;
    const latestTo = Date.now();
    if (nextFrom < 0) {
      nextFrom = 0;
      nextTo = span;
    }
    if (nextTo > latestTo) {
      nextTo = latestTo;
      nextFrom = latestTo - span;
    }
    return [nextFrom, nextTo];
  }

  function changeSpeed(event: Event) {
    onSpeedChange(Number((event.currentTarget as HTMLInputElement).value));
  }

  function resetSpeed() {
    onSpeedChange(1);
  }

  function changeSkipSilence(event: Event) {
    onSkipSilenceChange((event.currentTarget as HTMLInputElement).checked);
  }

  function millisFromTimelineEvent(event: PointerEvent, element: HTMLElement) {
    const rect = element.getBoundingClientRect();
    const ratio = clamp((event.clientX - rect.left) / Math.max(1, rect.width), 0, 1);
    return Math.round(effectiveFromMillis + spanMillis * ratio);
  }

  function historyRangeLabel() {
    if (!effectiveFromMillis || !effectiveToMillis) return 'No recordings';
    const fromDate = utcDate(effectiveFromMillis);
    const toDate = utcDate(effectiveToMillis);
    const count = utterances.length === 1 ? '1 recording' : `${utterances.length} recordings`;
    if (fromDate === toDate) {
      return `${fromDate} ${utcTime(effectiveFromMillis)} - ${utcTime(effectiveToMillis)} · ${count}`;
    }
    return `${fromDate} ${utcTime(effectiveFromMillis)} - ${toDate} ${utcTime(effectiveToMillis)} · ${count}`;
  }

  function timelineStatusLabel() {
    if (loading) return 'Loading...';
    return spanMillis <= waveformWindowMillis ? 'Waveform by utterance SNR' : 'Zoom in to show utterance blocks';
  }

  function cacheLabel() {
    return `Cache ${formatBytes(cacheBytes)}`;
  }

  function formatBytes(bytes: number) {
    if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
    const units = ['B', 'KiB', 'MiB', 'GiB'];
    let value = bytes;
    let unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
      value /= 1024;
      unitIndex += 1;
    }
    const digits = value >= 10 || unitIndex === 0 ? 0 : 1;
    return `${value.toFixed(digits)} ${units[unitIndex]}`;
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

  function duration(utterance: Utterance) {
    const millis = utterance.durationMillis && utterance.durationMillis > 0
      ? utterance.durationMillis
      : utterance.endMillis - utterance.startMillis;
    return Number.isFinite(millis) ? Math.max(0, millis / 1000).toFixed(1) : '?';
  }

  function snr(utterance: Utterance) {
    return Number.isFinite(utterance.averageSnrDb) ? `${utterance.averageSnrDb!.toFixed(1)} dB` : '-';
  }

  function timelinePositionPercent(millis: number, startMillis: number, endMillis: number) {
    if (!millis || endMillis <= startMillis || millis < startMillis || millis > endMillis) return null;
    return ((millis - startMillis) / (endMillis - startMillis)) * 100;
  }

  function buildTimelineBlocks(items: Utterance[], activeItems: ActiveTimelineChannel[],
                               startMillis: number, endMillis: number, currentMillis: number) {
    const span = endMillis - startMillis;
    if (span <= 0 || span > waveformWindowMillis) return [];
    const storedBlocks = items
      .filter(utterance => utterance.endMillis >= startMillis && utterance.startMillis <= endMillis)
      .map(utterance => {
        const start = clamp(utterance.startMillis, startMillis, endMillis);
        const end = clamp(Math.max(utterance.endMillis, utterance.startMillis + 100), startMillis, endMillis);
        const snrDb = Number.isFinite(utterance.averageSnrDb) ? utterance.averageSnrDb! : minTimelineSnrDb;
        const snrRatio = (clamp(snrDb, minTimelineSnrDb, maxTimelineSnrDb) - minTimelineSnrDb)
          / (maxTimelineSnrDb - minTimelineSnrDb);
        const height = 18 + snrRatio * 70;
        return {
          key: utteranceKey(utterance),
          left: ((start - startMillis) / span) * 100,
          width: Math.max(0.18, ((end - start) / span) * 100),
          top: 50 - height / 2,
          height,
          color: channelAccentColor(utterance.channel),
          label: `${utterance.channel} ${utcTime(utterance.startMillis)} ${duration(utterance)}s ${snr(utterance)}`
        };
      });
    const liveBlocks = activeItems
      .filter(channel => channel.startMillis <= endMillis && currentMillis >= startMillis)
      .map(channel => {
        const start = clamp(channel.startMillis, startMillis, endMillis);
        const end = clamp(currentMillis, startMillis, endMillis);
        return {
          key: `live:${channel.name}`,
          left: ((start - startMillis) / span) * 100,
          width: Math.max(0.18, ((Math.max(end, start + 100) - start) / span) * 100),
          top: 22,
          height: 56,
          color: channelAccentColor(channel.name),
          label: `${channel.name} active`
        };
      });
    return [...storedBlocks, ...liveBlocks];
  }

  function buildTimelineTicks(startMillis: number, endMillis: number) {
    const span = endMillis - startMillis;
    if (span <= 0) return [];
    const hour = 60 * 60 * 1000;
    const day = 24 * hour;
    const interval = span <= 2 * hour ? 15 * 60 * 1000
      : span <= 12 * hour ? hour
      : span <= 2 * day ? 6 * hour
      : span <= 14 * day ? day
      : 7 * day;
    const builtTicks: { millis: number; left: number; label: string; major: boolean }[] = [];
    const first = Math.ceil(startMillis / interval) * interval;
    for (let millis = first; millis <= endMillis && builtTicks.length < 240; millis += interval) {
      const date = new Date(millis);
      const major = interval >= day || (date.getUTCHours() === 0 && date.getUTCMinutes() === 0);
      builtTicks.push({
        millis,
        left: ((millis - startMillis) / span) * 100,
        label: major ? date.toISOString().slice(5, 10) : `${date.toISOString().slice(11, 16)}Z`,
        major
      });
    }
    return builtTicks;
  }

  function scheduleDraw(_canvas: HTMLCanvasElement | null, _ticks: typeof ticks, _blocks: typeof blocks,
                        _playheadMillis: number, _fromMillis: number, _toMillis: number,
                        _realtime: boolean, _nowMillis: number) {
    if (!canvas) return;
    if (drawFrame) cancelAnimationFrame(drawFrame);
    drawFrame = requestAnimationFrame(() => {
      drawFrame = 0;
      drawTimelineCanvas();
    });
  }

  function drawTimelineCanvas() {
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const width = Math.max(1, Math.round(rect.width));
    const height = Math.max(1, Math.round(rect.height));
    const scale = window.devicePixelRatio || 1;
    const pixelWidth = Math.round(width * scale);
    const pixelHeight = Math.round(height * scale);
    if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
      canvas.width = pixelWidth;
      canvas.height = pixelHeight;
    }
    const context = canvas.getContext('2d');
    if (!context) return;
    context.setTransform(scale, 0, 0, scale, 0, 0);
    context.clearRect(0, 0, width, height);

    context.fillStyle = '#10151c';
    context.beginPath();
    context.roundRect(0, 0, width, height, 8);
    context.fill();

    context.strokeStyle = 'rgb(255 255 255 / 0.03)';
    context.lineWidth = 1;
    for (let x = 0; x <= width; x += width / 10) {
      context.beginPath();
      context.moveTo(x, 0);
      context.lineTo(x, height);
      context.stroke();
    }

    const gradient = context.createLinearGradient(0, 0, 0, height * 0.42);
    gradient.addColorStop(0, 'rgb(255 255 255 / 0.03)');
    gradient.addColorStop(1, 'rgb(255 255 255 / 0)');
    context.fillStyle = gradient;
    context.fillRect(0, 0, width, height * 0.42);

    context.strokeStyle = '#26313d';
    context.beginPath();
    context.moveTo(0, height / 2);
    context.lineTo(width, height / 2);
    context.stroke();

    context.font = '11px system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
    context.textBaseline = 'top';
    for (const tick of ticks) {
      const x = (tick.left / 100) * width;
      context.strokeStyle = tick.major ? '#526579' : '#33404d';
      context.lineWidth = tick.major ? 2 : 1;
      context.beginPath();
      context.moveTo(x, 0);
      context.lineTo(x, height);
      context.stroke();
      context.fillStyle = tick.major ? '#c3cfda' : '#8493a2';
      context.fillText(tick.label, Math.min(width - 80, x + 6), 8, 78);
    }

    for (const block of blocks) {
      const x = (block.left / 100) * width;
      const blockWidth = Math.max(2, (block.width / 100) * width);
      const y = (block.top / 100) * height;
      const blockHeight = (block.height / 100) * height;
      const blockGradient = context.createLinearGradient(0, y, 0, y + blockHeight);
      blockGradient.addColorStop(0, mixCanvasColor(block.color, '#ffffff', 0.18));
      blockGradient.addColorStop(1, mixCanvasColor(block.color, '#000000', 0.28));
      context.fillStyle = blockGradient;
      context.strokeStyle = mixCanvasColor(block.color, '#ffffff', 0.22);
      context.lineWidth = 1;
      context.beginPath();
      context.roundRect(x, y, blockWidth, blockHeight, 2);
      context.fill();
      context.stroke();
    }

    if (playheadPercent !== null) {
      const playheadX = (playheadPercent / 100) * width;
      context.strokeStyle = '#7cf0a8';
      context.lineWidth = 2;
      context.shadowColor = 'rgb(124 240 168 / 0.36)';
      context.shadowBlur = 14;
      context.beginPath();
      context.moveTo(playheadX, 0);
      context.lineTo(playheadX, height);
      context.stroke();
      context.shadowBlur = 0;
      context.fillStyle = '#7cf0a8';
      context.beginPath();
      context.roundRect(playheadX - 6, 0, 12, 12, 3);
      context.fill();
    }

    if (realtime) {
      context.font = '700 11px system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
      context.textBaseline = 'top';
      const label = 'Real-time';
      const textWidth = context.measureText(label).width;
      const x = width - textWidth - 18;
      context.fillStyle = 'rgb(16 21 28 / 0.78)';
      context.beginPath();
      context.roundRect(x - 8, 8, textWidth + 16, 22, 4);
      context.fill();
      context.fillStyle = '#7cf0a8';
      context.fillText(label, x, 13);
    }
  }

  function mixCanvasColor(color: string, other: string, amount: number) {
    const match = /^hsl\(([-\d.]+) ([\d.]+)% ([\d.]+)%\)$/.exec(color);
    if (!match) return color;
    const hue = Number(match[1]);
    const saturation = Number(match[2]);
    const lightness = Number(match[3]);
    const targetLightness = other === '#ffffff' ? 100 : 0;
    return `hsl(${hue} ${saturation}% ${lightness + (targetLightness - lightness) * amount}%)`;
  }

  function clamp(value: number, min: number, max: number) {
    return Math.min(max, Math.max(min, value));
  }
</script>

<div class="timeline-shell">
  <div class="timeline-topline">
    <span>{historyRangeLabel()}</span>
    <span class="timeline-topline-right">
      <span>{timelineStatusLabel()}</span>
      <span>{cacheLabel()}</span>
    </span>
  </div>
  <div class="timeline-controls">
    <button type="button" class="icon-button" disabled={!selected}
      aria-label={playing ? 'Pause historical playback' : 'Play historical playback'}
      onclick={onTogglePlayback}>
      <span class="icon-symbol">{playing ? 'Ⅱ' : '▶'}</span>
    </button>
    <button type="button" class="icon-button" disabled={!selected}
      aria-label="Skip back 5 seconds" onclick={() => onSkip(-skipMillis)}>
      <span class="icon-symbol">↶</span>
    </button>
    <button type="button" class="icon-button" disabled={!selected}
      aria-label="Skip forward 5 seconds" onclick={() => onSkip(skipMillis)}>
      <span class="icon-symbol">↷</span>
    </button>
    <button type="button" class="icon-button" disabled={!selected}
      aria-label="Jump to real time" onclick={onStop}>
      <span class="icon-symbol">■</span>
    </button>
    <button type="button" class="icon-button" disabled={!selected}
      aria-label="Zoom out" onclick={() => zoomTimelineButton('out')}>
      <span class="icon-symbol">−</span>
    </button>
    <button type="button" class="icon-button" disabled={!selected}
      aria-label="Zoom in" onclick={() => zoomTimelineButton('in')}>
      <span class="icon-symbol">+</span>
    </button>
    <label class="speed-control">
      <button type="button" class="speed-label" aria-label="Reset playback speed to 1.0x"
        onclick={resetSpeed}>
        {playbackSpeed.toFixed(1)}×
      </button>
      <input value={playbackSpeed} type="range" min="0.5" max="4" step="0.1"
        oninput={changeSpeed} />
    </label>
    <label class="checkbox skip-silence">
      <input checked={skipSilence} type="checkbox" onchange={changeSkipSilence} />
      Skip silence
    </label>
  </div>
  <canvas class="history-timeline" role="slider" aria-label="Historical playback seek bar"
    bind:this={canvas}
    aria-valuemin={effectiveFromMillis} aria-valuemax={effectiveToMillis}
    aria-valuenow={clamp(playheadMillis || effectiveFromMillis, effectiveFromMillis, effectiveToMillis)}
    tabindex="0"
    onwheel={zoomTimeline}
    onpointerdown={beginTimelineDrag}
    onpointermove={moveTimelineDrag}
    onpointerup={endTimelineDrag}
    onpointercancel={cancelTimelineDrag}>
  </canvas>
</div>

<style>
  .timeline-shell {
    display: grid;
    gap: 8px;
    margin-top: 12px;
  }

  .timeline-topline {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    color: #9aa7b4;
    font-size: 12px;
  }

  .timeline-topline > span:first-child {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .timeline-topline-right {
    display: inline-flex;
    gap: 12px;
    justify-content: flex-end;
    text-align: right;
    white-space: nowrap;
  }

  .timeline-controls {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }

  .speed-control {
    display: inline-grid;
    grid-template-columns: 42px minmax(130px, 190px);
    align-items: center;
    gap: 8px;
    min-height: 34px;
    margin-left: 4px;
    color: #cbd5df;
    font-size: 13px;
  }

  .speed-label {
    border: 0;
    padding: 0;
    background: none;
    color: #9ee6b5;
    cursor: pointer;
    font-weight: 700;
    text-align: left;
  }

  .speed-control input {
    width: 100%;
    accent-color: #7cf0a8;
  }

  .history-timeline {
    display: block;
    width: 100%;
    height: 116px;
    border: 1px solid #2b3440;
    border-radius: 8px;
    background: #10151c;
    cursor: grab;
    touch-action: none;
    user-select: none;
  }

  .history-timeline:active {
    cursor: grabbing;
  }

  .history-timeline:focus-visible {
    outline: 2px solid #66819d;
    outline-offset: 2px;
  }
</style>
