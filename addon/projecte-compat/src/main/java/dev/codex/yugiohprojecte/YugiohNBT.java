package dev.codex.yugiohprojecte;

import com.kiki.yugioh.item.DeckBoxItem;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.nbt.INBTProcessor;
import moze_intel.projecte.api.nbt.NBTProcessor;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

@NBTProcessor(requiredMods = {"yugioh_projecte"})
public final class YugiohNBT implements INBTProcessor {
    public static final String CAPS = "YugiohProjectEForgeCaps";
    @Override public String getName() { return "YugiohItemData"; }
    @Override public String getDescription() { return "Preserve card rarity and empty glove data; filled containers cannot be transmuted."; }
    @Override public boolean hasPersistentNBT() { return true; }
    @Override public boolean usePersistentNBT() { return true; }
    @Override public CompoundTag getPersistentNBT(ItemInfo info) {
        return EmcValues.target(info.getItem()) ? info.getNBT() : null;
    }
    @Override public long recalculateEMC(ItemInfo info, long current) {
        return current;
    }
    public static boolean isForbiddenContainer(ItemStack stack) {
        if (stack.getItem() instanceof DeckBoxItem) {
            var tag = stack.getTag();
            if (tag != null && tag.contains("Deck")) {
                if (!tag.contains("Deck", Tag.TAG_COMPOUND)) return true;
                var deck = tag.getCompound("Deck");
                if (deck.contains("Items") && !deck.contains("Items", Tag.TAG_LIST)) return true;
                // ItemStackHandler writes nonempty slots only, with the item ID beside Slot.
                // Block all stored entries, including invalid or unknown legacy items.
                if (!deck.getList("Items", Tag.TAG_COMPOUND).isEmpty()) return true;
            }
        } else if (EmcValues.glove(stack.getItem())) {
            var inventory = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (inventory == null) return true;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                var child = inventory.getStackInSlot(slot);
                if (child.isEmpty()) continue;
                return true;
            }
        }
        return false;
    }
}
