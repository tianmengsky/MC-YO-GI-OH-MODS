package dev.codex.yugiohmaid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.kiki.yugioh.duel.DuelManager;
import com.kiki.yugioh.item.DeckContents;
import com.kiki.yugioh.item.DuelDiskItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** All state is accessed on the server thread. Pending offers never affect unrelated matches. */
public final class MaidDuelHooks {
    private record Offer(MaidDuelSession session, BlockPos pos, ResourceKey<Level> dimension, int deadline) {}
    private static final Map<UUID, Offer> OFFERS = new HashMap<>();
    private static final Map<UUID, MaidDuelSession> STARTING = new HashMap<>();
    private static final Map<UUID, MaidDuelSession> ACTIVE = new HashMap<>();
    private static final Map<UUID, MaidDuelPositionState> POSITIONS = new HashMap<>();

    private MaidDuelHooks() {}

    public static void offer(ServerPlayer player, MaidDuelSession session, BlockPos pos) {
        OFFERS.put(player.getUUID(), new Offer(session, pos.immutable(), player.level().dimension(), player.server.getTickCount() + 1200));
    }

    public static void cancelOffer(ServerPlayer player) { OFFERS.remove(player.getUUID()); }

    public static void expireOffers(int tick) { OFFERS.values().removeIf(offer -> tick > offer.deadline()); }

    /** Called only for the SOLO branch of the original format confirmation. */
    public static Component startSelected(DuelManager manager, BlockPos pos, ServerPlayer player, ServerPlayer second, int games) {
        Offer offer = OFFERS.remove(player.getUUID());
        if (offer == null) return manager.startMatch(pos, player, second, games);
        if (second != null || !offer.pos().equals(pos) || !offer.dimension().equals(player.level().dimension())
                || player.server.getTickCount() > offer.deadline()) {
            return Component.literal("女仆决斗邀请已失效，请重新右键女仆。");
        }
        var entity = player.serverLevel().getEntity(offer.session().maidId());
        if (!(entity instanceof EntityMaid maid) || !maid.isAlive() || !maid.isOwnedBy(player)
                || player.isSpectator() || player.distanceToSqr(maid) > 256
                || !(maid.getOffhandItem().getItem() instanceof DuelDiskItem) || maid.isPassenger() || maid.isVehicle()) {
            return Component.literal("女仆已离开、正在骑乘或未装备决斗盘，请重新发起决斗。");
        }
        if (maidBusy(maid.getUUID()) || manager.isDueling(player.getUUID())) {
            return Component.literal("玩家或女仆已经在决斗中。");
        }
        var selection = MaidDeckSelection.find(maid.getAvailableBackpackInv());
        if (selection.refusal() != null) return selection.refusal();
        var handler = DeckContents.handlerFor(selection.box());
        Component invalid = DeckContents.validate(handler);
        if (invalid != null) return Component.literal("女仆的卡组盒无效：").append(invalid);
        MaidDuelSession session = new MaidDuelSession(player.getUUID(), maid.getUUID(),
                maid.getName().getString(), maid.getModelId(), maid.isYsmModel(), maid.getYsmModelId(),
                maid.getYsmModelTexture(), maid.getYsmModelName().getString(), maid.getOffhandItem(),
                DeckContents.mainDeck(handler), DeckContents.extraDeck(handler));
        MaidDuelPositionState state = MaidDuelPositionState.capture(maid, player);
        if (state == null) return Component.literal("女仆附近没有安全站位，请移至空旷地面后重试。");
        POSITIONS.put(player.getUUID(), state);
        STARTING.put(player.getUUID(), session);
        boolean success = false;
        try {
            state.freeze();
            Component failure = manager.startMatch(pos, player, null, games);
            var duel = manager.duelOf(player.getUUID());
            success = failure == null && duel != null && !duel.isFinished();
            return failure != null ? failure : success ? null : Component.literal("决斗未能启动，请检查卡组和服务器日志。");
        } finally {
            STARTING.remove(player.getUUID());
            if (!success) clear(player.getUUID());
        }
    }

    public static MaidDuelSession take(ServerPlayer player) {
        MaidDuelSession session = STARTING.remove(player.getUUID());
        if (session != null) ACTIVE.put(player.getUUID(), session);
        return session;
    }

    public static MaidDuelSession active(UUID challenger) { return ACTIVE.get(challenger); }

    public static boolean maidBusy(UUID maid) {
        return ACTIVE.values().stream().anyMatch(session -> session.maidId().equals(maid));
    }

    public static boolean protects(UUID maid) {
        return POSITIONS.values().stream().anyMatch(state -> state.maidId().equals(maid));
    }

    public static boolean maintainPosition(UUID challenger, ServerPlayer player) {
        MaidDuelPositionState state = POSITIONS.get(challenger);
        return state != null && state.maintain(player);
    }

    public static void clear(UUID challenger) {
        OFFERS.remove(challenger);
        STARTING.remove(challenger);
        ACTIVE.remove(challenger);
        MaidDuelPositionState position = POSITIONS.remove(challenger);
        if (position != null) position.restore();
    }

    public static void clearAll() {
        for (UUID challenger : java.util.List.copyOf(POSITIONS.keySet())) clear(challenger);
        OFFERS.clear();
        STARTING.clear();
        ACTIVE.clear();
    }
}
