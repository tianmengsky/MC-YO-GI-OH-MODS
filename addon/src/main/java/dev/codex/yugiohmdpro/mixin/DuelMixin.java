package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.duel.Duel;
import dev.codex.yugiohmdpro.duel.DuelBridge;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=Duel.class, remap=false, priority=2000)
public abstract class DuelMixin implements dev.codex.yugiohfix.engine.DuelState.State {
    @Unique private final java.util.Map<String,Object> mdpro$featureData=new java.util.HashMap<>();
    public java.util.Map<String,Object> fix$data() {return mdpro$featureData;}
    @Shadow @Final private MinecraftServer server;
    @Inject(method="start(I)V",at=@At("HEAD"),cancellable=true)
    private void mdpro$start(int starter,CallbackInfo ci) {
        DuelBridge.start((Duel)(Object)this,server,starter); ci.cancel();
    }
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void mdpro$tick(CallbackInfo ci) { DuelBridge.tick((Duel)(Object)this); ci.cancel(); }
    @Inject(method="sync",at=@At("HEAD"),cancellable=true)
    private void mdpro$sync(CallbackInfo ci) { DuelBridge.sync((Duel)(Object)this); ci.cancel(); }
    @Inject(method="endDuel",at=@At("HEAD"))
    private void mdpro$finalState(int winner,net.minecraft.network.chat.Component reason,CallbackInfo ci) {
        DuelBridge.beforeEnd((Duel)(Object)this);
    }
    @Inject(method="endDuel",at=@At("TAIL"))
    private void mdpro$end(int winner,net.minecraft.network.chat.Component reason,CallbackInfo ci) {
        DuelBridge.end((Duel)(Object)this,reason.getString());
    }
}
