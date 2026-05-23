package studio.dreamys.prometheus.mixin.gg.essential.cosmetics;

import gg.essential.cosmetics.IngameEquippedOutfitsManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.peer.PrometheusPresence;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mixin(value = IngameEquippedOutfitsManager.class, remap = false)
public class MixinIngameEquippedOutfitsManager {

    @Inject(method = "applyUpdates(Ljava/util/List;)V", at = @At("HEAD"), remap = false)
    private void prometheus$markPeersFromBatch(List<Map.Entry<UUID, List<IngameEquippedOutfitsManager.Update>>> list, CallbackInfo ci) {
        for (Map.Entry<UUID, List<IngameEquippedOutfitsManager.Update>> entry : list) {
            PrometheusPresence.markPeer(entry.getKey());
        }
    }

    @Inject(method = "applyUpdates(Ljava/util/UUID;Ljava/util/List;)V", at = @At("HEAD"), remap = false)
    private void prometheus$markPeer(UUID uuid, List<IngameEquippedOutfitsManager.Update> updates, CallbackInfo ci) {
        PrometheusPresence.markPeer(uuid);
    }
}
