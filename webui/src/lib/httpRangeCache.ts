export type HttpRangeFetchResult = {
  url: string;
  start: number;
  end: number;
  total: number | null;
  data: ArrayBuffer;
  fromCache: boolean;
};

export type HttpRangeCacheOptions = {
  maxBlocksPerUrl?: number;
  maxBytesPerUrl?: number;
  fetcher?: typeof fetch;
};

export type HttpRangeCacheStats = {
  urls: number;
  blocks: number;
  bytes: number;
  inFlight: number;
};

export type HttpRangeRequestOptions = {
  signal?: AbortSignal;
  blockSize?: number;
};

type CachedBlock = {
  start: number;
  end: number;
  data: ArrayBuffer;
  lastUsed: number;
};

type CacheEntry = {
  blocks: CachedBlock[];
  total: number | null;
};

type MissingRange = {
  start: number;
  end: number;
};

type CachedSegment = {
  block: CachedBlock;
  start: number;
  end: number;
};

const DEFAULT_MAX_BLOCKS_PER_URL = 64;
const DEFAULT_MAX_BYTES_PER_URL = 32 * 1024 * 1024;

// All public ranges use the same convention as Array.prototype.slice:
// start is inclusive, end is exclusive. HTTP Range headers are converted at
// the network boundary.
export class SparseHttpRangeCache {
  private readonly entries = new Map<string, CacheEntry>();
  private readonly inFlight = new Map<string, Promise<Omit<HttpRangeFetchResult, 'url' | 'fromCache'>>>();
  private readonly maxBlocksPerUrl: number;
  private readonly maxBytesPerUrl: number;
  private readonly fetcher: typeof fetch;
  private accessTick = 0;

  constructor(options: HttpRangeCacheOptions = {}) {
    this.maxBlocksPerUrl = options.maxBlocksPerUrl ?? DEFAULT_MAX_BLOCKS_PER_URL;
    this.maxBytesPerUrl = options.maxBytesPerUrl ?? DEFAULT_MAX_BYTES_PER_URL;
    this.fetcher = options.fetcher ?? fetch.bind(globalThis);
  }

  async fetchRange(url: string, start: number, end: number,
                   options: HttpRangeRequestOptions = {}): Promise<HttpRangeFetchResult> {
    assertBoundedRange(start, end);
    const cached = this.readCached(url, start, end);
    if (cached) {
      return { url, start, end, total: this.knownTotal(url), data: cached, fromCache: true };
    }

    const missing = coalesceRanges(this.missingRanges(url, start, end)
      .map(range => alignRange(range, options.blockSize)));
    for (const range of missing) {
      const result = await this.fetchHttpRange(url, `bytes=${range.start}-${range.end - 1}`, options.signal);
      this.addBlock(url, result.start, result.end, result.data, result.total);
    }

    const data = this.readCached(url, start, end);
    if (!data) {
      throw new Error(`Fetched ranges did not cover requested bytes ${start}-${end - 1}.`);
    }
    return { url, start, end, total: this.knownTotal(url), data, fromCache: false };
  }

  async fetchOpenEnded(url: string, start: number,
                       options: HttpRangeRequestOptions = {}): Promise<HttpRangeFetchResult> {
    if (!Number.isSafeInteger(start) || start < 0) {
      throw new Error(`Invalid range start: ${start}`);
    }
    const total = this.knownTotal(url);
    // Once EOF is known, an open-ended request is just a normal bounded range
    // and can be satisfied from existing sparse blocks.
    if (total !== null && start <= total) {
      if (start === total) {
        return { url, start, end: total, total, data: new ArrayBuffer(0), fromCache: true };
      }
      return this.fetchRange(url, start, total, options);
    }
    const result = await this.fetchHttpRange(url, `bytes=${start}-`, options.signal);
    this.addBlock(url, result.start, result.end, result.data, result.total);
    return { ...result, url, fromCache: false };
  }

  async fetchSuffix(url: string, length: number,
                    options: HttpRangeRequestOptions = {}): Promise<HttpRangeFetchResult> {
    if (!Number.isSafeInteger(length) || length <= 0) {
      throw new Error(`Invalid suffix length: ${length}`);
    }
    const total = this.knownTotal(url);
    // Suffix ranges are server-relative until EOF is known. After that, they
    // become cacheable bounded reads.
    if (total !== null) {
      if (total === 0) {
        return { url, start: 0, end: 0, total, data: new ArrayBuffer(0), fromCache: true };
      }
      return this.fetchRange(url, Math.max(0, total - length), total, options);
    }
    const result = await this.fetchHttpRange(url, `bytes=-${length}`, options.signal);
    this.addBlock(url, result.start, result.end, result.data, result.total);
    return { ...result, url, fromCache: false };
  }

  clear(url?: string) {
    if (url === undefined) {
      this.entries.clear();
      return;
    }
    this.entries.delete(url);
  }

  knownTotal(url: string) {
    return this.entries.get(url)?.total ?? null;
  }

  stats(): HttpRangeCacheStats {
    let blocks = 0;
    let bytes = 0;
    for (const entry of this.entries.values()) {
      blocks += entry.blocks.length;
      bytes += totalBytes(entry.blocks);
    }
    return {
      urls: this.entries.size,
      blocks,
      bytes,
      inFlight: this.inFlight.size
    };
  }

  private readCached(url: string, start: number, end: number) {
    const segments = this.cachedSegments(url, start, end);
    if (!segments) return null;
    if (segments.length === 1) {
      const segment = segments[0];
      return segment.block.data.slice(segment.start - segment.block.start, segment.end - segment.block.start);
    }

    const output = new Uint8Array(end - start);
    for (const segment of segments) {
      output.set(
        new Uint8Array(segment.block.data, segment.start - segment.block.start, segment.end - segment.start),
        segment.start - start
      );
    }
    return output.buffer;
  }

  private cachedSegments(url: string, start: number, end: number) {
    const entry = this.entries.get(url);
    if (!entry) return null;

    const segments: CachedSegment[] = [];
    let cursor = start;
    for (const block of entry.blocks) {
      if (block.end <= cursor) continue;
      if (block.start > cursor) return null;
      const copyStart = Math.max(cursor, block.start);
      const copyEnd = Math.min(end, block.end);
      segments.push({ block, start: copyStart, end: copyEnd });
      cursor = copyEnd;
      if (cursor >= end) {
        const lastUsed = this.nextAccessTick();
        for (const segment of segments) segment.block.lastUsed = lastUsed;
        return segments;
      }
    }
    return null;
  }

  private missingRanges(url: string, start: number, end: number) {
    const entry = this.entries.get(url);
    if (!entry) return [{ start, end }];

    const missing: MissingRange[] = [];
    let cursor = start;
    for (const block of entry.blocks) {
      if (block.end <= cursor) continue;
      if (block.start > cursor) {
        missing.push({ start: cursor, end: Math.min(block.start, end) });
      }
      if (block.end > cursor) {
        cursor = block.end;
      }
      if (cursor >= end) break;
    }
    if (cursor < end) {
      missing.push({ start: cursor, end });
    }
    return missing;
  }

  private addBlock(url: string, start: number, end: number, data: ArrayBuffer, total: number | null) {
    if (end <= start || data.byteLength === 0) return;
    const entry = this.entryFor(url);
    if (total !== null) entry.total = total;
    if (this.cachedSegments(url, start, end)) return;

    const nextBlock: CachedBlock = {
      start,
      end,
      data,
      lastUsed: this.nextAccessTick()
    };
    const remaining: CachedBlock[] = [];
    const touching: CachedBlock[] = [];
    for (const block of entry.blocks) {
      if (block.end < nextBlock.start || block.start > nextBlock.end) {
        remaining.push(block);
      } else {
        touching.push(block);
      }
    }

    if (!touching.length) {
      entry.blocks = [...remaining, nextBlock].sort(compareBlocks);
      this.prune(url);
      return;
    }

    let mergedStart = nextBlock.start;
    let mergedEnd = nextBlock.end;
    for (const block of touching) {
      mergedStart = Math.min(mergedStart, block.start);
      mergedEnd = Math.max(mergedEnd, block.end);
    }
    const mergedBytes = new Uint8Array(mergedEnd - mergedStart);
    for (const block of touching) {
      mergedBytes.set(new Uint8Array(block.data), block.start - mergedStart);
    }
    mergedBytes.set(new Uint8Array(nextBlock.data), nextBlock.start - mergedStart);

    entry.blocks = [
      ...remaining,
      {
        start: mergedStart,
        end: mergedEnd,
        data: mergedBytes.buffer,
        lastUsed: this.nextAccessTick()
      }
    ].sort(compareBlocks);
    this.prune(url);
  }

  private async fetchHttpRange(url: string, range: string, signal?: AbortSignal): Promise<Omit<HttpRangeFetchResult, 'url' | 'fromCache'>> {
    const key = `${url}\u0000${range}`;
    const existing = this.inFlight.get(key);
    if (existing) return existing;

    // Identical concurrent ranges share one network request. This is important
    // when seek/playback code asks for the same utterance more than once before
    // the first response has completed.
    const request = this.fetcher(url, { signal, headers: { Range: range } })
      .then(async response => {
        if (!response.ok && response.status !== 206) {
          throw new Error(`Range request failed: ${response.status}`);
        }
        const data = await response.arrayBuffer();
        const contentRange = parseContentRange(response.headers.get('Content-Range'));
        if (contentRange) {
          return { ...contentRange, end: contentRange.end + 1, data };
        }
        return { start: 0, end: data.byteLength, total: data.byteLength, data };
      })
      .finally(() => this.inFlight.delete(key));
    this.inFlight.set(key, request);
    return request;
  }

  private entryFor(url: string) {
    let entry = this.entries.get(url);
    if (!entry) {
      entry = { blocks: [], total: null };
      this.entries.set(url, entry);
    }
    return entry;
  }

  private prune(url: string) {
    const entry = this.entries.get(url);
    if (!entry) return;
    while (entry.blocks.length > this.maxBlocksPerUrl || totalBytes(entry.blocks) > this.maxBytesPerUrl) {
      const oldest = entry.blocks.reduce((oldestIndex, block, index) =>
        block.lastUsed < entry.blocks[oldestIndex].lastUsed ? index : oldestIndex, 0);
      entry.blocks.splice(oldest, 1);
    }
  }

  private nextAccessTick() {
    this.accessTick += 1;
    return this.accessTick;
  }
}

export function parseContentRange(header: string | null) {
  const match = /^bytes\s+(\d+)-(\d+)\/(\d+|\*)$/i.exec(header ?? '');
  if (!match) return null;
  return {
    start: Number(match[1]),
    end: Number(match[2]),
    total: match[3] === '*' ? null : Number(match[3])
  };
}

function assertBoundedRange(start: number, end: number) {
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end <= start) {
    throw new Error(`Invalid byte range: ${start}-${end}`);
  }
}

function alignRange(range: MissingRange, blockSize?: number): MissingRange {
  if (!blockSize || blockSize <= 1) return range;
  return {
    start: Math.max(0, Math.floor(range.start / blockSize) * blockSize),
    end: Math.ceil(range.end / blockSize) * blockSize
  };
}

function coalesceRanges(ranges: MissingRange[]) {
  const sorted = ranges.filter(range => range.end > range.start)
    .sort((a, b) => a.start - b.start || a.end - b.end);
  const coalesced: MissingRange[] = [];
  for (const range of sorted) {
    const last = coalesced.at(-1);
    if (!last || range.start > last.end) {
      coalesced.push({ ...range });
    } else {
      last.end = Math.max(last.end, range.end);
    }
  }
  return coalesced;
}

function compareBlocks(a: CachedBlock, b: CachedBlock) {
  return a.start - b.start || a.end - b.end;
}

function totalBytes(blocks: CachedBlock[]) {
  return blocks.reduce((total, block) => total + block.data.byteLength, 0);
}
