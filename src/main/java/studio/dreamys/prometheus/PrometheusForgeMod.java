package studio.dreamys.prometheus;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PrometheusForgeMod {
    private static final Logger LOGGER = LogManager.getLogger("Prometheus");

    // Forge/NeoForge entrypoint - called when the mod initializes on Forge
    public void init() {
        LOGGER.info("Prometheus: Forge init called");
        try {
            Class<?> clazz = Class.forName("studio.dreamys.prometheus.peer.PrometheusPeerNetworking");
            clazz.getMethod("initClient").invoke(null);
            LOGGER.info("Prometheus: Forge init complete");
        } catch (Exception e) {
            LOGGER.error("Prometheus: Forge init failed", e);
        }
    }
}