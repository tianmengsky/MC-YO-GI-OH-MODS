package dev.codex.yugiohmaid.mixin;

import dev.codex.yugiohfix.client.DuelPuppets;
import dev.codex.yugiohfix.client.PuppetModel;
import dev.codex.yugiohmaid.client.MaidPortraitRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = DuelPuppets.class, remap = false)
public abstract class MaidPuppetMixin {
    @Shadow private static int drawnThisFrame;
    @Shadow private static void drawOne(GuiGraphics g, PuppetModel model, PuppetModel.Clip clip,
            String clipName, double seconds, boolean looping, boolean mine, int x, int width, int cut,
            boolean keepAbove, int bustPixels, float scale, int virtualWidth, int virtualHeight, ItemStack offhand) {
        throw new AssertionError();
    }

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Ldev/codex/yugiohfix/client/DuelPuppets;drawOne(Lnet/minecraft/client/gui/GuiGraphics;Ldev/codex/yugiohfix/client/PuppetModel;Ldev/codex/yugiohfix/client/PuppetModel$Clip;Ljava/lang/String;DZZIIIZIFIILnet/minecraft/world/item/ItemStack;)V"))
    private static void maidDuel$portrait(GuiGraphics g, PuppetModel model, PuppetModel.Clip clip,
            String clipName, double seconds, boolean looping, boolean mine, int x, int width, int cut,
            boolean keepAbove, int bustPixels, float scale, int virtualWidth, int virtualHeight, ItemStack offhand) {
        if (!mine && MaidPortraitRenderer.draw(g, x, width, scale, virtualWidth, model, clip, seconds, looping)) {
            drawnThisFrame++;
            return;
        }
        drawOne(g, model, clip, clipName, seconds, looping, mine, x, width, cut, keepAbove,
                bustPixels, scale, virtualWidth, virtualHeight, offhand);
    }
}

