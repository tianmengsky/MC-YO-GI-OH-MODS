package dev.codex.yugiohmdpro.mixin;

import dev.codex.yugiohfix.client.Hologram3DPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the embedded legacy renderer on 2D artwork, including when an external
 * resource pack is enabled. The addon renderer no longer has a monster mesh path.
 * Duel disks use their independent DuelDiskPack and retain their 3D attachments.
 */
@Mixin(value=Hologram3DPack.class, remap=false)
public abstract class Hologram3DPackMixin {
    @Inject(method="of", at=@At("HEAD"), cancellable=true, require=0)
    private static void mdpro$only2d(String cardId, CallbackInfoReturnable<Hologram3DPack.Entry> cir) {
        cir.setReturnValue(null);
    }
}
