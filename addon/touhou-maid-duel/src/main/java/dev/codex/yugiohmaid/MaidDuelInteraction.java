package dev.codex.yugiohmaid;

import com.github.tartaricacid.touhoulittlemaid.api.event.InteractMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.kiki.yugioh.duel.DuelManager;
import com.kiki.yugioh.item.DeckContents;
import com.kiki.yugioh.item.DuelDiskItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;

@Mod.EventBusSubscriber(modid = TouhouMaidDuel.MOD_ID)
public final class MaidDuelInteraction {
    private MaidDuelInteraction() {}

    @SubscribeEvent
    public static void onMaidInteract(InteractMaidEvent event) {
        if (!(event.getStack().getItem() instanceof DuelDiskItem)) return;
        EntityMaid maid = event.getMaid();
        event.setCanceled(true);
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(maid.getOffhandItem().getItem() instanceof DuelDiskItem)) {
            tell(player, "请先在女仆副手装备决斗盘。");
            return;
        }

        if (player.isSpectator() || !maid.isOwnedBy(player)) {
            tell(player, "女仆必须由你驯服，且决斗发起者不能处于旁观模式。");
            return;
        }
        if (DuelManager.get(player.server).isDueling(player.getUUID())
                || DuelManager.get(player.server).matchOf(player.getUUID()) != null) {
            tell(player, "你当前已经在决斗中。");
            return;
        }

        if (MaidDuelHooks.maidBusy(maid.getUUID())) {
            tell(player, "这位女仆已经在决斗中。");
            return;
        }
        var selection = MaidDeckSelection.find(maid.getAvailableBackpackInv());
        ItemStack box = selection.box();
        if (selection.refusal() != null) {
            player.sendSystemMessage(selection.refusal().copy().withStyle(ChatFormatting.YELLOW));
            return;
        }

        var handler = DeckContents.handlerFor(box);
        Component invalid = DeckContents.validate(handler);
        if (invalid != null) {
            player.sendSystemMessage(Component.literal("女仆的卡组盒无效：").withStyle(ChatFormatting.RED).append(invalid));
            return;
        }

        var main = new ArrayList<>(DeckContents.mainDeck(handler));
        var extra = new ArrayList<>(DeckContents.extraDeck(handler));
        if (main.size() < DeckContents.minMainCards() || main.size() > DeckContents.maxMainCards()
                || extra.size() > DeckContents.maxExtraCards()) {
            tell(player, "女仆的卡组数量不符合决斗规则。");
            return;
        }

        MaidDuelSession session = new MaidDuelSession(player.getUUID(), maid.getUUID(),
                maid.getName().getString(), maid.getModelId(), maid.isYsmModel(), maid.getYsmModelId(),
                maid.getYsmModelTexture(), maid.getYsmModelName().getString(), maid.getOffhandItem(), main, extra);
        DuelManager.get(player.server).formats().requestSolo(maid.blockPosition(), player);
        MaidDuelHooks.offer(player, session, maid.blockPosition());
    }

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.YELLOW));
    }
}
