package dev.codex.yugiohmdpro.mixin;

import dev.codex.yugiohfix.client.ClientDuelRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pins the "private voice room" duel rule to off and sets the rules the match-format screen starts
 * with.
 *
 * <p>The rule defaults to on in the interaction-fix mod, which is what made every duel request a
 * voice room. With the rule forced off the row shows 关, the value sent to the server stays false,
 * and together with {@link VoiceRoomMixin} the feature has no reachable code path.</p>
 *
 * <p>The screen starts with cancel surrender off (surrender allowed) and free move on, matching
 * {@link DuelRulesOffersMixin}, which supplies the same combination when the player never clicks a
 * switch.</p>
 */
@Mixin(value=ClientDuelRules.class, remap=false)
public abstract class ClientDuelRulesMixin {
    @Shadow private static boolean requestedCancelSurrender;
    @Shadow private static boolean requestedFreeMove;

    @Inject(method="privateVoiceRoom", at=@At("HEAD"), cancellable=true, require=0)
    private static void mdpro$voiceRoomOff(CallbackInfoReturnable<Boolean> cir) { cir.setReturnValue(false); }

    @Inject(method="flipPrivateVoiceRoom", at=@At("HEAD"), cancellable=true, require=0)
    private static void mdpro$voiceRoomRowFlipsTraining(CallbackInfo ci) {
        // MatchFormatScreenLayoutMixin folds the voice-room row onto the training row, so the switch
        // drawn in the voice-room slot is the training switch; a click there must toggle training.
        ClientDuelRules.flipTraining();
        ci.cancel();
    }

    @Inject(method="resetForPrompt", at=@At("TAIL"), require=0)
    private static void mdpro$ruleDefaults(CallbackInfo ci) {
        requestedCancelSurrender = false;
        requestedFreeMove = true;
    }
}
