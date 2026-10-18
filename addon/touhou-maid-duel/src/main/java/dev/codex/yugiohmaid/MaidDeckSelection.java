package dev.codex.yugiohmaid;

import com.kiki.yugioh.item.DeckBoxItem;
import com.kiki.yugioh.item.DeckContents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

/** Select the first prepared, legal box in the visible backpack slot order. */
public record MaidDeckSelection(int slot, ItemStack box, Component refusal) {
    public static MaidDeckSelection find(IItemHandler inventory) {
        Component firstError = null;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof DeckBoxItem)) continue;
            ItemStack copy = stack.copy();
            Component error = DeckContents.validate(DeckContents.handlerFor(copy));
            if (error == null) return new MaidDeckSelection(slot, copy, null);
            if (firstError == null) firstError = Component.literal("女仆的卡组盒尚未准备好：").append(error);
        }
        return new MaidDeckSelection(-1, ItemStack.EMPTY, firstError == null
                ? Component.literal("女仆背包中没有卡组盒。") : firstError);
    }
}
