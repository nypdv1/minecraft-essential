package studio.dreamys.prometheus.mixin.gg.essential.network.connectionmanager.cosmetics;

import gg.essential.gui.elementa.state.v2.State;
import gg.essential.mod.Skin;
import gg.essential.mod.cosmetics.CosmeticOutfit;
import gg.essential.mod.cosmetics.CosmeticSlot;
import gg.essential.mod.cosmetics.settings.CosmeticSetting;
import gg.essential.network.connectionmanager.cosmetics.InfraEquippedOutfitsManager;
import gg.essential.network.connectionmanager.cosmetics.OutfitManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(value = OutfitManager.class, remap = false)
public class MixinOutfitManager {

    @Shadow @Final private State<String> selectedOutfitId;

    @Shadow @Final private State<List<CosmeticOutfit>> outfits;

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lgg/essential/network/connectionmanager/cosmetics/InfraEquippedOutfitsManager;update(Ljava/util/UUID;Lgg/essential/network/connectionmanager/cosmetics/InfraEquippedOutfitsManager$InfraOutfit;)V"
        ),
        index = 1,
        remap = false
    )
    private InfraEquippedOutfitsManager.InfraOutfit prometheus$syncFullEquippedOutfit(
        InfraEquippedOutfitsManager.InfraOutfit outfit
    ) {
        String selected = selectedOutfitId.getUntracked();
        if (selected == null) return outfit;

        CosmeticOutfit current = null;
        for (CosmeticOutfit o : outfits.getUntracked()) {
            if (selected.equals(o.getId())) {
                current = o;
                break;
            }
        }
        if (current == null) return outfit;

        Map<CosmeticSlot, String> infraCosmetics = new HashMap<>(current.getEquippedCosmetics());
        Map<String, List<CosmeticSetting>> settings = new HashMap<>();
        for (Map.Entry<String, List<CosmeticSetting>> entry : current.getCosmeticSettings().entrySet()) {
            if (infraCosmetics.containsValue(entry.getKey())) {
                settings.put(entry.getKey(), entry.getValue());
            }
        }

        Skin skin = current.getSkin();
        return new InfraEquippedOutfitsManager.InfraOutfit(infraCosmetics, settings, skin);
    }
}
