package studio.dreamys.prometheus.peer;

import gg.essential.cosmetics.IngameEquippedOutfitsUpdateDispatcher;
import gg.essential.cosmetics.OutfitUpdatesPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
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

        Minecraft mc = Minecraft.getMinecraft();
        String server = mc.getCurrentServerData() != null
            ? mc.getCurrentServerData().serverIP
            : "singleplayer";

        UUID uuid;
        String username;
        try {
            Object session = gg.essential.util.USession.Companion.activeNow();
            uuid = (UUID) session.getClass().getMethod("getUuid").invoke(session);
            username = (String) session.getClass().getMethod("getUsername").invoke(session);
        } catch (Throwable t) {
            uuid = mc.getSession().getProfile().getId();
            username = mc.getSession().getUsername();
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
            OutfitUpdatesPayload.encode(buf, new OutfitUpdatesPayload(ownOnly));
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

            IngameEquippedOutfitsUpdateDispatcher.sendUpdates(payload.getUpdates());
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
