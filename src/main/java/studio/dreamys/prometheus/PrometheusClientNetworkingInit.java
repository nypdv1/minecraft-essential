package studio.dreamys.prometheus;

import net.fabricmc.api.ClientModInitializer;
import studio.dreamys.prometheus.peer.PrometheusPeerNetworking;

public class PrometheusClientNetworkingInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PrometheusPeerNetworking.initClient();
    }
}
