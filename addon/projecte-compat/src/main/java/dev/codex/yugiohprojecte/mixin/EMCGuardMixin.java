package dev.codex.yugiohprojecte.mixin;

import com.kiki.yugioh.item.DeckBoxItem;
import dev.codex.yugiohprojecte.*;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.emc.nbt.NBTManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Guard before ProjectE's no-NBT fast path and independently of configurable NBT processors. */
@Mixin(value = NBTManager.class, remap = false)
public abstract class EMCGuardMixin {
    @Inject(method = "getEmcValue", at = @At("HEAD"), cancellable = true)
    private static void rejectContents(ItemInfo info, CallbackInfoReturnable<Long> cir) {
        if ((info.getItem() instanceof DeckBoxItem || EmcValues.glove(info.getItem()))
                && YugiohNBT.isForbiddenContainer(info.createStack())) cir.setReturnValue(0L);
    }
}
