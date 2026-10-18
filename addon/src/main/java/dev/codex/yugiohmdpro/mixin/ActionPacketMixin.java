package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.network.C2SDuelActionPacket;
import dev.codex.yugiohmdpro.duel.DuelBridge;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=C2SDuelActionPacket.class,remap=false,priority=2000)
public abstract class ActionPacketMixin {
    @Inject(method="handle",at=@At("HEAD"),cancellable=true)
    private void mdpro$replace(Supplier<NetworkEvent.Context> supplier,CallbackInfo ci) {
        var ctx=supplier.get();
        ctx.enqueueWork(() -> DuelBridge.legacyAction(ctx.getSender(),(C2SDuelActionPacket)(Object)this));
        ctx.setPacketHandled(true); ci.cancel();
    }
}
