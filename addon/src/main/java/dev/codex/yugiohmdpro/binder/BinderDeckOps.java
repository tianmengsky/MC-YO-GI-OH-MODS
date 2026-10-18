package dev.codex.yugiohmdpro.binder;

import com.kiki.yugioh.item.CardItem;
import com.kiki.yugioh.menu.DeckBoxMenu;
import dev.codex.yugiohfix.binder.CardBinder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.LoggerFactory;

/**
 * Server-authoritative single-card moves between the card binder and the deck box.
 *
 * <p>The reviewed interaction fix moves whole binder stacks into the deck and has no operation in the
 * other direction, so both behaviours are implemented here on the server: one clicked card adds exactly
 * one card, and one right-clicked deck slot returns exactly one card to the binder. Everything is
 * validated against the server's own state (the open menu, the binder cell contents, the slot rules of
 * the deck box), so a client cannot use these messages to duplicate or destroy cards.</p>
 */
public final class BinderDeckOps {
    private BinderDeckOps() {}

    /** Number of deck slots at the start of the menu, before the player inventory. */
    private static final int DECK_SLOTS=90;

    /** Moves exactly one card from a binder cell into the deck box. */
    public static void addOne(ServerPlayer player,int cell,ItemStack expected) {
        if(player==null)return;
        try {
            if(!(player.containerMenu instanceof DeckBoxMenu menu))return;
            CardBinder binder=CardBinder.get(player.getServer());
            var id=player.getUUID();
            ItemStack held=binder.at(id,cell);
            if(held.isEmpty()||!ItemStack.isSameItemSameTags(held,expected))return;
            if(CardItem.definitionOf(held)==null)return;
            if(!placeOne(menu,held))return;
            ItemStack rest=held.copyWithCount(held.getCount()-1);
            if(rest.isEmpty())binder.remove(id,cell); else binder.put(id,cell,rest);
            sync(player,menu);
        }catch(Throwable failed) {
            LoggerFactory.getLogger("MDProBinderDeck").warn("Adding one card failed",failed);
        }
    }

    /** Moves exactly one card from a deck slot back into the binder. */
    public static void returnOne(ServerPlayer player,int slotIndex) {
        if(player==null)return;
        try {
            if(!(player.containerMenu instanceof DeckBoxMenu menu))return;
            if(slotIndex<0||slotIndex>=DECK_SLOTS||slotIndex>=menu.slots.size())return;
            Slot slot=menu.slots.get(slotIndex);
            ItemStack inSlot=slot.getItem();
            if(inSlot.isEmpty()||CardItem.definitionOf(inSlot)==null)return;
            CardBinder binder=CardBinder.get(player.getServer());
            ItemStack one=inSlot.copyWithCount(1);
            if(!binder.insert(player.getUUID(),one))return; // binder full: nothing changes
            ItemStack rest=inSlot.copyWithCount(inSlot.getCount()-1);
            slot.set(rest);
            slot.setChanged();
            sync(player,menu);
        }catch(Throwable failed) {
            LoggerFactory.getLogger("MDProBinderDeck").warn("Returning one card failed",failed);
        }
    }

    /**
     * Places a single card, using the deck box's own slot rules: a matching slot with room first, then
     * the first empty slot that accepts the card. Returns false when the deck cannot take it.
     */
    private static boolean placeOne(DeckBoxMenu menu,ItemStack card) {
        ItemStack one=card.copyWithCount(1);
        for(int index=0;index<DECK_SLOTS&&index<menu.slots.size();index++) {
            Slot slot=menu.slots.get(index);
            ItemStack present=slot.getItem();
            if(present.isEmpty())continue;
            if(!ItemStack.isSameItemSameTags(present,one))continue;
            if(present.getCount()>=slot.getMaxStackSize())continue;
            present.grow(1);
            slot.setChanged();
            return true;
        }
        for(int index=0;index<DECK_SLOTS&&index<menu.slots.size();index++) {
            Slot slot=menu.slots.get(index);
            if(!slot.getItem().isEmpty())continue;
            if(!slot.mayPlace(one))continue;
            slot.set(one);
            slot.setChanged();
            return true;
        }
        return false;
    }

    /**
     * Refreshes both menus on the client. The deck handler writes through to the box item, and the menu
     * saves it when it closes, so no explicit save is needed here.
     */
    private static void sync(ServerPlayer player,DeckBoxMenu menu) {
        menu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
    }
}
