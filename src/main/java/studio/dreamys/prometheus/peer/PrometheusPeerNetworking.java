package studio.dreamys.prometheus.peer;

import gg.essential.cosmetics.OutfitUpdatesPayload;
import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PrometheusPeerNetworking {
    private static final Logger LOGGER = LogManager.getLogger("Prometheus");
    private static final String RELAY_URL = "wss://acsx-fsa-production.up.railway.app";
    private static boolean initialized;
    private static final ThreadLocal<Boolean> applyingFromRelay = ThreadLocal.withInitial(() -> false);

    private PrometheusPeerNetworking() {}

    public static void initClient() {
        if (initialized) return;
        initialized = true;

        LOGGER.info("Peer sync via relay: {}", RELAY_URL);

        String server = "singleplayer";
        UUID uuid;
        String username;
        try {
            ClassLoader cl = PrometheusPeerNetworking.class.getClassLoader();
            Object mc = Class.forName("net.minecraft.client.Minecraft", false, cl)
                .getMethod("getMinecraft").invoke(null);
            Object serverData = mc.getClass().getMethod("getCurrentServerData").invoke(mc);
            if (serverData != null) server = (String) serverData.getClass().getField("serverIP").get(serverData);
            Object s = mc.getClass().getMethod("getSession").invoke(mc);
            Object profile = s.getClass().getMethod("getProfile").invoke(s);
            uuid = (UUID) profile.getClass().getMethod("getId").invoke(profile);
            username = (String) s.getClass().getMethod("getUsername").invoke(s);
        } catch (Throwable t) {
            LOGGER.warn("Failed to resolve player identity via Minecraft", t);
            try {
                Object session = gg.essential.util.USession.Companion.activeNow();
                uuid = (UUID) session.getClass().getMethod("getUuid").invoke(session);
                username = (String) session.getClass().getMethod("getUsername").invoke(session);
                LOGGER.info("Resolved identity via USession: {} {}", uuid, username);
            } catch (Throwable t2) {
                LOGGER.warn("Failed to resolve player identity via USession", t2);
                return;
            }
        }

        String finalUuid = uuid.toString();
        String finalUsername = username;
        PrometheusRelayClient.connect(RELAY_URL, finalUuid, finalUsername, server,
            (srv, bytes) -> receiveRelayOutfit(bytes));
    }

    /** Called from mixin when outfit updates are applied locally (own or peer). */
    @SuppressWarnings("unchecked")
    public static void sendOwnOutfitUpdates(java.util.List<?> updates) {
        if (updates == null || updates.isEmpty()) return;
        if (applyingFromRelay.get()) return;

        UUID ownUuid;
        try {
            Object session = gg.essential.util.USession.Companion.activeNow();
            ownUuid = (UUID) session.getClass().getMethod("getUuid").invoke(session);
        } catch (Throwable t) {
            return;
        }

        java.util.List<Object> ownOnly = updates.stream()
            .filter(e -> ownUuid.equals(extractUuid(e)))
            .collect(Collectors.toList());
        if (ownOnly.isEmpty()) {
            LOGGER.debug("Relay: no own updates in batch of {}", updates.size());
            return;
        }

        try {
            io.netty.buffer.ByteBuf buf = Unpooled.buffer();
            @SuppressWarnings("rawtypes")
            java.util.List raw = ownOnly;
            OutfitUpdatesPayload.encode(buf, new OutfitUpdatesPayload(raw));
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            PrometheusRelayClient.send(bytes);
            LOGGER.info("Relay: sent {} own update(s)", ownOnly.size());
        } catch (Throwable t) {
            LOGGER.debug("Failed to send outfit via relay", t);
        }
    }

    private static void receiveRelayOutfit(byte[] bytes) {
        try {
            io.netty.buffer.ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            OutfitUpdatesPayload payload = OutfitUpdatesPayload.decode(buf);
            buf.release();

            if (payload.getUpdates().isEmpty()) {
                LOGGER.debug("Relay: empty payload, ignoring");
                return;
            }

            LOGGER.info("Relay: received {} updates", payload.getUpdates().size());
            for (Object entry : payload.getUpdates()) {
                UUID peerUuid = extractUuid(entry);
                if (peerUuid != null) {
                    LOGGER.info("Relay:   - from peer {}", peerUuid);
                    PrometheusPresence.markPeer(peerUuid);
                }
            }

            applyUpdatesDirect(payload.getUpdates());
        } catch (Throwable t) {
            LOGGER.error("Relay: receive failed", t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyUpdatesDirect(java.util.List<?> updates) {
        applyingFromRelay.set(true);
        try {
            ClassLoader cl = PrometheusPeerNetworking.class.getClassLoader();
            Object mc = Class.forName("net.minecraft.client.Minecraft", false, cl)
                .getMethod("getMinecraft").invoke(null);
            Object connection = mc.getClass().getMethod("getConnection").invoke(mc);
            if (connection == null) {
                LOGGER.warn("Relay: apply failed - no connection");
                return;
            }

            Object manager = connection.getClass()
                .getMethod("getEssential$ingameEquippedOutfitsManager").invoke(connection);
            if (manager == null) {
                LOGGER.warn("Relay: apply failed - no manager");
                return;
            }
            
            LOGGER.info("Relay: applying {} updates via IngameEquippedOutfitsManager", updates.size());
            manager.getClass().getMethod("applyUpdates", java.util.List.class).invoke(manager, updates);
            LOGGER.info("Relay: apply succeeded");
        } catch (Throwable t) {
            LOGGER.error("Relay: apply failed", t);
        } finally {
            applyingFromRelay.set(false);
        }
    }

    /** Extract component1 from kotlin.Pair or getKey from Map.Entry. */
    private static UUID extractUuid(Object obj) {
        if (obj instanceof Map.Entry) {
            return (UUID) ((Map.Entry<?, ?>) obj).getKey();
        }
        try {
            return (UUID) obj.getClass().getMethod("component1").invoke(obj);
        } catch (Exception e) {
            try {
                return (UUID) obj.getClass().getMethod("getFirst").invoke(obj);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
