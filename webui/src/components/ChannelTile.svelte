<script lang="ts">
  let {
    name,
    lastActiveLabel,
    active,
    selected,
    historyColor,
    live,
    replaying,
    onHistory,
    onAddFilter,
    onRemoveFilter,
    onLive,
    onReplayLast
  }: {
    name: string;
    lastActiveLabel: string;
    active: boolean;
    selected: boolean;
    historyColor: string | null;
    live: boolean;
    replaying: boolean;
    onHistory: (name: string) => void;
    onAddFilter: (name: string) => void;
    onRemoveFilter: (name: string) => void;
    onLive: (name: string) => void;
    onReplayLast: (name: string) => void;
  } = $props();

  function showHistory() {
    onHistory(name);
  }

  function addFilter() {
    if (selected) {
      onRemoveFilter(name);
    } else {
      onAddFilter(name);
    }
  }

  function toggleLive() {
    onLive(name);
  }

  function replayLast() {
    onReplayLast(name);
  }
</script>

<div class:active-channel={active} class="channel">
  <strong style:color={historyColor}>{name}</strong>
  <span>{lastActiveLabel}</span>
  <div class:active={selected} class="split-button">
    <button type="button" onclick={showHistory}>History</button>
    <button type="button" aria-label={`${selected ? 'Remove' : 'Add'} ${name} ${selected ? 'from' : 'to'} history filter`}
      onclick={addFilter}>{selected ? '-' : '+'}</button>
  </div>
  <div class="listen-row">
    <button type="button" class:live onclick={toggleLive}>
      {live ? 'Listening' : 'Listen'}
    </button>
    <button type="button" class="icon-button replay-last" aria-label={`Play last ${name} utterance`}
      onclick={replayLast}>
      <span class="icon-symbol">{replaying ? '■' : '↻'}</span>
    </button>
  </div>
</div>
