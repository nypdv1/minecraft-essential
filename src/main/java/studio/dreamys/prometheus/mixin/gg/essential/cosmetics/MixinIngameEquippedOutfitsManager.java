package studio.dreamys.prometheus.mixin.gg.essential.cosmetics;

import gg.essential.cosmetics.IngameEquippedOutfitsManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.peer.PrometheusPeerNetworking;
import studio.dreamys.prometheus.peer.PrometheusPresence;

import java.util.List;
import java.util.UUID;

@Mixin(value = IngameEquippedOutfitsManager.class, remap = false)
public class MixinIngameEquippedOutfitsManager {

    @Inject(method = "applyUpdates(Ljava/util/List;)V", at = @At("HEAD"), remap = false)
    private void prometheus$onApplyUpdates(List<?> list, CallbackInfo ci) {
        PrometheusPeerNetworking.sendOwnOutfitUpdates(list);
        for (Object entry : list) {
            UUID uuid = extractUuid(entry);
            if (uuid != null) PrometheusPresence.markPeer(uuid);
        }
    }

    @Inject(method = "applyUpdates(Ljava/util/UUID;Ljava/util/List;)V", at = @At("HEAD"), remap = false)
    private void prometheus$markPeer(UUID uuid, List<IngameEquippedOutfitsManager.Update> updates, CallbackInfo ci) {
        PrometheusPresence.markPeer(uuid);
    }

    private static UUID extractUuid(Object obj) {
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
