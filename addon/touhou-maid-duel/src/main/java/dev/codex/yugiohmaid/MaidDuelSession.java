package dev.codex.yugiohmaid;

import com.kiki.yugioh.item.DeckContents;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

public record MaidDuelSession(UUID ownerId, UUID maidId, String maidName, String modelId,
                              boolean ysmModel, String ysmModelId, String ysmTexture,
                              String ysmName, ItemStack offhand,
                              List<DeckContents.DeckEntry> mainDeck,
                              List<DeckContents.DeckEntry> extraDeck) {
    public MaidDuelSession {
        offhand = offhand.copy();
        mainDeck = List.copyOf(mainDeck);
        extraDeck = List.copyOf(extraDeck);
    }
}
