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
        if (initialized) {
            LOGGER.debug("initClient already ran, skipping");
            return;
        }
        initialized = true;

        LOGGER.info("Peer sync via relay: {}", RELAY_URL);
        LOGGER.info("initClient: starting...");

        String server = "singleplayer";
        UUID uuid;
        String username;
        try {
            // Use context class loader which is the Fabric/Knot classloader that can find Minecraft classes
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            // Fabric intermediary names: class_310 = Minecraft, class_2535 = NetworkManager, class_634 = ClientPlayNetworkHandler
            Class<?> minecraftClass = Class.forName("net.minecraft.class_310", false, cl);
            Object mc = minecraftClass.getMethod("method_1551").invoke(null); // getMinecraft
            Object serverData = mc.getClass().getMethod("getCurrentServerData").invoke(mc);
            if (serverData != null) {
                String ip = (String) serverData.getClass().getField("field_26365").get(serverData); // serverIP
                String name = (String) serverData.getClass().getField("field_26364").get(serverData); // name
                LOGGER.info("ServerData: ip={}, name={}", ip, name);
                // For singleplayer and LAN, use "singleplayer" as group so they sync together
                // For real servers, use the IP
                if ("singleplayer".equals(ip) || ip == null || ip.isEmpty()) {
                    server = "singleplayer";
                } else {
                    server = ip;
                }
            }
            Object s = mc.getClass().getMethod("getSession").invoke(mc);
            Object profile = s.getClass().getMethod("getProfile").invoke(s);
            uuid = (UUID) profile.getClass().getMethod("getId").invoke(profile);
            username = (String) s.getClass().getMethod("getUsername").invoke(s);
        } catch (Throwable t) {
            LOGGER.error("Failed to resolve player identity via Minecraft", t);
            try {
                Object session = gg.essential.util.USession.Companion.activeNow();
                uuid = (UUID) session.getClass().getMethod("getUuid").invoke(session);
                username = (String) session.getClass().getMethod("getUsername").invoke(session);
                LOGGER.info("Resolved identity via USession: {} {}", uuid, username);
            } catch (Throwable t2) {
                LOGGER.error("Failed to resolve player identity via USession", t2);
                return;
            }
        }

        String finalUuid = uuid.toString();
        String finalUsername = username;
        LOGGER.info("initClient: calling PrometheusRelayClient.connect...");
        PrometheusRelayClient.connect(RELAY_URL, finalUuid, finalUsername, server,
            (srv, bytes) -> receiveRelayOutfit(bytes));
        LOGGER.info("initClient: connect() returned");
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
            // Use context class loader which is the Fabric/Knot classloader that can find Minecraft classes
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            // Fabric intermediary names
            Class<?> minecraftClass = Class.forName("net.minecraft.class_310", false, cl);
            Object mc = minecraftClass.getMethod("method_1551").invoke(null); // getMinecraft
            Object connection = mc.getClass().getMethod("method_1562").invoke(mc); // getConnection
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

    /** Broadcast our current outfit to all peers when a new player joins. */
    public static void broadcastCurrentOutfit() {
        try {
            // Get own UUID
            Object session = gg.essential.util.USession.Companion.activeNow();
            UUID ownUuid = (UUID) session.getClass().getMethod("getUuid").invoke(session);
            
            // Get the manager and our current equipped cosmetics
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> minecraftClass = Class.forName("net.minecraft.class_310", false, cl);
            Object mc = minecraftClass.getMethod("method_1551").invoke(null);
            Object connection = mc.getClass().getMethod("method_1562").invoke(mc);
            if (connection == null) return;

            Object manager = connection.getClass()
                .getMethod("getEssential$ingameEquippedOutfitsManager").invoke(connection);
            if (manager == null) return;

            // Get current cosmetics - getEquippedCosmetics(UUID) returns Outfit
            Object outfit = manager.getClass()
                .getMethod("getEquippedCosmetics", java.util.UUID.class)
                .invoke(manager, ownUuid);
            if (outfit == null) {
                LOGGER.debug("Relay: no equipped cosmetics to broadcast");
                return;
            }

            // Build updates from the outfit
            java.util.List<Object> updates = new java.util.ArrayList<>();
            
            // Get cosmetics map: Map<CosmeticSlot, EquippedCosmetic>
            Object cosmetics = outfit.getClass().getMethod("getCosmetics").invoke(outfit);
            if (cosmetics != null) {
                for (Object e : (java.util.Set<?>) ((java.util.Map<?, ?>) cosmetics).entrySet()) {
                    Object slot = e.getClass().getMethod("getKey").invoke(e);
                    Object equippedCosmetic = e.getClass().getMethod("getValue").invoke(e);
                    // EquippedCosmetic has component1() which returns EquippedCosmeticId
                    Object cosmeticId = equippedCosmetic.getClass().getMethod("component1").invoke(equippedCosmetic);
                    // Create Update.Cosmetic via reflection
                    Object updateCosmetic = gg.essential.cosmetics.IngameEquippedOutfitsManager.Update.Cosmetic.class
                        .getConstructor(Object.class, Object.class).newInstance(slot, cosmeticId);
                    updates.add(updateCosmetic);
                }
            }
            
            // Get skin
            Object skin = outfit.getClass().getMethod("getSkin").invoke(outfit);
            if (skin != null) {
                Object updateSkin = gg.essential.cosmetics.IngameEquippedOutfitsManager.Update.Skin.class
                    .getConstructor(Object.class).newInstance(skin);
                updates.add(updateSkin);
            }

            if (!updates.isEmpty()) {
                // Create batch: List<Pair<UUID, List<Update>>>
                Object pair = new kotlin.Pair<>(ownUuid, updates);
                @SuppressWarnings("rawtypes")
                java.util.List batch = java.util.Collections.singletonList(pair);
                
                io.netty.buffer.ByteBuf buf = Unpooled.buffer();
                OutfitUpdatesPayload.encode(buf, new OutfitUpdatesPayload(batch));
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                buf.release();
                PrometheusRelayClient.send(bytes);
                LOGGER.info("Relay: broadcast current outfit ({} cosmetics, skin={})", 
                    updates.stream().filter(u -> u.getClass().getSimpleName().contains("Cosmetic")).count(),
                    skin != null);
            }
        } catch (Throwable t) {
            LOGGER.warn("Relay: broadcastCurrentOutfit failed: {}", t.getMessage());
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
