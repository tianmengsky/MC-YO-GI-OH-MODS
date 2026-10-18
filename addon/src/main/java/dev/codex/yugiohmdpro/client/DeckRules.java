package dev.codex.yugiohmdpro.client;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.item.CardItem;
import com.kiki.yugioh.item.DeckContents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side deck legality advisor.
 *
 * <p>The reviewed menu already refuses illegal placements server-side through its slot rules, but it
 * gives no feedback: a rejected click simply does nothing. This mirrors the same limits on the client
 * so the rebuilt editor can say what is wrong — main deck size, extra and side limits, and the shared
 * copy limit that spans all three sections. It only reads the slots; it never decides whether an
 * action is allowed.</p>
 */
public final class DeckRules {
    /** One problem found in the current deck, ready to be shown to the player. */
    public record Warning(String text) {}

    /** Result of one evaluation. Counts are cards, not occupied slots. */
    public record Verdict(int main,int extra,int side,int minMain,int maxMain,int maxExtra,int maxSide,
                          int maxCopies,List<Warning> warnings) {
        public boolean legal() {return warnings.isEmpty();}
        public String mainLabel() {return main+" / "+maxMain;}
        /** Short status for the main deck header, e.g. {@code 还差 5 张} or {@code 数量合适}. */
        public String mainStatus() {
            if(main<minMain)return "还差 "+(minMain-main)+" 张";
            if(main>maxMain)return "超出 "+(main-maxMain)+" 张";
            return "数量合适";
        }
    }

    private DeckRules() {}

    /** Evaluates the deck slots: 0-59 main, 60-74 extra, 75-89 side. */
    public static Verdict evaluate(List<Slot> slots) {
        int minMain=DeckContents.minMainCards(), maxMain=DeckContents.maxMainCards();
        int maxExtra=DeckContents.maxExtraCards(), maxSide=DeckContents.maxSideCards(), maxCopies=DeckContents.maxCopies();
        int main=count(slots,0,DeckEditorLayout.MAIN_SLOTS);
        int extra=count(slots,DeckEditorLayout.MAIN_SLOTS,DeckEditorLayout.EXTRA_SLOTS);
        int side=count(slots,DeckEditorLayout.MAIN_SLOTS+DeckEditorLayout.EXTRA_SLOTS,DeckEditorLayout.SIDE_SLOTS);
        List<Warning> warnings=new ArrayList<>();
        if(main<minMain)warnings.add(new Warning("主卡组还差 "+(minMain-main)+" 张"));
        if(main>maxMain)warnings.add(new Warning("主卡组超出 "+(main-maxMain)+" 张"));
        if(extra>maxExtra)warnings.add(new Warning("额外卡组超出 "+(extra-maxExtra)+" 张"));
        if(side>maxSide)warnings.add(new Warning("副卡组超出 "+(side-maxSide)+" 张"));
        for(Map.Entry<String,Integer> entry:copies(slots).entrySet()) {
            if(entry.getValue()>maxCopies)warnings.add(new Warning("同名超限："+entry.getKey()+" ×"+entry.getValue()));
        }
        return new Verdict(main,extra,side,minMain,maxMain,maxExtra,maxSide,maxCopies,warnings);
    }

    /** Copies per card name across the whole deck, mirroring the reviewed shared copy limit. */
    public static Map<String,Integer> copies(List<Slot> slots) {
        Map<String,Integer> copies=new LinkedHashMap<>();
        int limit=Math.min(DeckEditorLayout.MAIN_SLOTS+DeckEditorLayout.EXTRA_SLOTS+DeckEditorLayout.SIDE_SLOTS,slots.size());
        for(int index=0;index<limit;index++) {
            ItemStack stack=slots.get(index).getItem();
            if(stack.isEmpty())continue;
            CardDefinition definition=CardItem.definitionOf(stack);
            if(definition==null)continue;
            copies.merge(definition.getId(),stack.getCount(),Integer::sum);
        }
        return copies;
    }

    private static int count(List<Slot> slots,int from,int size) {
        int total=0;
        for(int index=from;index<from+size&&index<slots.size();index++) {
            ItemStack stack=slots.get(index).getItem();
            if(!stack.isEmpty())total+=stack.getCount();
        }
        return total;
    }
}
