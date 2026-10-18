package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.client.screen.CardCatalogScreen;
import dev.codex.yugiohfix.mixin.CardItemAccess;
import dev.codex.yugiohmdpro.client.CatalogDetails;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Catalog hover: rules text fallback, passcode, first release date and containing products. */
@Mixin(value=CardCatalogScreen.class,remap=false,priority=1500)
public abstract class CatalogTooltipMixin {
    @Inject(method="tooltipOf",at=@At("RETURN"),remap=false)
    private void mdpro$details(CardDefinition card,CallbackInfoReturnable<List<Component>> ci) {
        List<Component> lines=ci.getReturnValue();
        if(card==null||lines==null)return;
        String text=Component.translatable(card.descriptionKey()).getString();
        if(!text.isBlank()&&lines.stream().noneMatch(line->line.getString().contains(probe(text)))) {
            lines.add(Component.empty());
            lines.addAll(CardItemAccess.fix$wrap(text));
        }
        lines.add(Component.empty());
        lines.addAll(CatalogDetails.lines(card.getId(),Screen.hasShiftDown()));
    }
    /** The review package already appends the same rules text; match its opening characters. */
    private static String probe(String text) {return text.substring(0,Math.min(12,text.length()));}
}
