package eu.lixko.jarband.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final Undertow undertow;
    private final LiveAudioHub hub;
    private final HistoricalRecordings history;
    private final Path outputDir;
    private final int recentChannelLimit;
    private final int opusSampleRateHz;
    private final int opusFrameMillis;

    public AirbandWebServer(String host, int port, int recentChannelLimit, Path outputDir,
                            LiveAudioHub hub, int opusSampleRateHz, int opusFrameMillis) {
        this.hub = hub;
        this.outputDir = outputDir;
        this.opusSampleRateHz = opusSampleRateHz;
        this.opusFrameMillis = opusFrameMillis;
        this.history = new HistoricalRecordings(outputDir, hub, opusSampleRateHz, opusFrameMillis);
        try {
            int seeded = history.seedLastActivityFromDbEnds();
            if (seeded > 0) {
                System.out.printf("Airband web: seeded last activity for %,d channels from recording DBs%n", seeded);
            }
        } catch (IOException e) {
            System.err.println("Airband web: failed to seed last activity from recording DBs: " + e.getMessage());
        }
        this.recentChannelLimit = recentChannelLimit;
        HttpHandler routes = Handlers.path()
                .addExactPath("/airband/ws", Handlers.websocket(new Callback()))
                .addExactPath("/airband/api/recording-files", this::recordingFilesResource)
                .addPrefixPath("/airband/recordings", this::recordingResource)
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

    private void recordingFilesResource(HttpServerExchange exchange) throws IOException {
        List<String> channelNames = queryChannels(exchange.getQueryParameters());
        StringBuilder json = new StringBuilder(4096);
        json.append("{\"files\":[");
        int count = 0;
        for (String channel : channelNames.isEmpty() ? allChannelNames() : channelNames) {
            Path channelDir = outputDir.resolve(channel);
            if (!Files.isDirectory(channelDir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(channelDir)) {
                for (Path db : files
                        .filter(path -> path.getFileName().toString().endsWith(".udb"))
                        .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                        .toList()) {
                    String base = db.getFileName().toString().replaceFirst("\\.udb$", "");
                    Path opus = channelDir.resolve(base + ".opus");
                    if (!Files.isRegularFile(opus)) {
                        continue;
                    }
                    if (count++ > 0) json.append(',');
                    json.append('{')
                            .append("\"channel\":\"").append(LiveAudioHub.escape(channel)).append("\",")
                            .append("\"dbUrl\":\"/airband/recordings/").append(LiveAudioHub.escape(channel))
                            .append('/').append(LiveAudioHub.escape(db.getFileName().toString())).append("\",")
                            .append("\"opusUrl\":\"/airband/recordings/").append(LiveAudioHub.escape(channel))
                            .append('/').append(LiveAudioHub.escape(opus.getFileName().toString())).append("\",")
                            .append("\"dbSize\":").append(Files.size(db)).append(',')
                            .append("\"opusSize\":").append(Files.size(opus)).append(',')
                            .append("\"sampleRate\":").append(opusSampleRateHz).append(',')
                            .append("\"frameMillis\":").append(opusFrameMillis)
                            .append('}');
                }
            }
        }
        json.append("]}");
        writeJson(exchange, json.toString());
    }

    private void recordingResource(HttpServerExchange exchange) throws IOException {
        String relative = exchange.getRelativePath();
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isBlank() || relative.contains("..")) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            return;
        }
        Path file = outputDir.resolve(relative).normalize();
        if (!file.startsWith(outputDir.normalize()) || !Files.isRegularFile(file)) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            return;
        }

        long size = Files.size(file);
        long start = 0;
        long end = size - 1;
        boolean partial = false;
        String range = exchange.getRequestHeaders().getFirst(Headers.RANGE);
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length());
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String startText = spec.substring(0, dash);
                String endText = spec.substring(dash + 1);
                if (startText.isBlank() && !endText.isBlank()) {
                    long suffixLength = Long.parseLong(endText);
                    start = Math.max(0, size - suffixLength);
                    end = size - 1;
                } else {
                    if (!startText.isBlank()) start = Long.parseLong(startText);
                    if (!endText.isBlank()) end = Long.parseLong(endText);
                }
                partial = true;
            }
        }
        if (size == 0 || start < 0 || end < start || start >= size) {
            exchange.setStatusCode(StatusCodes.REQUEST_RANGE_NOT_SATISFIABLE);
            exchange.getResponseHeaders().put(Headers.CONTENT_RANGE, "bytes */" + size);
            return;
        }
        end = Math.min(end, size - 1);
        int length = Math.toIntExact(end - start + 1);
        ByteBuffer bytes = ByteBuffer.allocate(length);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(start);
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
                // Copy the requested byte range.
            }
        }
        bytes.flip();
        exchange.setStatusCode(partial ? StatusCodes.PARTIAL_CONTENT : StatusCodes.OK);
        exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType(file.getFileName().toString()));
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Long.toString(length));
        if (partial) {
            exchange.getResponseHeaders().put(Headers.CONTENT_RANGE,
                    "bytes " + start + "-" + end + "/" + size);
        }
        exchange.getResponseSender().send(bytes);
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

    private static void writeJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(json);
    }

    private static String contentType(String path) {
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".opus")) return "audio/ogg; codecs=opus";
        if (path.endsWith(".udb")) return "application/octet-stream";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    @Override
    public void close() {
        undertow.stop();
    }

    private final class Callback implements WebSocketConnectionCallback {
        @Override
        public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
            LiveAudioHub.Client client = hub.register(channel);
            channel.getReceiveSetter().set(new Receiver(client));
            channel.addCloseTask(ch -> hub.unregister(client));
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
            hub.unregister(client);
        }

        @Override
        protected void onClose(WebSocketChannel webSocketChannel, StreamSourceFrameChannel channel) {
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
                    default -> error(channel, "Unknown command");
                }
            } catch (Exception e) {
                error(channel, e.getMessage());
            }
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

    private static List<String> queryChannels(Map<String, Deque<String>> query) {
        Deque<String> values = query.get("channel");
        return values == null ? List.of() : List.copyOf(values);
    }

    private List<String> allChannelNames() {
        var names = new ArrayList<String>();
        for (var channel : hub.channels()) {
            names.add(channel.name());
        }
        return names;
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
