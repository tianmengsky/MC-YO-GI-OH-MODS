package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.item.*;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=DeckContents.class,remap=false)
public abstract class HistoricalDeckCopiesMixin {
    @Inject(method="countCopies",at=@At("HEAD"),cancellable=true)
    private static void mdpro$count(ItemStackHandler handler,CardDefinition definition,CallbackInfoReturnable<Integer> ci) {
        if(definition==null)return;
        String family=HistoricalContent.copyFamily(definition);int total=0;
        for(int i=0;i<handler.getSlots();i++) {
            var stack=handler.getStackInSlot(i);var def=CardItem.definitionOf(stack);
            if(def!=null&&HistoricalContent.copyFamily(def).equals(family))total+=stack.getCount();
        }
        ci.setReturnValue(total);
    }
    @Inject(method="validate",at=@At("RETURN"),cancellable=true)
    private static void mdpro$validate(ItemStackHandler handler,CallbackInfoReturnable<Component> ci) {
        if(ci.getReturnValue()!=null)return;
        Map<String,Integer> copies=new HashMap<>();
        for(int i=0;i<handler.getSlots();i++) {
            var stack=handler.getStackInSlot(i);var def=CardItem.definitionOf(stack);
            if(def!=null&&copies.merge(HistoricalContent.copyFamily(def),stack.getCount(),Integer::sum)>DeckContents.maxCopies()) {
                ci.setReturnValue(Component.translatable("yugioh.deck.too_many_copies",Component.translatable("item.yugioh."+def.getId()),DeckContents.maxCopies()));return;
            }
        }
        var cards=new ArrayList<CardDefinition>();
        for(int i=0;i<handler.getSlots();i++) {
            var stack=handler.getStackInSlot(i);var def=CardItem.definitionOf(stack);
            if(def!=null)for(int n=0;n<stack.getCount();n++)cards.add(def);
        }
        var restriction=dev.codex.yugiohmdpro.rules.ServerBanlist.validate(cards);
        if(restriction!=null)ci.setReturnValue(restriction);
    }
}
