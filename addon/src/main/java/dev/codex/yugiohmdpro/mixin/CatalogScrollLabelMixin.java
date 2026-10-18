package dev.codex.yugiohmdpro.mixin;

import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The original catalog label reads "第 %s 页 / 共 %s 页", but both numbers are row windows, not pages:
 * with 8,231 cards and three visible rows it claimed 822 pages instead of 275 screens. The label now
 * names what it counts and mentions the paging keys. Overriding the key here is deterministic, unlike
 * relying on resource-pack order for a namespace that the original jar also provides.
 */
@Mixin(value=ClientLanguage.class,remap=false)
public abstract class CatalogScrollLabelMixin {
    private static final String KEY="yugioh.gui.list_scroll";

    // Explicit dev/SRG selectors: this project does not generate a vanilla refmap.
    @Inject(method={"getOrDefault","m_118919_"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$scrollLabel(String key,String fallback,CallbackInfoReturnable<String> ci) {
        if(KEY.equals(key))ci.setReturnValue("滚动位置 %s / %s · 滚轮或 Shift 翻页");
    }
}
