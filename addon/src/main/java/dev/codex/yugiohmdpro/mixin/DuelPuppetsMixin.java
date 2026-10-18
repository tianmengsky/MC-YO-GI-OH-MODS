package dev.codex.yugiohmdpro.mixin;

import dev.codex.yugiohfix.client.DuelPuppets;
import dev.codex.yugiohfix.client.PuppetModel;
import dev.codex.yugiohmdpro.client.PuppetClip;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows only the upper body of both duel portraits.
 *
 * <p>The portraits are 3D models drawn by the interaction-fix mod, and its own bust clipping does not
 * take effect in the shipped build: the whole body including the legs is painted. Each portrait is
 * therefore hard-clipped here to the band that ends at its own life-point plate, which is exactly the
 * lower edge of the plate the portrait stands behind:</p>
 *
 * <ul>
 *   <li>opponent, top right: everything below {@code y = 138} is dropped;</li>
 *   <li>own, bottom left: everything below {@code virtualHeight - 30} is dropped.</li>
 * </ul>
 *
 * <p>A scissor is used rather than the mod's own clip maths because the scissor also applies to the
 * duel disk and glove attachments and cannot be defeated by a model that is taller than expected. The
 * band is closed again by {@link PuppetDiskRendererMixin} once the body has been emitted, so those
 * attachments keep being drawn.</p>
 */
@Mixin(value=DuelPuppets.class, remap=false)
public abstract class DuelPuppetsMixin {
    /** Lower edge of the opponent plate, in the puppets' virtual coordinate space. */
    private static final int OPPONENT_BAND=138;
    /** Distance from the bottom of the virtual screen to the lower edge of the own plate. */
    private static final int OWN_BOTTOM_MARGIN=30;

    @Inject(method="drawOne", at=@At("HEAD"), require=0)
    private static void mdpro$clipBust(GuiGraphics graphics,PuppetModel model,PuppetModel.Clip clip,String clipName,
            double seconds,boolean looping,boolean mine,int x,int width,int cut,boolean keepAbove,int bustPixels,
            float scale,int virtualWidth,int virtualHeight,ItemStack offhand,CallbackInfo ci) {
        int band=mine?Math.max(40,virtualHeight-OWN_BOTTOM_MARGIN):OPPONENT_BAND;
        PuppetClip.begin(graphics,virtualWidth,scale,band);
    }

    @Inject(method="drawOne", at=@At("RETURN"), require=0)
    private static void mdpro$clipBustEnd(GuiGraphics graphics,PuppetModel model,PuppetModel.Clip clip,String clipName,
            double seconds,boolean looping,boolean mine,int x,int width,int cut,boolean keepAbove,int bustPixels,
            float scale,int virtualWidth,int virtualHeight,ItemStack offhand,CallbackInfo ci) {
        PuppetClip.end(graphics);
    }
}
