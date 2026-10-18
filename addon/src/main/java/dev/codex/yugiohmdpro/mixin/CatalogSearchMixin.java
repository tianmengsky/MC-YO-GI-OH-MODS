package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.client.screen.CardCatalogScreen;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import java.util.Locale;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The original catalog only searches the Chinese and English names. The same haystack now also
 * carries the passcode, CID, Japanese name and registration id, so a typed passcode finds its card.
 */
@Mixin(value=CardCatalogScreen.class,remap=false)
public abstract class CatalogSearchMixin {
    @Shadow @Final private Map<String,String> haystacks;
    @Unique private boolean mdpro$keysAdded;

    @Inject(method="refresh",at=@At("RETURN"),remap=false)
    private void mdpro$searchKeys(CallbackInfo ci) {
        if(mdpro$keysAdded)return;
        mdpro$keysAdded=true;
        for(CardDefinition definition:((CatalogAccess)(Object)this).mdpro$sorted())addKeys(definition);
    }

    @Unique private void addKeys(CardDefinition definition) {
        String id=definition.getId();
        var row=HistoricalContent.CARDS.get(id);
        StringBuilder extra=new StringBuilder();
        if(row!=null) {
            int code=HistoricalContent.num(row,"code");
            extra.append(String.format("%08d",code)).append(' ').append(code).append(' ')
                 .append(HistoricalContent.num(row,"cid")).append(' ')
                 .append(HistoricalContent.str(row,"name_en")).append(' ')
                 .append(HistoricalContent.str(row,"name_ja"));
        }
        extra.append(' ').append(id);
        this.haystacks.merge(id,extra.toString().toLowerCase(Locale.ROOT),(existing,added)->existing+" "+added);
    }
}
