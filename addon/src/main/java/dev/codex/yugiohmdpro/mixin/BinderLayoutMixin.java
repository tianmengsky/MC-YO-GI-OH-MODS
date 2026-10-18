package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.screen.DeckBoxScreen;
import dev.codex.yugiohfix.client.BinderView;
import dev.codex.yugiohmdpro.client.DeckBinderLayout;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the frozen binder session/search lifecycle; the deck screen owns its new rendering/input. */
@Mixin(value=BinderView.class,remap=false)
public abstract class BinderLayoutMixin {
    @Inject(method="panels",at=@At("HEAD"),cancellable=true,remap=false)
    private static void mdpro$panels(int width,CallbackInfoReturnable<BinderView.Panels> ci) {
        if(Minecraft.getInstance().screen instanceof DeckBoxScreen)
            ci.setReturnValue(new BinderView.Panels(0,0,0,0,0,0));
    }
    @Inject(method="searchField",at=@At("HEAD"),cancellable=true,remap=false)
    private static void mdpro$search(BinderView.Panels panels,int height,CallbackInfoReturnable<BinderView.Field> ci) {
        if(Minecraft.getInstance().screen instanceof DeckBoxScreen screen)
            ci.setReturnValue(DeckBinderLayout.listSearchField(DeckBinderLayout.panels(screen.width),height));
    }
    @Inject(method={"drawBand","drawRarityRow"},at=@At("HEAD"),cancellable=true,remap=false)
    private static void mdpro$catalogBand(net.minecraft.client.gui.GuiGraphics g,net.minecraft.client.gui.Font font,int left,int top,int x,int y,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if(Minecraft.getInstance().screen instanceof com.kiki.yugioh.client.screen.CardCatalogScreen)ci.cancel();
    }
}

