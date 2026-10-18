package dev.codex.yugiohmdpro.mixin;

import dev.codex.yugiohfix.client.BinderClient;
import dev.codex.yugiohfix.client.BinderFilter;
import dev.codex.yugiohmdpro.client.CardSearchKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The binder page and the deck box only matched the displayed Chinese name and the English name, so a
 * typed passcode, CID or Japanese name found nothing. The same reviewed index the catalog uses now
 * answers here, while the rarity filter keeps deciding which cells are eligible.
 */
@Mixin(value=BinderClient.class,remap=false)
public abstract class BinderSearchMixin {
    @Inject(method="begin",at=@At("TAIL"),remap=false)
    private static void mdpro$resetRace(java.util.UUID screen,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE.clearRace();
    }
    @Inject(method="visibleCells(Ljava/lang/String;)Ljava/util/List;",at=@At("HEAD"),cancellable=true,remap=false)
    private static void mdpro$searchIndex(String needle,CallbackInfoReturnable<List<Integer>> ci) {
        String search=needle==null?"":needle.trim().toLowerCase(Locale.ROOT);
        if(search.isEmpty()&&!(net.minecraft.client.Minecraft.getInstance().screen instanceof com.kiki.yugioh.client.screen.DeckBoxScreen))return;
        List<Integer> cells=new ArrayList<>();
        for(int cell=0;cell<BinderClient.capacity();cell++) {
            ItemStack stack=BinderClient.stackAt(cell);
            if(!BinderFilter.passes(stack))continue;
            if(net.minecraft.client.Minecraft.getInstance().screen instanceof com.kiki.yugioh.client.screen.DeckBoxScreen
                    &&!dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE.passes(stack))continue;
            if(search.isEmpty()||CardSearchKeys.matchesStack(stack,search))cells.add(cell);
        }
        ci.setReturnValue(List.copyOf(cells));
    }
}
