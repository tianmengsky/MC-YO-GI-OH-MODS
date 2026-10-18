package dev.codex.yugiohmdpro.mixin;

import dev.codex.yugiohmdpro.content.HistoricalContent;
import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Original and fix language files repeat these keys across namespaces, so the reused legacy pack
 * items needed a deterministic value. It is now the reviewed Chinese product name alone: the item
 * name must match the product name the catalog prints, with no language marker appended.
 */
@Mixin(value=ClientLanguage.class,remap=false)
public abstract class JapaneseProductLanguageMixin {
    // Explicit dev/SRG selectors: this project does not generate a vanilla refmap.
    @Inject(method={"getOrDefault","m_118919_"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$productName(String key,String fallback,CallbackInfoReturnable<String> ci) {
        String race=switch(key) {
            case "yugioh.monster_type.psychic" -> "念动力";
            case "yugioh.monster_type.creator_god" -> "创造神";
            case "yugioh.monster_type.wyrm" -> "幻龙";
            default -> null;
        };
        if(race!=null) {ci.setReturnValue(race);return;}
        if(!key.startsWith("item.yugioh."))return;
        var product=HistoricalContent.LEGACY_PRODUCTS.get("yugioh:"+key.substring("item.yugioh.".length()));
        if(product!=null)ci.setReturnValue(HistoricalContent.str(product,"name"));
    }
}
