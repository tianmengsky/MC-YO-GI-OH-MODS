package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.duel.Match;
import dev.codex.yugiohfix.duel.DuelRules;
import dev.codex.yugiohfix.duel.DuelRulesOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives a match whose initiator never clicked a rule switch the defaults the match-format screen now
 * shows.
 *
 * <p>The interaction-fix mod only stores a player's rules when a switch is clicked. A player who
 * opens the screen and picks a format immediately therefore lands on {@code DuelRules.Rules.DEFAULT}
 * ("no surrender, fixed position"), which contradicts the screen. The addon's defaults are cancel
 * surrender off, free move on, no private voice room and no training; its GameTests already use the
 * same combination.</p>
 */
@Mixin(value=DuelRulesOffers.class, remap=false)
public abstract class DuelRulesOffersMixin {
    @Inject(method="rulesFor", at=@At("RETURN"), cancellable=true, require=0)
    private static void mdpro$screenDefaults(Match match, CallbackInfoReturnable<DuelRules.Rules> cir) {
        if (cir.getReturnValue() == DuelRules.Rules.DEFAULT) cir.setReturnValue(new DuelRules.Rules(false, true, false, false));
    }
}
