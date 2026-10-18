package dev.codex.yugiohmdpro.mixin;
import com.kiki.yugioh.duel.Match;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=Match.class,remap=false)
public interface MatchAccess { @Accessor("over") void mdpro$setOver(boolean over); }
