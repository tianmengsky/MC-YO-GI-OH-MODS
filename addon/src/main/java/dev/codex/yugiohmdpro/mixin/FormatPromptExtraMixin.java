package dev.codex.yugiohmdpro.mixin;

import dev.codex.yugiohfix.client.FormatPromptExtra;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reserves one row less for the match-format screen's extra rows.
 *
 * <p>{@link MatchFormatScreenLayoutMixin} folds the private voice room switch away, so the panel and
 * its footer move up by the 23 px row pitch instead of leaving a blank row above the footer.</p>
 */
@Mixin(value=FormatPromptExtra.class, remap=false)
public abstract class FormatPromptExtraMixin {
    @Inject(method="panelHeight", at=@At("RETURN"), cancellable=true, require=0)
    private static void mdpro$foldedRowHeight(int base, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(cir.getReturnValue() - 23);
    }
}
