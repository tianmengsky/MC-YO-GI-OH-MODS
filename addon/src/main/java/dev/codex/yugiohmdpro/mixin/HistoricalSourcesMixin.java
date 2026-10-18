package dev.codex.yugiohmdpro.mixin;
import dev.codex.yugiohfix.catalog.CardSources;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=CardSources.class,remap=false)
public abstract class HistoricalSourcesMixin {
    @Inject(method="of",at=@At("RETURN"),cancellable=true)
    private static void mdpro$legacySources(String id,CallbackInfoReturnable<java.util.List<CardSources.Source>> ci) {
        ci.setReturnValue(ci.getReturnValue().stream().filter(s->HistoricalContent.LEGACY_PRODUCTS.containsKey("yugioh:"+(s.deck()?"structure_deck_":"booster_")+s.id())).toList());
    }
    @Inject(method="text",at=@At("HEAD"),cancellable=true)
    private static void mdpro$sources(String id,String exchange,CallbackInfoReturnable<String> ci) {
        if(!HistoricalContent.CARDS.containsKey(id))return;
        // The catalog now prints every containing product itself, with its release date, so the old
        // six-name "日版收录" summary is dropped. The operator exchange hint is kept.
        ci.setReturnValue(exchange);
    }
}
