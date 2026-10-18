package dev.codex.yugiohmdpro.mixin;

import dev.codex.yugiohfix.client.InWorldBoardRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Makes the standing hologram fully opaque.
 *
 * <p>The panel is emitted with vertex alpha 190 ({@code InWorldBoardRenderer.holo}), which is what makes
 * the artwork look like a ghost with the world showing through it. The constant is raised to 255 so the
 * cutout is drawn solid; the texture's own transparent margins still cut the silhouette out.</p>
 */
@Mixin(value=InWorldBoardRenderer.class, remap=false)
public abstract class InWorldBoardRendererMixin {
    @ModifyConstant(method="holo", constant=@Constant(intValue=190), require=0, expect=1)
    private static int mdpro$opaqueHologram(int alpha) {
        return 255;
    }
}
