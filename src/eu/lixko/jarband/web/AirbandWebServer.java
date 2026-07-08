package eu.lixko.jarband.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.lixko.jarband.recording.EncodedOpusFrame;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.StreamSourceFrameChannel;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;

public final class AirbandWebServer implements AutoCloseable {
    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CHANNELS = Pattern.compile("\"channels\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FROM = Pattern.compile("\"fromMillis\"\\s*:\\s*(\\d+)");
    private static final Pattern TO = Pattern.compile("\"toMillis\"\\s*:\\s*(\\d+)");
    private static final Pattern PAGE = Pattern.compile("\"page\"\\s*:\\s*(\\d+)");
    private static final Pattern PAGE_SIZE = Pattern.compile("\"pageSize\"\\s*:\\s*(\\d+)");
    private static final Pattern REALTIME = Pattern.compile("\"realtime\"\\s*:\\s*true");

    private final Undertow undertow;
    private final LiveAudioHub hub;
    private final HistoricalRecordings history;
    private final AtomicLong playbackIds = new AtomicLong();
    private final ExecutorService historyExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "jarband-web-history");
        thread.setDaemon(true);
        return thread;
    });
    private final int recentChannelLimit;

    public AirbandWebServer(String host, int port, int recentChannelLimit, Path outputDir,
                            LiveAudioHub hub, int opusSampleRateHz, int opusFrameMillis) {
        this.hub = hub;
        this.history = new HistoricalRecordings(outputDir, hub, opusSampleRateHz, opusFrameMillis);
        this.recentChannelLimit = recentChannelLimit;
        HttpHandler routes = Handlers.path()
                .addExactPath("/airband/ws", Handlers.websocket(new Callback()))
                .addPrefixPath("/airband", this::staticResource)
                .addExactPath("/", exchange -> redirect(exchange, "/airband/"));
        this.undertow = Undertow.builder()
                .addHttpListener(port, host)
                .setHandler(routes)
                .build();
    }

    public void start() {
        undertow.start();
    }

    private void staticResource(HttpServerExchange exchange) throws IOException {
        String path = exchange.getRelativePath();
        if (path.isEmpty() || path.equals("/")) {
            writeResource(exchange, "airband/index.html", "text/html; charset=utf-8");
            return;
        }
        if (path.contains("..")) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            return;
        }
        writeResource(exchange, "airband" + path, contentType(path));
    }

    private static void redirect(HttpServerExchange exchange, String location) {
        exchange.setStatusCode(StatusCodes.FOUND);
        exchange.getResponseHeaders().put(Headers.LOCATION, location);
    }

    private static void writeResource(HttpServerExchange exchange, String name, String contentType) throws IOException {
        try (InputStream in = AirbandWebServer.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseSender().send(ByteBuffer.wrap(bytes));
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    @Override
    public void close() {
        historyExecutor.shutdownNow();
        undertow.stop();
    }

    private final class Callback implements WebSocketConnectionCallback {
        @Override
        public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
            LiveAudioHub.Client client = hub.register(channel);
            channel.getReceiveSetter().set(new Receiver(client));
            channel.addCloseTask(ch -> {
                client.stopPlaybacks();
                hub.unregister(client);
            });
            channel.resumeReceives();
            sendText(channel, hub.channelListJson(recentChannelLimit));
        }
    }

    private final class Receiver extends AbstractReceiveListener {
        private final LiveAudioHub.Client client;

        Receiver(LiveAudioHub.Client client) {
            this.client = client;
        }

        @Override
        protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
            handle(channel, message.getData());
        }

        @Override
        protected void onError(WebSocketChannel channel, Throwable error) {
            stopPlayback(client);
            hub.unregister(client);
        }

        @Override
        protected void onClose(WebSocketChannel webSocketChannel, StreamSourceFrameChannel channel) {
            stopPlayback(client);
            hub.unregister(client);
        }

        private void handle(WebSocketChannel channel, String json) {
            try {
                switch (type(json)) {
                    case "list_channels" -> sendText(channel, hub.channelListJson(recentChannelLimit));
                    case "subscribe_live" -> {
                        List<String> names = channels(json);
                        client.subscribe(names);
                        sendText(channel, "{\"type\":\"subscribed\"}");
                    }
                    case "unsubscribe_live" -> {
                        client.unsubscribe(channels(json));
                        sendText(channel, "{\"type\":\"unsubscribed\"}");
                    }
                    case "history_index" -> sendText(channel,
                            history.indexJson(channels(json), millis(json, FROM, 0), millis(json, TO, Long.MAX_VALUE)));
                    case "recent_history" -> sendText(channel,
                            history.recentJson(channels(json),
                                    (int) millis(json, PAGE, 0), (int) millis(json, PAGE_SIZE, 20)));
                    case "play_history" -> playHistory(client, channels(json),
                            millis(json, FROM, 0), millis(json, TO, Long.MAX_VALUE), true, realtime(json));
                    case "play_utterance" -> playHistory(client, channels(json),
                            millis(json, FROM, 0), millis(json, TO, Long.MAX_VALUE), false, realtime(json));
                    case "play_last_utterance" -> playLastUtterance(client, channels(json));
                    case "stop_history" -> {
                        stopPlayback(client);
                        sendText(channel, "{\"type\":\"history_stopped\"}");
                    }
                    default -> error(channel, "Unknown command");
                }
            } catch (Exception e) {
                error(channel, e.getMessage());
            }
        }
    }

    private void playLastUtterance(LiveAudioHub.Client client, List<String> channels) throws IOException {
        if (channels.isEmpty()) {
            return;
        }
        String name = channels.getFirst();
        long[] range = history.latestUtteranceMillis(name);
        if (range == null) {
            error(client.channel(), "No recording for " + name);
            return;
        }
        playHistory(client, List.of(name), range[0], range[1], false, false);
    }

    private void playHistory(LiveAudioHub.Client client, List<String> channels, long fromMillis, long toMillis,
                             boolean replaceExisting, boolean realtime) {
        if (replaceExisting) {
            stopPlayback(client);
        }
        HistoryTask task = new HistoryTask(client, channels, fromMillis, toMillis, playbackIds.incrementAndGet(),
                realtime);
        client.addPlayback(task);
        historyExecutor.execute(task);
    }

    private void stopPlayback(LiveAudioHub.Client client) {
        client.stopPlaybacks();
    }

    private final class HistoryTask implements Runnable, LiveAudioHub.HistoryPlayback {
        private final LiveAudioHub.Client client;
        private final List<String> channels;
        private final long fromMillis;
        private final long toMillis;
        private final long playbackId;
        private final boolean realtime;
        private volatile boolean stopped;

        HistoryTask(LiveAudioHub.Client client, List<String> channels, long fromMillis, long toMillis,
                    long playbackId, boolean realtime) {
            this.client = client;
            this.channels = List.copyOf(channels);
            this.fromMillis = fromMillis;
            this.toMillis = toMillis;
            this.playbackId = playbackId;
            this.realtime = realtime;
        }

        @Override
        public void run() {
            try {
                List<EncodedOpusFrame> frames = history.packets(channels, fromMillis, toMillis);
                sendText(client.channel(), "{\"type\":\"history_started\",\"frames\":" + frames.size()
                        + ",\"fromMillis\":" + fromMillis
                        + ",\"toMillis\":" + toMillis
                        + ",\"playbackId\":" + playbackId
                        + ",\"realtime\":" + realtime
                        + ",\"channels\":" + channelsJson(channels) + "}");
                for (EncodedOpusFrame frame : frames) {
                    if (stopped || !client.channel().isOpen()) {
                        break;
                    }
                    WebSockets.sendBinary(LiveAudioHub.packetMessage(
                            frame.withStreamKey("history:" + playbackId + ":" + frame.channelName())),
                            client.channel(), null);
                }
                if (!stopped && client.channel().isOpen()) {
                    sendText(client.channel(), "{\"type\":\"history_finished\",\"playbackId\":" + playbackId + "}");
                }
            } catch (Exception e) {
                if (client.channel().isOpen()) {
                    error(client.channel(), e.getMessage());
                }
            } finally {
                client.removePlayback(this);
            }
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    private static String type(String json) {
        Matcher matcher = TYPE.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> channels(String json) {
        Matcher array = CHANNELS.matcher(json);
        if (!array.find()) {
            return List.of();
        }
        var channels = new ArrayList<String>();
        Matcher strings = STRING.matcher(array.group(1));
        while (strings.find()) {
            channels.add(unescape(strings.group(1)));
        }
        return channels;
    }

    private static long millis(String json, Pattern pattern, long fallback) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : fallback;
    }

    private static boolean realtime(String json) {
        return REALTIME.matcher(json).find();
    }

    private static String channelsJson(List<String> channels) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < channels.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(LiveAudioHub.escape(channels.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private static String unescape(String input) {
        return input.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static void sendText(WebSocketChannel channel, String text) {
        WebSockets.sendText(text, channel, null);
    }

    private static void error(WebSocketChannel channel, String message) {
        sendText(channel, "{\"type\":\"error\",\"message\":\"" + LiveAudioHub.escape(message == null ? "" : message) + "\"}");
    }
}
