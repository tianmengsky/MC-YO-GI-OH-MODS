package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.screen.DeckBoxScreen;
import dev.codex.yugiohmdpro.client.BinderListActions;
import dev.codex.yugiohmdpro.client.DeckBinderLayout;
import dev.codex.yugiohfix.client.BinderView;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;

/** Handles clear in the deck screen before the inherited binder consumes the filter column. */
@Mixin(value=DeckBoxScreen.class,remap=false)
public abstract class DeckEditorClickMixin<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    protected DeckEditorClickMixin(T menu,net.minecraft.world.entity.player.Inventory inventory,Component title) {super(menu,inventory,title);}

    @org.spongepowered.asm.mixin.Unique private boolean mdpro$controlClick;

    @Override public boolean mouseClicked(double x,double y,int button) {
        mdpro$controlClick=dev.codex.yugiohmdpro.client.DeckEditorControls.click(this,this.font,x,y,button);
        if(mdpro$controlClick)return true;
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta) {
        if(dev.codex.yugiohmdpro.client.DeckEditorControls.wheel(this,x,y,delta))return true;
        return super.mouseScrolled(x,y,delta);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(dev.codex.yugiohmdpro.client.DeckEditorControls.drag(this,x,y))return true;
        if(mdpro$controlClick)return true;
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        boolean handled=dev.codex.yugiohmdpro.client.DeckEditorControls.release()|mdpro$controlClick;
        mdpro$controlClick=false;
        if(handled)return true;
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==256&&dev.codex.yugiohmdpro.client.DeckEditorLayout.filtersOpen) {
            dev.codex.yugiohmdpro.client.DeckEditorLayout.filtersOpen=false;return true;
        }
        if(dev.codex.yugiohmdpro.client.DeckEditorLayout.filtersOpen)return true;
        return super.keyPressed(key,scan,modifiers);
    }
}
