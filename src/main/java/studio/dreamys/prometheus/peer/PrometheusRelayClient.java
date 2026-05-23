package studio.dreamys.prometheus.peer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
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
    private static final java.util.Queue<byte[]> queuedSends = new java.util.concurrent.ConcurrentLinkedQueue<>();

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
            LOGGER.info("Relay: attempting to send {} bytes", data.length);
            try {
                // Use synchronous send with timeout
                var result = s.sendBinary(ByteBuffer.wrap(data), true).get(5, TimeUnit.SECONDS);
                LOGGER.info("Relay: send complete");
            } catch (Exception e) {
                LOGGER.warn("Relay: send FAILED - {}: {}", e.getClass().getSimpleName(), e.getMessage());
            }
        } else {
            LOGGER.warn("Cannot send: WebSocket not connected yet, queuing for retry");
            // Queue the data for when the connection is ready
            queuedSends.add(data);
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
            webSocket.request(1); // signal we're ready to receive
            LOGGER.info("Relay connected as {} on {}", currentUsername, currentServer);
            
            // Flush any queued sends
            byte[] queued;
            while ((queued = queuedSends.poll()) != null) {
                LOGGER.info("Relay: flushing queued send ({} bytes)", queued.length);
                webSocket.sendBinary(ByteBuffer.wrap(queued), true);
            }
}

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String msg = data.toString();
            LOGGER.info("Relay: received text: {}", msg);
            try {
                // Simple JSON parsing for {"type":"peerJoined",...} and {"type":"youJoined",...}
                if (msg.contains("\"type\":\"youJoined\"")) {
                    LOGGER.info("Relay: we joined the server, broadcasting our outfit");
                    // We just joined - broadcast our outfit to everyone
                    PrometheusPeerNetworking.broadcastCurrentOutfit();
                } else if (msg.contains("\"type\":\"peerJoined\"")) {
                    int uuidIdx = msg.indexOf("\"uuid\":\"");
                    int usernameIdx = msg.indexOf("\"username\":\"");
                    if (uuidIdx >= 0 && usernameIdx >= 0) {
                        String peerUuid = msg.substring(uuidIdx + 8, msg.indexOf("\"", uuidIdx + 8));
                        String peerUsername = msg.substring(usernameIdx + 11, msg.indexOf("\"", usernameIdx + 11));
                        LOGGER.info("Relay: peer {} ({}) joined, broadcasting our outfit", peerUsername, peerUuid);
                        try {
                            // Broadcast our current outfit to the new peer
                            PrometheusPeerNetworking.broadcastCurrentOutfit();
                            LOGGER.info("Relay: broadcastCurrentOutfit completed");
                        } catch (Exception e) {
                            LOGGER.error("Relay: broadcastCurrentOutfit failed", e);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Relay: failed to parse text message: {}", e.getMessage());
            }
            webSocket.request(1);
return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            LOGGER.info("Relay: received {} bytes binary data", bytes.length);
            if (onOutfit != null) {
                onOutfit.accept(currentServer, bytes);
            }
            webSocket.request(1); // allow next message
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

