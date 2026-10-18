package dev.codex.yugiohmaid;

import com.kiki.yugioh.duel.DuelManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TouhouMaidDuel.MOD_ID)
public final class MaidDuelLifecycle {
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        MaidDuelHooks.expireOffers(event.getServer().getTickCount());
        for (var player : event.getServer().getPlayerList().getPlayers()) {
            var active = MaidDuelHooks.active(player.getUUID());
            if (active == null) continue;
            var manager = DuelManager.get(event.getServer());
            var match = manager.matchOf(player.getUUID());
            if (match == null || match.isOver()) {
                MaidDuelHooks.clear(player.getUUID());
            } else if (!MaidDuelHooks.maintainPosition(player.getUUID(), player)) {
                // Stop the whole match if keeping the entity safe is no longer possible.
                manager.onPlayerLeave(player);
                MaidDuelHooks.clear(player.getUUID());
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("女仆站位失效，已安全结束决斗。"));
            }
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        MaidDuelHooks.clear(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void stopped(net.minecraftforge.event.server.ServerStoppingEvent event) {
        MaidDuelHooks.clearAll();
    }

    @SubscribeEvent
    public static void joined(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof EntityMaid maid
                && !MaidDuelHooks.protects(maid.getUUID())) MaidDuelPositionState.recover(maid);
    }

    @SubscribeEvent
    public static void leaving(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof EntityMaid maid
                && MaidDuelHooks.protects(maid.getUUID())) MaidDuelPositionState.recover(maid);
    }

    @SubscribeEvent
    public static void suffocation(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if (event.getEntity() instanceof EntityMaid maid && !maid.level().isClientSide
                && event.getSource().is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)
                && MaidDuelHooks.protects(maid.getUUID())) event.setCanceled(true);
    }
}
