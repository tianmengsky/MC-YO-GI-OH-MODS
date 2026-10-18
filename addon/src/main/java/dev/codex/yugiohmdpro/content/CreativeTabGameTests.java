package dev.codex.yugiohmdpro.content;
import com.kiki.yugioh.item.*;
import dev.codex.yugiohmdpro.MdproAddon;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.*;
import java.util.Collection;

/** Creative inventory grouping: tools in the original tab, then packs/decks, then cards. */
@GameTestHolder(MdproAddon.MOD_ID) @PrefixGameTestTemplate(false)
public final class CreativeTabGameTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void itemsAreGroupedByTab(GameTestHelper h) {
        var params=new CreativeModeTab.ItemDisplayParameters(h.getLevel().enabledFeatures(),false,h.getLevel().registryAccess());
        var tabs=h.getLevel().registryAccess().registryOrThrow(Registries.CREATIVE_MODE_TAB);
        var original=tabs.get(new ResourceLocation("yugioh","main"));
        h.assertTrue(original!=null,"Original yugioh tab must stay registered");
        var remaining=contents(original,params);
        h.assertTrue(remaining.stream().noneMatch(s->s.getItem() instanceof CardItem||s.getItem() instanceof BoosterItem||s.getItem() instanceof StructureDeckItem),
            "Original tab must keep no boosters, preconstructed decks or cards");
        h.assertTrue(remaining.stream().anyMatch(s->s.getItem() instanceof DeckBoxItem)&&remaining.stream().anyMatch(s->s.getItem() instanceof DuelDiskItem),
            "Original tab must keep advertising its tools");

        var packs=tabs.get(new ResourceLocation(MdproAddon.MOD_ID,"products"));
        h.assertTrue(packs!=null,"Pack tab must be registered");
        var packStacks=contents(packs,params);
        h.assertTrue(packStacks.stream().allMatch(s->s.getItem() instanceof HistoricalProductItem||s.getItem() instanceof BoosterItem||s.getItem() instanceof StructureDeckItem),
            "Pack tab admits packs and preconstructed decks only");
        h.assertTrue(packStacks.stream().anyMatch(s->s.getItem() instanceof BoosterItem)&&packStacks.stream().anyMatch(s->s.getItem() instanceof StructureDeckItem),
            "Pack tab must advertise both booster packs and preconstructed decks");
        h.assertTrue(packStacks.stream().anyMatch(s->s.getItem() instanceof HistoricalProductItem),
            "Pack tab must keep the imported historical products");

        var cards=tabs.get(new ResourceLocation(MdproAddon.MOD_ID,"cards"));
        h.assertTrue(cards!=null,"Card tab must be registered");
        var cardStacks=contents(cards,params);
        long playable=cardStacks.stream().filter(s->s.getItem() instanceof CardItem).count();
        h.assertTrue(playable>=8000,"Card tab must advertise every registered card: "+playable);
        h.assertTrue(cardStacks.stream().allMatch(s->s.getItem() instanceof CardItem||s.getItem() instanceof MemorialItem),
            "Card tab admits cards and commemorative prize cards only");
        h.assertTrue(cardStacks.stream().anyMatch(s->s.getItem() instanceof MemorialItem),
            "Commemorative prize cards belong to the card tab");
        h.succeed();
    }
    /** Display items start empty, so contents must be built explicitly before inspection. */
    private static Collection<ItemStack> contents(CreativeModeTab tab,CreativeModeTab.ItemDisplayParameters params) {
        tab.buildContents(params);
        return tab.getDisplayItems();
    }
}
