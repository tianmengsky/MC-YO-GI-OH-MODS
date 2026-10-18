package dev.codex.yugiohmdpro.mixin;

import dev.codex.yugiohfix.client.PuppetDiskRenderer;
import dev.codex.yugiohfix.client.PuppetModel;
import dev.codex.yugiohmdpro.client.PuppetClip;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import com.kiki.yugioh.item.DuelDiskItem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the duel disk visible while the portrait is cropped to its upper body.
 *
 * <p>{@link DuelPuppetsMixin} opens a scissor band around the portrait's mesh and the disk is drawn
 * right after it, inside the same call. Without this hook the disk falls below the band and disappears,
 * which is not what the original mod shows. The glove that follows the disk is drawn with the band
 * already closed, so it needs no hook of its own.</p>
 */
@Mixin(value=PuppetDiskRenderer.class, remap=false)
public abstract class PuppetDiskRendererMixin {
    /**
     * The legacy duel starts from a disk in either hand or the inventory.  The renderer only
     * consulted the offhand, so a normal player and every AI opponent were passed an empty stack.
     * Keep the original offhand priority, then use the same inventory search as DuelManager.
     */
    @Inject(method="offhand", at=@At("HEAD"), cancellable=true, require=0)
    private static void mdpro$inventoryDisk(boolean mine, CallbackInfoReturnable<ItemStack> ci) {
        var player=Minecraft.getInstance().player;
        if(mine && player!=null) {
            if(player.getOffhandItem().getItem() instanceof DuelDiskItem) {ci.setReturnValue(player.getOffhandItem());return;}
            for(ItemStack stack:player.getInventory().items)
                if(!stack.isEmpty() && stack.getItem() instanceof DuelDiskItem) {ci.setReturnValue(stack);return;}
        }
        if(!mine) {
            var id=net.minecraft.resources.ResourceLocation.tryParse(dev.codex.yugiohfix.client.ClientDuelPublicInfo.current().opponentOffHand());
            if(id!=null) {
                var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                if(item instanceof DuelDiskItem) {ci.setReturnValue(new ItemStack(item));return;}
            }
        }
        // AI and temporarily unavailable inventories still wear the original standard disk.
        var fallback=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.tryParse("yugioh_interaction_fix:duel_disk"));
        if(fallback instanceof DuelDiskItem)ci.setReturnValue(new ItemStack(fallback));
    }
    @Inject(method="draw", at=@At("HEAD"), require=0)
    private static void mdpro$keepDuelDisk(GuiGraphics graphics,PuppetModel puppet,Matrix4f[] bones,ItemStack stack,
            float cut,boolean keepAbove,int bustPixels,CallbackInfo ci) {
        PuppetClip.endForAttachments(graphics);
        if(stack.getItem() instanceof DuelDiskItem)PuppetClip.diskDrawn();
    }
}
