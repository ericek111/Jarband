import type { Utterance } from './types';

type RowPlayback = {
  active: boolean;
  startAt: number;
  endAt: number;
  startTimer: ReturnType<typeof setTimeout> | null;
  endTimer: ReturnType<typeof setTimeout> | null;
};

export class RowPlaybackHighlighter {
  private readonly rows = new Map<string, RowPlayback>();

  constructor(private readonly onChange: (rows: Set<string>) => void) {}

  mark(utterance: Utterance, delayMillis = 0, durationMillis = utterance.endMillis - utterance.startMillis + 750) {
    const key = utteranceKey(utterance);
    const nowMillis = performance.now();
    const startAt = nowMillis + Math.max(0, delayMillis);
    const endAt = startAt + Math.max(250, durationMillis);
    let row = this.rows.get(key);

    if (!row) {
      row = { active: false, startAt, endAt, startTimer: null, endTimer: null };
      this.rows.set(key, row);
    } else {
      row.endAt = Math.max(row.endAt, endAt);
      if (!row.active && startAt < row.startAt) {
        row.startAt = startAt;
        if (row.startTimer) clearTimeout(row.startTimer);
        row.startTimer = null;
      }
    }

    if (row.active) {
      this.scheduleEnd(key, row);
      return;
    }

    if (!row.startTimer) {
      row.startTimer = setTimeout(() => {
        row!.startTimer = null;
        row!.active = true;
        this.emit();
        this.scheduleEnd(key, row!);
      }, Math.max(0, row.startAt - performance.now()));
    }
  }

  clear() {
    for (const row of this.rows.values()) {
      if (row.startTimer) clearTimeout(row.startTimer);
      if (row.endTimer) clearTimeout(row.endTimer);
    }
    this.rows.clear();
    this.emit();
  }

  private scheduleEnd(key: string, row: RowPlayback) {
    if (row.endTimer) clearTimeout(row.endTimer);
    row.endTimer = setTimeout(() => {
      this.rows.delete(key);
      this.emit();
    }, Math.max(50, row.endAt - performance.now()));
  }

  private emit() {
    this.onChange(new Set([...this.rows.entries()]
      .filter(([, row]) => row.active)
      .map(([key]) => key)));
  }
}

export function utteranceKey(utterance: Utterance) {
  return `${utterance.channel}:${utterance.startMillis}:${utterance.endMillis}`;
}
