package dev.codex.yugiohmdpro.mixin;
import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.client.texture.CardArtTextures;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=CardArtTextures.class,remap=false)
public abstract class ImportedCardArtMixin {
    @Inject(method="front",at=@At("HEAD"),cancellable=true)
    private static void mdpro$front(CardDefinition card,CallbackInfoReturnable<ResourceLocation> ci) {
        var data=HistoricalContent.CARDS.get(card.getId());if(data==null)return;
        var custom=new ResourceLocation("yugioh","textures/card/"+card.getId()+".png");
        ci.setReturnValue(CardArtTextures.smooth(custom));
    }
}
