package studio.dreamys.prometheus.peer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

public class PrometheusRelayClient {
    private static final Logger LOGGER = LogManager.getLogger("Prometheus");
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final long RECONNECT_DELAY_MS = 5000;

    private static WebSocket ws;
    private static String relayUrl;
    private static String currentUuid;
    private static String currentUsername;
    private static String currentServer;
    private static BiConsumer<String, byte[]> onOutfit;

    public static void connect(String url, String uuid, String username, String server, BiConsumer<String, byte[]> callback) {
        if (url == null || url.isEmpty()) {
            LOGGER.warn("No relay URL configured");
            return;
        }

        relayUrl = url;
        currentUuid = uuid;
        currentUsername = username;
        currentServer = server;
        onOutfit = callback;

        LOGGER.info("Connecting to relay: {}", url);
        httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(url), new Listener())
            .exceptionally(t -> {
                LOGGER.warn("Relay connection failed, retrying in {}ms", RECONNECT_DELAY_MS, t);
                scheduleReconnect();
                return null;
            });
    }

    public static void send(byte[] data) {
        WebSocket s = ws;
        if (s != null) {
            s.sendBinary(ByteBuffer.wrap(data), true);
        } else {
            LOGGER.warn("Cannot send: WebSocket not connected yet");
        }
    }

    public static void disconnect() {
        WebSocket s = ws;
        if (s != null) {
            s.sendClose(WebSocket.NORMAL_CLOSURE, "");
            ws = null;
        }
    }

    private static void scheduleReconnect() {
        Thread timer = new Thread(() -> {
            try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException e) { return; }
            connect(relayUrl, currentUuid, currentUsername, currentServer, onOutfit);
        });
        timer.setDaemon(true);
        timer.start();
    }

    private static class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            String msg = String.format(
                "{\"type\":\"identify\",\"uuid\":\"%s\",\"username\":\"%s\",\"server\":\"%s\"}",
                currentUuid, currentUsername, currentServer
            );
            webSocket.sendText(msg, true);
            LOGGER.info("Relay connected as {} on {}", currentUsername, currentServer);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            if (onOutfit != null) {
                onOutfit.accept(currentServer, bytes);
            }
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOGGER.warn("Relay error", error);
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOGGER.info("Relay closed ({}): {}", statusCode, reason);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }
}

