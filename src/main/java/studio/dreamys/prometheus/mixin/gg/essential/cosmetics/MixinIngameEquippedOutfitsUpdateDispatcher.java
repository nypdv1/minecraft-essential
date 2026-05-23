package studio.dreamys.prometheus.mixin.gg.essential.cosmetics;

import gg.essential.cosmetics.IngameEquippedOutfitsManager;
import gg.essential.cosmetics.IngameEquippedOutfitsUpdateDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.peer.PrometheusPeerNetworking;

import java.util.List;
import java.util.UUID;

@Mixin(value = IngameEquippedOutfitsUpdateDispatcher.class, remap = false)
public class MixinIngameEquippedOutfitsUpdateDispatcher {

    @Inject(
        method = "sendUpdates(Lio/netty/channel/Channel;Ljava/util/List;)V",
        at = @At("RETURN"),
        remap = false
    )
    private static void prometheus$sendPeerOutfits(
        List<java.util.Map.Entry<UUID, List<IngameEquippedOutfitsManager.Update>>> updates,
        CallbackInfo ci
    ) {
        PrometheusPeerNetworking.sendOwnOutfitUpdates(updates);
    }
}
