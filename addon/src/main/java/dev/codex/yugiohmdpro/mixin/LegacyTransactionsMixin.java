package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.duel.Duel;
import dev.codex.yugiohfix.engine.DuelTransactions;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Legacy extra/normal/grave packets must never execute a second rules engine. */
@Mixin(value=DuelTransactions.class,remap=false)
public abstract class LegacyTransactionsMixin {
    @Inject(method="run",at=@At("HEAD"),cancellable=true)
    private static void mdpro$block(Duel duel,Runnable action,CallbackInfo ci) {ci.cancel();}
    @Inject(method="answer",at=@At("HEAD"),cancellable=true)
    private static void mdpro$answer(Duel duel,ServerPlayer sender,long id,int[] selected,CallbackInfo ci) {ci.cancel();}
}
