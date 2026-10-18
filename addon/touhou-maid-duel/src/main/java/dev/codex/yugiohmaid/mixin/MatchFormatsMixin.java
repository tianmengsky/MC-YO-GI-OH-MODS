package dev.codex.yugiohmaid.mixin;

import com.kiki.yugioh.duel.DuelManager;
import com.kiki.yugioh.duel.MatchFormats;
import dev.codex.yugiohmaid.MaidDuelHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MatchFormats.class, remap = false)
public abstract class MatchFormatsMixin {
    @Inject(method = {"requestSolo", "requestTable"}, at = @At("HEAD"))
    private void maidDuel$newLocationOffer(BlockPos pos, ServerPlayer host, CallbackInfo ci) {
        MaidDuelHooks.cancelOffer(host);
    }

    @Inject(method = "nudge", at = @At("HEAD"))
    private void maidDuel$newTableOffer(BlockPos pos, ServerPlayer host, ServerPlayer opponent, CallbackInfo ci) {
        MaidDuelHooks.cancelOffer(host);
    }

    @Inject(method = "requestChallenge", at = @At("HEAD"))
    private void maidDuel$newChallenge(ServerPlayer host, ServerPlayer target, CallbackInfo ci) {
        MaidDuelHooks.cancelOffer(host);
    }

    @Redirect(method = "choose", at = @At(value = "INVOKE", target =
            "Lcom/kiki/yugioh/duel/DuelManager;startMatch(Lnet/minecraft/core/BlockPos;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerPlayer;I)Lnet/minecraft/network/chat/Component;"))
    private Component maidDuel$confirmedSolo(DuelManager manager, BlockPos pos, ServerPlayer host, ServerPlayer second, int games) {
        return MaidDuelHooks.startSelected(manager, pos, host, second, games);
    }
}
