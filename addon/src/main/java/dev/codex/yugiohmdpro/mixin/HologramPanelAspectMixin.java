package dev.codex.yugiohmdpro.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The frozen board layout also builds the standing cutout panel one half-height tall while it is a
 * full hologram width wide, so every standing artwork was squashed vertically to half its height.
 * It was invisible while monsters still used 3D meshes; the released 0.6.13 draws every monster with
 * this panel. The corrected geometry lives in the addon so both renderers share one implementation.
 */
@Mixin(value=dev.codex.yugiohfix.board.BoardLayout.class,remap=false)
public abstract class HologramPanelAspectMixin {
    @Inject(method="hologramCorners",at=@At("RETURN"),cancellable=true,remap=false)
    private static void mdpro$artworkAspect(double centerX,double centerY,double centerZ,double towardX,double towardZ,
            double artAspect,double lift,CallbackInfoReturnable<float[][]> ci) {
        ci.setReturnValue(dev.codex.yugiohmdpro.visual.board.BoardLayout.hologramCorners(
                centerX,centerY,centerZ,towardX,towardZ,artAspect,lift));
    }
}
