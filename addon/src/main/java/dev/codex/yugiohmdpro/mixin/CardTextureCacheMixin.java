package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.texture.CardArtTextures;
import dev.codex.yugiohmdpro.client.CardTextureCache;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value=CardArtTextures.class,remap=false)
public abstract class CardTextureCacheMixin {
    @Inject(method="smooth",at=@At("HEAD"),cancellable=true)
    private static void mdpro$budget(ResourceLocation location,CallbackInfoReturnable<ResourceLocation> ci) {
        ci.setReturnValue(CardTextureCache.smooth(location));
    }
    @Inject(method="invalidate",at=@At("HEAD"))
    private static void mdpro$clear(CallbackInfo ci) {
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(mc.isSameThread())CardTextureCache.clear();else mc.execute(CardTextureCache::clear);
    }
}
