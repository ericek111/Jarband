package eu.lixko.jarband.web;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import eu.lixko.jarband.dsp.channelizer.ChannelPlan;
import eu.lixko.jarband.dsp.channelizer.LogicalChannel;
import eu.lixko.jarband.recording.EncodedOpusFrame;
import eu.lixko.jarband.recording.OpusFrameSink;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;

public final class LiveAudioHub implements OpusFrameSink {
    public static final int PACKET_MAGIC = 0x4a424f50; // JBOP
    private static final int LIVE_BUFFER_PACKETS = 2_000;
    private static final int SUBSCRIBE_REPLAY_PACKETS = 200;
    private static final int LIVE_BATCH_MILLIS = 200;

    private final List<LogicalChannel> channels;
    private final ChannelActivity[] activity;
    private final CopyOnWriteArraySet<Client> clients = new CopyOnWriteArraySet<>();

    public LiveAudioHub(ChannelPlan plan) {
        this.channels = List.copyOf(plan.channels());
        this.activity = new ChannelActivity[channels.size()];
        for (LogicalChannel channel : channels) {
            activity[channel.id()] = new ChannelActivity();
        }
    }

    public Client register(WebSocketChannel channel) {
        Client client = new Client(channel);
        clients.add(client);
        return client;
    }

    public void unregister(Client client) {
        clients.remove(client);
    }

    public List<LogicalChannel> channels() {
        return channels;
    }

    public LogicalChannel channelByName(String name) {
        for (LogicalChannel channel : channels) {
            if (channel.name().equals(name)) {
                return channel;
            }
        }
        return null;
    }

    public String channelListJson(int recentLimit) {
        StringBuilder json = new StringBuilder(8192);
        json.append("{\"type\":\"channels\",\"channels\":[");
        for (int i = 0; i < channels.size(); i++) {
            if (i > 0) json.append(',');
            appendChannel(json, channels.get(i));
        }
        json.append("],\"recent\":[");
        List<LogicalChannel> recent = new ArrayList<>(channels);
        recent.sort(Comparator.comparingLong((LogicalChannel c) -> activity[c.id()].lastActivityMillis).reversed());
        int emitted = 0;
        for (LogicalChannel channel : recent) {
            if (activity[channel.id()].lastActivityMillis <= 0) {
                break;
            }
            if (emitted >= recentLimit) {
                break;
            }
            if (emitted++ > 0) json.append(',');
            appendChannel(json, channel);
        }
        json.append("]}");
        return json.toString();
    }

    private void appendChannel(StringBuilder json, LogicalChannel channel) {
        ChannelActivity item = activity[channel.id()];
        json.append('{')
                .append("\"id\":").append(channel.id()).append(',')
                .append("\"name\":\"").append(escape(channel.name())).append("\",")
                .append("\"frequencyHz\":").append(Math.round(channel.frequencyHz())).append(',')
                .append("\"lastActivityMillis\":").append(item.lastActivityMillis).append(',')
                .append("\"active\":").append(item.active)
                .append('}');
    }

    private String activityJson(LogicalChannel channel) {
        StringBuilder json = new StringBuilder(160);
        json.append("{\"type\":\"activity\",\"channel\":");
        appendChannel(json, channel);
        json.append('}');
        return json.toString();
    }

    @Override
    public void accept(EncodedOpusFrame frame) {
        ChannelActivity item = activity[frame.channelId()];
        boolean wasActive = item.active;
        item.lastActivityMillis = frame.unixMillis();
        item.active = true;
        synchronized (item.recentPackets) {
            item.recentPackets.addLast(frame);
            while (item.recentPackets.size() > LIVE_BUFFER_PACKETS) {
                item.recentPackets.removeFirst();
            }
        }

        for (Client client : clients) {
            if (client.isSubscribed(frame.channelId())) {
                client.enqueueLive(frame);
            }
        }
        if (!wasActive) {
            broadcastActivity(channels.get(frame.channelId()));
        }
    }

    public void closeUtterance(int channelId, long unixMillis) {
        activity[channelId].active = false;
        activity[channelId].lastActivityMillis = Math.max(activity[channelId].lastActivityMillis, unixMillis);
        for (Client client : clients) {
            client.flushLiveBatch(channelId);
        }
        broadcastActivity(channels.get(channelId));
    }

    private void broadcastActivity(LogicalChannel channel) {
        String message = activityJson(channel);
        for (Client client : clients) {
            if (client.channel.isOpen()) {
                WebSockets.sendText(message, client.channel, null);
            }
        }
    }

    public void replayRecent(Client client, List<String> names) {
        for (String name : names) {
            LogicalChannel channel = channelByName(name);
            if (channel == null) {
                continue;
            }
            List<EncodedOpusFrame> frames;
            ChannelActivity item = activity[channel.id()];
            synchronized (item.recentPackets) {
                int skip = Math.max(0, item.recentPackets.size() - SUBSCRIBE_REPLAY_PACKETS);
                frames = item.recentPackets.stream().skip(skip).toList();
            }
        for (EncodedOpusFrame frame : frames) {
            if (!client.channel.isOpen()) {
                return;
            }
                client.enqueueLive(frame);
            }
        }
    }

    public static ByteBuffer packetMessage(EncodedOpusFrame frame) {
        return batchMessage(List.of(frame));
    }

    static String escape(String input) {
        StringBuilder out = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    public final class Client {
        private final WebSocketChannel channel;
        private final Set<Integer> subscriptions = new HashSet<>();
        private final ConcurrentHashMap<Integer, Batch> batches = new ConcurrentHashMap<>();
        private final CopyOnWriteArraySet<HistoryPlayback> playbacks = new CopyOnWriteArraySet<>();

        private Client(WebSocketChannel channel) {
            this.channel = channel;
        }

        public synchronized void subscribe(List<String> names) {
            for (String name : names) {
                LogicalChannel channel = channelByName(name);
                if (channel != null) {
                    subscriptions.add(channel.id());
                }
            }
        }

        public synchronized void unsubscribe(List<String> names) {
            for (String name : names) {
                LogicalChannel channel = channelByName(name);
                if (channel != null) {
                    subscriptions.remove(channel.id());
                }
            }
        }

        public synchronized boolean isSubscribed(int channelId) {
            return subscriptions.contains(channelId);
        }

        void enqueueLive(EncodedOpusFrame frame) {
            batches.compute(frame.channelId(), (channelId, batch) -> {
                if (batch == null) {
                    batch = new Batch();
                }
                batch.add(frame);
                if (batch.durationMillis >= LIVE_BATCH_MILLIS) {
                    sendBatch(batch);
                    return null;
                }
                return batch;
            });
        }

        public void flushLiveBatches() {
            for (var entry : batches.entrySet()) {
                Batch batch = batches.remove(entry.getKey());
                if (batch != null && !batch.frames.isEmpty()) {
                    sendBatch(batch);
                }
            }
        }

        public void flushLiveBatch(int channelId) {
            Batch batch = batches.remove(channelId);
            if (batch != null && !batch.frames.isEmpty()) {
                sendBatch(batch);
            }
        }

        private void sendBatch(Batch batch) {
            if (!channel.isOpen() || batch.frames.isEmpty()) {
                return;
            }
            WebSockets.sendBinary(batchMessage(batch.frames), channel, null);
        }

        public WebSocketChannel channel() {
            return channel;
        }

        public void addPlayback(HistoryPlayback playback) {
            playbacks.add(playback);
        }

        public void removePlayback(HistoryPlayback playback) {
            playbacks.remove(playback);
        }

        public void stopPlaybacks() {
            for (HistoryPlayback playback : playbacks) {
                playback.stop();
            }
            playbacks.clear();
        }
    }

    public interface HistoryPlayback {
        void stop();
    }

    private static ByteBuffer batchMessage(List<EncodedOpusFrame> frames) {
        int bytes = 4 + 2 + 2;
        for (EncodedOpusFrame frame : frames) {
            byte[] name = frame.channelName().getBytes(StandardCharsets.UTF_8);
            byte[] streamKey = frame.streamKey().getBytes(StandardCharsets.UTF_8);
            bytes += 2 + 2 + 4 + 8 + 4 + 4 + 8 + 2 + name.length + 2 + streamKey.length
                    + frame.packet().length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(PACKET_MAGIC);
        buffer.putShort((short) 2);
        buffer.putShort((short) frames.size());
        for (EncodedOpusFrame frame : frames) {
            byte[] name = frame.channelName().getBytes(StandardCharsets.UTF_8);
            byte[] streamKey = frame.streamKey().getBytes(StandardCharsets.UTF_8);
            byte[] packet = frame.packet();
            buffer.putShort((short) 2);
            buffer.putShort((short) frame.channelId());
            buffer.putInt(frame.sampleRateHz());
            buffer.putLong(frame.unixMillis());
            buffer.putInt(frame.durationMillis());
            buffer.putInt(packet.length);
            buffer.putLong(frame.sequence());
            buffer.putShort((short) name.length);
            buffer.put(name);
            buffer.putShort((short) streamKey.length);
            buffer.put(streamKey);
            buffer.put(packet);
        }
        buffer.flip();
        return buffer;
    }

    private static final class Batch {
        final ArrayList<EncodedOpusFrame> frames = new ArrayList<>();
        int durationMillis;

        void add(EncodedOpusFrame frame) {
            frames.add(frame);
            durationMillis += frame.durationMillis();
        }
    }

    private static final class ChannelActivity {
        final ArrayDeque<EncodedOpusFrame> recentPackets = new ArrayDeque<>();
        volatile long lastActivityMillis;
        volatile boolean active;
    }
}
