package dev.codex.yugiohmaid.mixin;

import com.kiki.yugioh.duel.Match;
import com.kiki.yugioh.duel.Duel;
import dev.codex.yugiohmaid.MaidDuelNetwork;
import dev.codex.yugiohmaid.MaidDuelHooks;
import dev.codex.yugiohmaid.MaidDuelSession;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = Match.class, remap = false)
public abstract class MatchStartMixin {
    @Shadow @Final private UUID[] players;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private com.kiki.yugioh.duel.DuelManager manager;
    @Shadow @Final private String[] names;
    @Shadow @Final private boolean solo;
    @Unique private MaidDuelSession touhouMaidDuel$session;

    @Inject(method = "startGame", at = @At("HEAD"))
    private void touhouMaidDuel$captureSession(CallbackInfoReturnable<Component> cir) {
        if (solo && touhouMaidDuel$session == null) {
            ServerPlayer player = server.getPlayerList().getPlayer(players[0]);
            if (player != null) {
                touhouMaidDuel$session = MaidDuelHooks.take(player);
            }
            if (touhouMaidDuel$session != null) {
                names[1] = touhouMaidDuel$session.maidName();
            }
        }
    }

    @Inject(method = "startGame", at = @At("RETURN"))
    private void touhouMaidDuel$finishStart(CallbackInfoReturnable<Component> cir) {
        MaidDuelSession session = touhouMaidDuel$session;
        if (session == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(players[0]);
        if (cir.getReturnValue() != null || player == null) {
            MaidDuelHooks.clear(players[0]);
            return;
        }
        Duel duel = manager.duelOf(players[0]);
        if (duel != null) MaidDuelNetwork.sendPortrait(player, duel.getSide(1).playerId, session);
    }

    @ModifyArg(method = "startGame", at = @At(value = "INVOKE",
            target = "Lcom/kiki/yugioh/duel/DuelSide;<init>(Ljava/util/UUID;Ljava/lang/String;Ljava/util/List;Ljava/util/List;)V",
            ordinal = 1), index = 1)
    private String touhouMaidDuel$replaceAiName(String original) {
        MaidDuelSession session = touhouMaidDuel$session;
        return session == null ? original : session.maidName();
    }

    @ModifyArg(method = "startGame", at = @At(value = "INVOKE",
            target = "Lcom/kiki/yugioh/duel/DuelSide;<init>(Ljava/util/UUID;Ljava/lang/String;Ljava/util/List;Ljava/util/List;)V",
            ordinal = 1), index = 2)
    private java.util.List<?> touhouMaidDuel$replaceAiMainDeck(java.util.List<?> original) {
        MaidDuelSession session = touhouMaidDuel$session;
        return session == null ? original : session.mainDeck();
    }

    @ModifyArg(method = "startGame", at = @At(value = "INVOKE",
            target = "Lcom/kiki/yugioh/duel/DuelSide;<init>(Ljava/util/UUID;Ljava/lang/String;Ljava/util/List;Ljava/util/List;)V",
            ordinal = 1), index = 3)
    private java.util.List<?> touhouMaidDuel$replaceAiExtraDeck(java.util.List<?> original) {
        MaidDuelSession session = touhouMaidDuel$session;
        return session == null ? original : session.extraDeck();
    }

}
