package dev.codex.yugiohmaid.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import dev.codex.yugiohfix.client.ClientDuelPublicInfo;
import dev.codex.yugiohfix.client.PuppetModel;
import dev.codex.yugiohfix.client.PuppetPose;
import dev.codex.yugiohfix.client.PuppetAnimationTrack;
import dev.codex.yugiohmaid.MaidPortraitClient;
import dev.codex.yugiohmaid.MaidPortraitPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "touhou_maid_duel", value = Dist.CLIENT)
public final class MaidPortraitRenderer {
    private static EntityMaid portrait;
    private static MaidPortraitPacket appearance;
    private static int drawn;
    public static int drawn() { return drawn; }

    public static boolean draw(GuiGraphics g, int x, int width, float scale, int virtualWidth,
                               PuppetModel source, PuppetModel.Clip clip, double seconds, boolean looping) {
        var mc = Minecraft.getInstance();
        var data = MaidPortraitClient.current();
        var duelInfo = ClientDuelPublicInfo.current();
        if (mc.level == null || data == null || duelInfo == null || clip == null || source == null
                || !MaidPortraitClient.matches(duelInfo.opponentId())) return false;
        if (portrait == null || portrait.level() != mc.level || appearance != data) {
            portrait = EntityMaid.TYPE.create(mc.level);
            if (portrait == null) return false;
            portrait.setModelId(data.modelId());
            portrait.setCustomName(Component.literal(data.maidName()));
            if (data.ysmModel()) portrait.setYsmModel(data.ysmModelId(), data.ysmTexture(), Component.literal(data.ysmName()));
            portrait.setIsYsmModel(data.ysmModel());
            // The native opponent holds the disk in the left (offhand) hand as well.  Keep the
            // portrait's offhand disk; the left-arm pose below is what decides whether it ends up
            // in front of the chest or at the neck.
            portrait.setItemSlot(EquipmentSlot.OFFHAND, data.offhand().copy());
            portrait.handItemsForAnimation[1] = data.offhand().copy();
            portrait.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            portrait.handItemsForAnimation[0] = ItemStack.EMPTY;
            appearance = data;
        }
        // A detached entity never joins the world, runs AI, or changes the challenged maid.
        portrait.tickCount = mc.player == null ? 0 : mc.player.tickCount;
        float modelScale = 1.0f;
        try {
            modelScale = CustomPackLoader.MAID_MODELS.getModelRenderItemScale(data.modelId());
        } catch (RuntimeException ignored) {
            // A client may not have the challenged maid's custom pack. Keep the default
            // portrait size while the normal TLM fallback model is rendered.
        }
        if (!Float.isFinite(modelScale) || modelScale <= 0.0f) modelScale = 1.0f;
        int pixels = Math.max(1, Math.round(105 * modelScale));
        g.enableScissor(Math.round(x * scale), 0, Math.round((x + width) * scale), Math.round(138 * scale));
        try (var animation = MaidDuelAnimation.open(portrait, source,
                PuppetPose.capture(source, clip, seconds, looping),
                PuppetAnimationTrack.IDLE.equals(clip.name))) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + width / 2, 229, pixels, 0, 0, portrait);
            drawn++;
        } finally {
            g.disableScissor();
        }
        return true;
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        portrait = null; appearance = null; MaidPortraitClient.clear();
    }
}
