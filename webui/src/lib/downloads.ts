import type { OpusPacket } from './types';
import { opusPacketsToWav } from './wav';

type PendingDownload = {
  filename: string;
  packets: OpusPacket[];
};

export class DownloadManager {
  private readonly pending = new Map<number, PendingDownload>();

  start(downloadId: number, filename: string) {
    this.pending.set(downloadId, { filename, packets: [] });
  }

  collect(packet: OpusPacket) {
    const id = this.idFromStreamKey(packet.streamKey);
    if (id === null) return false;
    this.pending.get(id)?.packets.push(packet);
    return true;
  }

  async finish(downloadId: number) {
    const download = this.pending.get(downloadId);
    this.pending.delete(downloadId);
    if (!download) return null;

    const wav = await opusPacketsToWav(download.packets);
    const url = URL.createObjectURL(wav);
    const link = document.createElement('a');
    link.href = url;
    link.download = download.filename;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30_000);
    return download.filename;
  }

  private idFromStreamKey(streamKey: string) {
    const match = /^download:(\d+):/.exec(streamKey);
    return match ? Number(match[1]) : null;
  }
}
