package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.screen.DeckBoxScreen;
import dev.codex.yugiohmdpro.client.DeckEditorLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps vanilla click, split and drag transactions, replacing only slot geometry and painting. */
@Mixin(value=AbstractContainerScreen.class,remap=false)
public abstract class DeckEditorSlotMixin {
    @Redirect(method={"render","m_88315_"},at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderSlotHighlight(Lnet/minecraft/client/gui/GuiGraphics;IIII)V"),require=0,remap=false)
    private void mdpro$highlight(GuiGraphics graphics,int x,int y,int z,int color) {
        if(!((Object)this instanceof DeckBoxScreen))AbstractContainerScreen.renderSlotHighlight(graphics,x,y,z,color);
    }
    @Inject(method={"isHovering(Lnet/minecraft/world/inventory/Slot;DD)Z","m_97774_"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$slotHit(Slot slot,double x,double y,CallbackInfoReturnable<Boolean> ci) {
        if(!((Object)this instanceof DeckBoxScreen screen))return;
        ci.setReturnValue(DeckEditorLayout.of(screen.width,screen.height).contains(slot.index,x,y));
    }

    @Group(name="deckItem",min=1,max=1)
    @Redirect(method={"renderSlot","m_280092_"},at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;renderItem(Lnet/minecraft/world/item/ItemStack;III)V"),require=0,remap=false)
    private void mdpro$itemDev(GuiGraphics graphics,ItemStack stack,int x,int y,int seed,GuiGraphics original,Slot slot) {
        mdpro$item(graphics,stack,x,y,seed,slot);
    }

    @Group(name="deckItem",min=1,max=1)
    @Redirect(method={"renderSlot","m_280092_"},at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;m_280256_(Lnet/minecraft/world/item/ItemStack;III)V"),require=0,remap=false)
    private void mdpro$itemSrg(GuiGraphics graphics,ItemStack stack,int x,int y,int seed,GuiGraphics original,Slot slot) {
        mdpro$item(graphics,stack,x,y,seed,slot);
    }

    private void mdpro$item(GuiGraphics graphics,ItemStack stack,int x,int y,int seed,Slot slot) {
        if((Object)this instanceof DeckBoxScreen screen)DeckEditorLayout.of(screen.width,screen.height).drawSlotItem(graphics,net.minecraft.client.Minecraft.getInstance().font,slot,stack);
        else graphics.renderItem(stack,x,y,seed);
    }

    @Group(name="deckCount",min=1,max=1)
    @Redirect(method={"renderSlot","m_280092_"},at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"),require=0,remap=false)
    private void mdpro$countDev(GuiGraphics graphics,Font font,ItemStack stack,int x,int y,String text,GuiGraphics original,Slot slot) {
        mdpro$count(graphics,font,stack,x,y,text,slot);
    }

    @Group(name="deckCount",min=1,max=1)
    @Redirect(method={"renderSlot","m_280092_"},at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;m_280302_(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"),require=0,remap=false)
    private void mdpro$countSrg(GuiGraphics graphics,Font font,ItemStack stack,int x,int y,String text,GuiGraphics original,Slot slot) {
        mdpro$count(graphics,font,stack,x,y,text,slot);
    }

    private void mdpro$count(GuiGraphics graphics,Font font,ItemStack stack,int x,int y,String text,Slot slot) {
        if((Object)this instanceof DeckBoxScreen screen)DeckEditorLayout.of(screen.width,screen.height).drawSlotCount(graphics,font,slot,stack,text);
        else graphics.renderItemDecorations(font,stack,x,y,text);
    }
}
