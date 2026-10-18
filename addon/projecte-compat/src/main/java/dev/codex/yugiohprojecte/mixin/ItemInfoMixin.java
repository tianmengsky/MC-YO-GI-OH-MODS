package dev.codex.yugiohprojecte.mixin;

import dev.codex.yugiohprojecte.EmcValues;
import dev.codex.yugiohprojecte.YugiohNBT;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ProjectE ItemInfo normally serializes only vanilla tag, omitting Forge capability inventories. */
@Mixin(value = ItemInfo.class, remap = false)
public abstract class ItemInfoMixin {
    @Inject(method = "fromStack", at = @At("RETURN"), cancellable = true)
    private static void retainGloveInventory(ItemStack stack, CallbackInfoReturnable<ItemInfo> cir) {
        if (!EmcValues.glove(stack.getItem())) return;
        CompoundTag serialized = stack.save(new CompoundTag());
        if (!serialized.contains("ForgeCaps", Tag.TAG_COMPOUND)) return;
        CompoundTag tag = stack.hasTag() ? stack.getTag().copy() : new CompoundTag();
        tag.remove("Inventory"); // This is the glove's network mirror, not its authoritative inventory.
        tag.put(YugiohNBT.CAPS, serialized.getCompound("ForgeCaps").copy());
        cir.setReturnValue(ItemInfo.fromItem(stack.getItem(), tag));
    }
    @Inject(method = "createStack", at = @At("RETURN"), cancellable = true)
    private void restoreGloveInventory(CallbackInfoReturnable<ItemStack> cir) {
        ItemInfo info = (ItemInfo) (Object) this;
        if (!EmcValues.glove(info.getItem())) return;
        CompoundTag tag = info.getNBT();
        if (tag == null || !tag.contains(YugiohNBT.CAPS, Tag.TAG_COMPOUND)) return;
        CompoundTag serialized = cir.getReturnValue().save(new CompoundTag());
        serialized.put("ForgeCaps", tag.getCompound(YugiohNBT.CAPS).copy());
        tag.remove(YugiohNBT.CAPS);
        serialized.put("tag", tag);
        cir.setReturnValue(ItemStack.of(serialized));
    }
}
