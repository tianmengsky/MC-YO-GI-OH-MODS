package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.screen.DeckBoxScreen;
import dev.codex.yugiohmdpro.client.DeckEditorLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the reviewed 286x266 panel and its five thin strips with the MDPro3-style editor.
 *
 * <p>The background pass draws the panels, section bands and slot frames; the label pass runs after
 * the container slots, printing section titles, counts and rarity splits. DeckEditorSlotMixin draws
 * each slot once and supplies its actual hit rectangle. Menu transactions stay with vanilla.</p>
 */
@Mixin(value=DeckBoxScreen.class,remap=false)
public abstract class DeckEditorRenderMixin<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    protected DeckEditorRenderMixin(net.minecraft.network.chat.Component title) {super(null,null,title);}

    @Inject(method={"renderBg","m_7286_"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$editorBackground(GuiGraphics graphics,float partialTick,int mouseX,int mouseY,CallbackInfo ci) {
        // Slots are placed here as well as on init: a window or GUI-scale change would otherwise leave
        // them on the previous geometry while the panels and card artwork move to the new one.
        DeckEditorLayout.applyIfChanged(this);
        DeckEditorLayout.of(this.width,this.height).drawBackground(graphics,this.font);
        ci.cancel();
    }

    @Inject(method={"renderLabels","m_280003_"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$editorLabels(GuiGraphics graphics,int mouseX,int mouseY,CallbackInfo ci) {
        Slot hovered=this.getSlotUnderMouse();
        int index=hovered==null?-1:this.getMenu().slots.indexOf(hovered);
        boolean carrying=!this.getMenu().getCarried().isEmpty();
        DeckEditorLayout.of(this.width,this.height).drawForeground(graphics,this.font,this.getMenu().slots,index,carrying);
        ci.cancel();
    }
    @Inject(method={"render","m_88315_"},at=@At("TAIL"),remap=false)
    private void mdpro$tools(GuiGraphics graphics,int mouseX,int mouseY,float tick,CallbackInfo ci) {
        dev.codex.yugiohmdpro.client.DeckEditorControls.render(this,graphics,this.font,mouseX,mouseY,tick);
    }
}
