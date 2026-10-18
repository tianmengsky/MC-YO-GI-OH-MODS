package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.client.screen.DuelScreen;
import com.kiki.yugioh.network.CardView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;

@Mixin(value=DuelScreen.class,remap=false)
public interface DuelScreenAccess {
    @Accessor("left") int mdpro$left();
    @Accessor("top") int mdpro$top();
    @Accessor("boardH") int mdpro$boardH();
    @Accessor("scale") float mdpro$scale();
    @Accessor("scale") void mdpro$scale(float value);
    @Accessor("left") void mdpro$left(int value);
    @Accessor("top") void mdpro$top(int value);
    @Accessor("selectedHand") void mdpro$hand(int value);
    @Accessor("selectedMonster") void mdpro$monster(int value);
    @Accessor("selectedSpell") void mdpro$spell(int value);
    @Invoker("drawCard") void mdpro$drawCard(GuiGraphics g,int x,int y,int w,int h,CardView card);
    @Invoker("showTooltip") void mdpro$tooltip(GuiGraphics g,CardView card,int x,int y);
    /** The item whose tooltip carries the card's full text; used for the native card tooltip. */
    @Invoker("stackFor") static ItemStack mdpro$stackFor(CardDefinition definition) {throw new AssertionError();}
}
