package dev.codex.yugiohprojecte.mixin;

import dev.codex.yugiohprojecte.YugiohNBT;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** These are vanilla Slot overrides: include both production SRG and development names. */
@Mixin(targets = {
    "moze_intel.projecte.gameObjs.container.slots.transmutation.SlotInput",
    "moze_intel.projecte.gameObjs.container.slots.transmutation.SlotConsume",
    "moze_intel.projecte.gameObjs.container.slots.transmutation.SlotLock",
    "moze_intel.projecte.gameObjs.container.slots.transmutation.SlotUnlearn"
}, remap = false)
public abstract class TransmutationSlotMixin {
    @Inject(method = "m_5857_", at = @At("HEAD"), cancellable = true)
    private void rejectContents(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (YugiohNBT.isForbiddenContainer(stack)) cir.setReturnValue(false);
    }
}
