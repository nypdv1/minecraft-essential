package studio.dreamys.prometheus.peer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PrometheusPresence {
    private static final long PEER_TIMEOUT_MS = 30_000L;
    private static final Map<UUID, Long> peers = new ConcurrentHashMap<>();

    private PrometheusPresence() {}

    public static void markPeer(UUID uuid) {
        if (uuid == null || uuid.version() != 4) return;
        peers.put(uuid, System.currentTimeMillis());
    }

    public static boolean isPeer(UUID uuid) {
        if (uuid == null) return false;
        Long seen = peers.get(uuid);
        if (seen == null) return false;
        if (System.currentTimeMillis() - seen > PEER_TIMEOUT_MS) {
            peers.remove(uuid);
            return false;
        }
        return true;
    }

    public static void clear() {
        peers.clear();
    }
}
