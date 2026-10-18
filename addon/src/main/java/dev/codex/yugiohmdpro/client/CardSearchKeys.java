package dev.codex.yugiohmdpro.client;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.item.CardItem;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * One reviewed search index for every card list in the mod.
 *
 * <p>The catalog, the binder page and the deck box all used to match the displayed Chinese name and
 * the English name only. The same key is now built here from the reviewed inventory: Chinese,
 * English and Japanese names, the eight-digit passcode, the bare passcode, the CID and the
 * registration id.</p>
 */
public final class CardSearchKeys {
    private CardSearchKeys() {}

    /** Lower-case haystack for one card id; {@code displayedName} may be null. */
    public static String of(String cardId,String displayedName) {
        StringBuilder key=new StringBuilder();
        if(displayedName!=null)key.append(displayedName).append(' ');
        var row=HistoricalContent.CARDS.get(cardId);
        if(row!=null) {
            int code=HistoricalContent.num(row,"code");
            // The reviewed Chinese name is part of the index even on another client language, so a
            // Chinese query keeps working after switching the game to English or Japanese.
            key.append(HistoricalContent.str(row,"name")).append(' ')
               .append(String.format(Locale.ROOT,"%08d",code)).append(' ').append(code).append(' ')
               .append(HistoricalContent.num(row,"cid")).append(' ')
               .append(HistoricalContent.str(row,"name_en")).append(' ')
               .append(HistoricalContent.str(row,"name_ja")).append(' ');
        }
        key.append(cardId);
        return key.toString().toLowerCase(Locale.ROOT);
    }

    public static boolean matches(String cardId,String displayedName,String needle) {
        if(needle==null||needle.isBlank())return true;
        return of(cardId,displayedName).contains(needle.trim().toLowerCase(Locale.ROOT));
    }

    /** Same match for an item stack, so the binder and the deck box share the catalog's index. */
    public static boolean matchesStack(ItemStack stack,String needle) {
        if(needle==null||needle.isBlank())return true;
        if(stack==null||stack.isEmpty())return false;
        CardDefinition definition=CardItem.definitionOf(stack);
        if(definition==null)return false;
        return matches(definition.getId(),Component.translatable("item.yugioh."+definition.getId()).getString(),needle);
    }
}
