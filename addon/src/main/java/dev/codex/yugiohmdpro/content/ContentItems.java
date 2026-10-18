package dev.codex.yugiohmdpro.content;
import com.google.gson.JsonObject;
import com.kiki.yugioh.item.BoosterItem;
import com.kiki.yugioh.item.CardItem;
import com.kiki.yugioh.item.StructureDeckItem;
import dev.codex.yugiohmdpro.MdproAddon;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraftforge.registries.*;
import net.minecraftforge.eventbus.api.IEventBus;
/** Two advertised groups: packs/preconstructed decks, and cards. The original tab keeps its tools. */
public final class ContentItems {
    public static final DeferredRegister<Item> ITEMS=DeferredRegister.create(ForgeRegistries.ITEMS,MdproAddon.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,MdproAddon.MOD_ID);
    public static final Map<String,RegistryObject<Item>> PRODUCTS=new LinkedHashMap<>();
    public static final Map<String,RegistryObject<Item>> RETIRED_PRODUCTS=new LinkedHashMap<>();
    public static final Map<String,RegistryObject<Item>> MEMORIAL=new LinkedHashMap<>();
    static {
        for(var p:HistoricalContent.PRODUCTS.values()) {
            String id=HistoricalContent.str(p,"id");PRODUCTS.put(id,ITEMS.register(id,()->new HistoricalProductItem(p)));
        }
        for(var e:HistoricalContent.DATA.getAsJsonArray("memorial")) {
            JsonObject c=e.getAsJsonObject();String id=HistoricalContent.str(c,"id");
            MEMORIAL.put(id,ITEMS.register(id,()->new MemorialItem(c)));
        }
        // Old stacks still deserialize, but these products have no creative entry.
        for(var p:HistoricalContent.RETIRED_PRODUCTS.values()) {
            String id=HistoricalContent.str(p,"id");
            RETIRED_PRODUCTS.put(id,ITEMS.register(id,()->new HistoricalProductItem(p)));
        }
        // Every booster pack and preconstructed deck, original or imported, is advertised here.
        TABS.register("products",()->CreativeModeTab.builder().title(Component.translatable("itemGroup.yugioh_mdpro_addon.products"))
            .icon(()->new ItemStack(PRODUCTS.values().iterator().next().get()))
            .displayItems((params,out)->{PRODUCTS.values().forEach(p->out.accept(p.get()));
                for(var item:ForgeRegistries.ITEMS.getValues())if(item instanceof BoosterItem||item instanceof StructureDeckItem)out.accept(item);}).build());
        // Every playable card, plus the commemorative prize cards that have no duel data.
        TABS.register("cards",()->CreativeModeTab.builder().title(Component.translatable("itemGroup.yugioh_mdpro_addon.cards"))
            .icon(()->new ItemStack(cardIcon()))
            .displayItems((params,out)->{for(var item:ForgeRegistries.ITEMS.getValues())if(item instanceof CardItem)out.accept(item);
                MEMORIAL.values().forEach(p->out.accept(p.get()));}).build());
    }
    /** Blue-Eyes is always registered; the scan and paper only guard a failed registry lookup. */
    private static Item cardIcon() {
        for(String id:List.of("blue_eyes_white_dragon","dark_magician")) {
            Item item=com.kiki.yugioh.registry.ModItems.cardItem(id);
            if(item!=null)return item;
        }
        for(var item:ForgeRegistries.ITEMS.getValues())if(item instanceof CardItem)return item;
        return Items.PAPER;
    }
    public static void register(IEventBus bus){ITEMS.register(bus);TABS.register(bus);}
}
