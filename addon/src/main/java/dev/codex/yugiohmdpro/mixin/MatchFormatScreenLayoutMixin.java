package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.screen.MatchFormatScreen;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Folds the interaction-fix "private voice room" switch out of the match-format screen.
 *
 * <p>That mod draws five extra rows below the match-format options: the star wager row at
 * {@code options.size()} and four switches at {@code options.size()+1 .. +4} (cancel surrender,
 * free move, private voice room, training). The row list is hard-coded in its mixin, so the addon
 * cannot simply drop one row. Instead every row after the voice-room row is laid out one pitch
 * higher: the training row lands on the voice-room row and, because the fix mixin draws the
 * training row after the voice-room row, only the training row remains visible. The voice room
 * itself is already unreachable ({@link VoiceRoomMixin} cancels the room, {@code ClientDuelRulesMixin}
 * pins the rule off), so nothing is lost.</p>
 *
 * <p>{@link FormatPromptExtraMixin} removes the same 23 px from the panel height, and
 * {@code ClientDuelRulesMixin} routes a click on the folded row to the training switch.</p>
 */
@Mixin(value=MatchFormatScreen.class, remap=false)
public abstract class MatchFormatScreenLayoutMixin {
    @Shadow @Final private List<Integer> options;

    @Inject(method="rowTop", at=@At("RETURN"), cancellable=true, require=0)
    private void mdpro$foldVoiceRoomRow(int index, CallbackInfoReturnable<Integer> cir) {
        int voiceRow = this.options.size() + 3;
        if (index > voiceRow) cir.setReturnValue(cir.getReturnValue() - (index - voiceRow) * 23);
    }
}
