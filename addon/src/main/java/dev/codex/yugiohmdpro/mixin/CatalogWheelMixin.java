package dev.codex.yugiohmdpro.mixin;
import com.kiki.yugioh.client.screen.CardCatalogScreen;
import dev.codex.yugiohfix.client.BinderWheel;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** The new catalog screen owns its adaptive scrollbar instead of the frozen 10-column event hook. */
@Mixin(value=BinderWheel.class,remap=false)
public abstract class CatalogWheelMixin {
    @Inject(method="page",at=@At("HEAD"),cancellable=true,remap=false)
    private static void mdpro$page(Screen s,CallbackInfoReturnable<Object> ci) {
        if(s instanceof CardCatalogScreen)ci.setReturnValue(null);
    }
}
