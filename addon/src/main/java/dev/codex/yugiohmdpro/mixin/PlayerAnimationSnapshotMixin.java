package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.duel.Duel;
import dev.codex.yugiohfix.duel.ServerPlayerAnimations;
import dev.codex.yugiohfix.sound.DuelSoundTracker;
import dev.codex.yugiohmdpro.duel.DuelBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=ServerPlayerAnimations.class,remap=false)
public abstract class PlayerAnimationSnapshotMixin {
    @Inject(method="snapshot",at=@At("HEAD"),cancellable=true)
    private static void mdpro$state(Duel duel,CallbackInfoReturnable<DuelSoundTracker.Snapshot> ci) {
        if(DuelBridge.active(duel))ci.setReturnValue(DuelBridge.animationSnapshot(duel));
    }
}
