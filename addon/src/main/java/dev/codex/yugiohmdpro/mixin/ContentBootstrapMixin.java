package dev.codex.yugiohmdpro.mixin;
import com.kiki.yugioh.registry.ModItems;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ModItems.class,remap=false)
public abstract class ContentBootstrapMixin {
    @Inject(method="<clinit>",at=@At(value="INVOKE",target="Lcom/kiki/yugioh/card/Cards;bootstrap()V",shift=At.Shift.AFTER))
    private static void mdpro$content(CallbackInfo ci){HistoricalContent.bootstrap();}
}
