package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.duel.Duel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A cancelled match bypasses the legacy draw announcement. */
@Mixin(value=Duel.class,remap=false)
public interface DuelLifecycleAccess {
    @Accessor("finished") void mdpro$finished(boolean finished);
    @Accessor("winner") void mdpro$winner(int winner);
}
