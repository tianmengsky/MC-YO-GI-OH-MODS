package dev.codex.yugiohmdpro.content;

import com.kiki.yugioh.item.BoosterItem;
import com.kiki.yugioh.item.CardItem;
import com.kiki.yugioh.item.StructureDeckItem;
import dev.codex.yugiohmdpro.MdproAddon;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The original tab advertises its tools only; packs, preconstructed decks and cards live in the
 * dedicated addon tabs. A legacy product stays hidden unless the current JP catalogue reuses the
 * exact item, so old stacks remain usable without cluttering the creative inventory.
 */
@Mod.EventBusSubscriber(modid=MdproAddon.MOD_ID,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class ProductVisibility {
    private static final ResourceKey<CreativeModeTab> ORIGINAL_MAIN=ResourceKey.create(Registries.CREATIVE_MODE_TAB,new ResourceLocation("yugioh","main"));
    @SubscribeEvent
    public static void creative(BuildCreativeModeTabContentsEvent event) {
        boolean originalMain=event.getTabKey().equals(ORIGINAL_MAIN);
        var remove=new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        for(var entry:event.getEntries()) {
            var item=entry.getKey().getItem();
            if(item instanceof CardItem||item instanceof BoosterItem||item instanceof StructureDeckItem) {
                if(originalMain)remove.add(entry.getKey());
                else if(!(item instanceof CardItem)&&!HistoricalContent.legacyVisible(item))remove.add(entry.getKey());
            }
        }
        remove.forEach(event.getEntries()::remove);
    }
}
