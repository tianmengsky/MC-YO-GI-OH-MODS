package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.texture.CardArtTextures;
import dev.codex.yugiohmdpro.client.CardBackTextures;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives the item/list renderer the replacement card back.
 *
 * <p>{@code CardArtTextures.back()} is the only reader of the original {@code BACK} constant, so
 * answering it here covers the card list, the item renderer and the zoom panel in one place. See
 * {@link dev.codex.yugiohmdpro.client.CardBackTextures} for the artwork and why the lookup is
 * redirected instead of overriding the resource path.</p>
 */
@Mixin(value=CardArtTextures.class, remap=false)
public abstract class CardArtTexturesMixin {
    @Inject(method="back", at=@At("HEAD"), cancellable=true, require=0)
    private static void mdpro$cardBack(CallbackInfoReturnable<ResourceLocation> cir) {
        cir.setReturnValue(CardArtTextures.smooth(CardBackTextures.CARD_BACK));
    }
}
