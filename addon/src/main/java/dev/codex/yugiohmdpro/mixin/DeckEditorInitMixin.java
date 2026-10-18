package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.screen.DeckBoxScreen;
import dev.codex.yugiohmdpro.client.DeckEditorLayout;
import dev.codex.yugiohmdpro.client.ScreenFields;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Places the reviewed deck box menu on the MDPro3-style editor layout.
 *
 * <p>The menu, its 90 deck slots and the NBT save format stay exactly as they were; only the slot
 * rectangles move, onto the left deck panel and the bottom inventory block. The screen's origin is
 * set to 0,0 so the layout can work in absolute screen coordinates. Priority 1500 keeps this after
 * the reviewed binder mixin, which also adjusts the window on init.</p>
 */
@Mixin(value=AbstractContainerScreen.class,remap=false,priority=1500)
public abstract class DeckEditorInitMixin<T extends AbstractContainerMenu> extends Screen {
    protected DeckEditorInitMixin(net.minecraft.network.chat.Component title) {super(title);}

    @Inject(method={"init","m_7856_"},at=@At("RETURN"),remap=false)
    private void mdpro$layoutSlots(CallbackInfo ci) {
        if(!((Object)this instanceof DeckBoxScreen))return;
        AbstractContainerScreen<T> screen=(AbstractContainerScreen<T>)(Object)this;
        DeckEditorLayout.open(screen);
        ScreenFields.absoluteOrigin(screen);
        DeckEditorLayout.of(this.width,this.height).apply(screen.getMenu().slots);
    }
}
