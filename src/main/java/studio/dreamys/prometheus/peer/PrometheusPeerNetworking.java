package studio.dreamys.prometheus.peer;

import gg.essential.cosmetics.IngameEquippedOutfitsUpdateDispatcher;
import gg.essential.cosmetics.OutfitUpdatesPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PrometheusPeerNetworking {
    private static final Logger LOGGER = LogManager.getLogger("Prometheus");
    private static boolean initialized;

    private PrometheusPeerNetworking() {}

    public static void initClient() {
        if (initialized) return;
        initialized = true;

        String url = System.getProperty("prometheus.relay.url");
        if (url == null || url.isEmpty()) url = "wss://prometheus-relay.up.railway.app";
        LOGGER.info("Peer sync via relay: {}", url);

        String server = "singleplayer";
        UUID uuid;
        String username;
        try {
            Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getMinecraft").invoke(null);
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
            } catch (Throwable t2) {
                LOGGER.warn("Failed to resolve player identity via USession", t2);
                return;
            }
        }

        String finalUuid = uuid.toString();
        String finalUsername = username;
        PrometheusRelayClient.connect(finalUuid, finalUsername, server,
            (srv, bytes) -> receiveRelayOutfit(bytes));
    }

    /** Called from mixin when outfit updates are dispatched. */
    @SuppressWarnings("unchecked")
    public static void sendOwnOutfitUpdates(java.util.List<?> updates) {
        if (updates == null || updates.isEmpty()) return;

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
        if (ownOnly.isEmpty()) return;

        try {
            io.netty.buffer.ByteBuf buf = Unpooled.buffer();
            @SuppressWarnings("rawtypes")
            java.util.List raw = ownOnly;
            OutfitUpdatesPayload.encode(buf, new OutfitUpdatesPayload(raw));
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            PrometheusRelayClient.send(bytes);
        } catch (Throwable t) {
            LOGGER.debug("Failed to send outfit via relay", t);
        }
    }

    private static void receiveRelayOutfit(byte[] bytes) {
        try {
            io.netty.buffer.ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            OutfitUpdatesPayload payload = OutfitUpdatesPayload.decode(buf);
            buf.release();

            if (payload.getUpdates().isEmpty()) return;

            for (Object entry : payload.getUpdates()) {
                UUID peerUuid = extractUuid(entry);
                if (peerUuid != null) PrometheusPresence.markPeer(peerUuid);
            }

            IngameEquippedOutfitsUpdateDispatcher.Companion.sendUpdates(payload.getUpdates());
        } catch (Throwable t) {
            LOGGER.debug("Failed to apply relay outfit", t);
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
