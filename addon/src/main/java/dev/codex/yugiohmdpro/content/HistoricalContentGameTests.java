package dev.codex.yugiohmdpro.content;
import com.kiki.yugioh.card.*;
import com.kiki.yugioh.item.*;
import com.kiki.yugioh.registry.ModItems;
import dev.codex.yugiohmdpro.engine.DuelCatalog;
import dev.codex.yugiohmdpro.duel.DuelBridge;
import dev.codex.yugiohfix.shop.ShopTrade;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import net.minecraftforge.gametest.*;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.items.ItemStackHandler;
import java.util.*;
@GameTestHolder("yugioh_mdpro_addon") @PrefixGameTestTemplate(false)
public final class HistoricalContentGameTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void importedInventoryAndProducts(GameTestHelper h) {
        h.assertTrue(HistoricalContent.CARDS.size()==8231&&ContentItems.PRODUCTS.size()==249&&ContentItems.RETIRED_PRODUCTS.size()==232&&HistoricalContent.LEGACY_PRODUCTS.size()==18&&ContentItems.MEMORIAL.size()==30,"Reviewed cards, visible products and retired compatibility items must be completely registered");
        for(var row:HistoricalContent.CARDS.values()) {
            String id=HistoricalContent.str(row,"id");int code=HistoricalContent.num(row,"code");var def=Cards.byId(id);
            h.assertTrue(def!=null&&ModItems.cardItem(id) instanceof CardItem,"Every pool card must have an original-compatible definition and item: "+id);
            h.assertTrue(DuelCatalog.code(id)==code&&DuelCatalog.legacyId(code).equals(id),"Card/passcode/GUI reverse mapping must round trip: "+id);
            if(id.startsWith("mdpro_c")&&(HistoricalContent.num(row,"type")&1)!=0)
                h.assertTrue(def.getAtk()==HistoricalContent.num(row,"atk")&&def.getDef()==HistoricalContent.num(row,"def")&&def.getLevel()==(HistoricalContent.num(row,"level_packed")&255),"Imported monster stats must match metadata: "+id);
            h.assertTrue((HistoricalContent.num(row,"type")&0x4000000)==0,"Link cards must be excluded");
        }
        int relations=0;
        for(var p:HistoricalContent.PRODUCTS.values())for(var r:p.getAsJsonArray("cards")) {
            var row=r.getAsJsonObject();relations++;
            if(HistoricalContent.num(row,"code")!=0)h.assertTrue(Cards.byId(HistoricalContent.str(row,"id"))!=null,"Product member must resolve to an existing card");
            else h.assertTrue(ContentItems.MEMORIAL.get(HistoricalContent.str(row,"id")).isPresent(),"Non-duel commemorative member must remain distinct");
        }
        h.assertTrue(relations==HistoricalContent.PRODUCTS.values().stream().mapToInt(p->p.getAsJsonArray("cards").size()).sum(),"Every imported product membership row must survive import");
        var canonical=HistoricalContent.visibleProducts();
        h.assertTrue(canonical.size()==267&&canonical.stream().allMatch(p->HistoricalContent.str(p,"locale").equals("ja")),"All visible products must be unique Japanese releases");
        h.assertTrue(ShopTrade.packs().size()==206&&ShopTrade.decks().size()==61,"Shop must contain exactly one item per canonical JP product");
        for(var e:HistoricalContent.LEGACY_PRODUCTS.entrySet()) {
            var item=ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.tryParse(e.getKey()));
            var stock=HistoricalContent.booster(e.getValue())?ShopTrade.packs():ShopTrade.decks();
            h.assertTrue(stock.stream().filter(s->s.is(item)).count()==1,"Original product must be reused exactly once");
            if(item instanceof StructureDeckItem legacy) {
                String unchanged=legacy.deck().toString();
                var box=StructureDeckItem.build(legacy.deck());
                var loaded=DeckContents.handlerFor(ItemStack.of(box.save(new net.minecraft.nbt.CompoundTag())));
                h.assertTrue(DeckContents.countMain(loaded)==40&&legacy.deck().toString().equals(unchanged),"Reused original preconstructed item must still deliver its original 40-card box without rewriting its recipe");
                for(var row:legacy.deck().main()) {
                    int count=0;
                    for(int i=0;i<60;i++) {
                        var s=loaded.getStackInSlot(i);var def=CardItem.definitionOf(s);
                        if(def!=null&&def.getId().equals(row.cardId()))count+=s.getCount();
                    }
                    int expected=legacy.deck().main().stream().filter(r->r.cardId().equals(row.cardId())).mapToInt(StructureDecks.Entry::count).sum();
                    h.assertTrue(count==expected,"Reused original deck must retain every original card quantity");
                }
            }
        }
        Item pack=ContentItems.PRODUCTS.get("product_ja_1101000").get();
        h.assertTrue(ShopTrade.packs().stream().anyMatch(s->s.is(pack)),"New historical booster must be in the original shop");
        var bag=new ArrayList<ItemStack>();bag.add(new ItemStack(Items.DIAMOND,6));for(int i=1;i<36;i++)bag.add(ItemStack.EMPTY);
        h.assertTrue(ShopTrade.buyPack(bag,ForgeRegistries.ITEMS.getKey(pack).toString()).isEmpty(),"Original shop must atomically buy new pack");
        h.assertTrue(bag.stream().anyMatch(s->s.is(pack))&&bag.stream().noneMatch(s->s.is(Items.DIAMOND)),"Imported pack purchase must pay the original six-diamond price");
        Item deck=ContentItems.PRODUCTS.values().stream().map(r->(HistoricalProductItem)r.get()).filter(i->!HistoricalContent.booster(i.product())).findFirst().orElseThrow();
        h.assertTrue(ShopTrade.decks().stream().anyMatch(s->s.is(deck)),"Historical preconstructed collection must be in the original deck shop");
        var gold=dev.codex.yugiohfix.item.GoldCardItems.GOLD_CARD.get();
        bag.set(0,new ItemStack(gold));
        h.assertTrue(ShopTrade.buyDeck(bag,ForgeRegistries.ITEMS.getKey(deck).toString()).isEmpty(),"Original shop must buy historical preconstructed collections");
        h.assertTrue(bag.stream().anyMatch(s->s.is(deck))&&bag.stream().noneMatch(s->s.is(gold)),"Historical deck must cost one original gold card");
        var before=bag.stream().map(ItemStack::copy).toList();
        h.assertTrue(!ShopTrade.buyDeck(bag,ForgeRegistries.ITEMS.getKey(deck).toString()).isEmpty(),"Purchase without currency must fail");
        for(int i=0;i<bag.size();i++)h.assertTrue(ItemStack.matches(bag.get(i),before.get(i)),"Failed purchase must leave inventory unchanged");
        bag.set(0,new ItemStack(Items.DIAMOND,64));
        for(var e:ContentItems.RETIRED_PRODUCTS.entrySet()) {
            String id=e.getKey();var item=e.getValue().get();
            h.assertTrue(!HistoricalContent.PRODUCTS.containsKey(id),"Retired vendor pack must not be visible");
            h.assertTrue(ShopTrade.packs().stream().noneMatch(s->s.is(item))&&ShopTrade.decks().stream().noneMatch(s->s.is(item)),"Retired vendor pack must not appear in shop tabs");
            var saved=new ItemStack(item).save(new net.minecraft.nbt.CompoundTag());
            h.assertTrue(ItemStack.of(saved).is(item),"Old vendor pack stack must retain its registry identity after save/load");
            var paidBefore=bag.stream().map(ItemStack::copy).toList();
            h.assertTrue(!ShopTrade.buyPack(bag,ForgeRegistries.ITEMS.getKey(item).toString()).isEmpty(),"Direct purchase of a retired vendor pack must be rejected");
            for(int i=0;i<bag.size();i++)h.assertTrue(ItemStack.matches(bag.get(i),paidBefore.get(i)),"Rejected vendor purchase must not change inventory");
            for(var row:HistoricalContent.playableRows(HistoricalContent.RETIRED_PRODUCTS.get(id)))h.assertTrue(HistoricalContent.CARDS.containsKey(HistoricalContent.str(row,"id")),"Hiding products must not remove any card identity");
        }
        h.assertTrue(HistoricalContent.SOURCES.values().stream().flatMap(List::stream).allMatch(p->HistoricalContent.str(p,"locale").equals("ja")&&(!HistoricalContent.RETIRED_PRODUCTS.containsKey(HistoricalContent.str(p,"id"))||p.has("legacy_item"))),"Card-source text must omit retired vendor packs");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void permanentNamesShareCopyLimit(GameTestHelper h) {
        var box=new ItemStackHandler(90);
        String a=DuelCatalog.legacyId(76812113),b=DuelCatalog.legacyId(27927359);
        box.setStackInSlot(0,new ItemStack(ModItems.cardItem(a),2));
        box.setStackInSlot(75,new ItemStack(ModItems.cardItem(b),2));
        h.assertTrue(DeckContents.countCopies(box,Cards.byId(a))==4&&DeckContents.countCopies(box,Cards.byId(b))==4,"Permanent same-name cards must share copies across main and side");
        var fillers=HistoricalContent.CARDS.values().stream().filter(c->HistoricalContent.num(c,"alias")==0&&HistoricalContent.num(c,"type")==17&&HistoricalContent.num(c,"code")!=76812113).limit(38).toList();
        for(int i=0;i<38;i++)box.setStackInSlot(i+1,new ItemStack(ModItems.cardItem(HistoricalContent.str(fillers.get(i),"id"))));
        h.assertTrue(DeckContents.validate(box)!=null,"Four rule-same-name copies must invalidate a full deck");
        box.setStackInSlot(75,new ItemStack(ModItems.cardItem(b)));
        h.assertTrue(DeckContents.validate(box)==null,"Three total rule-same-name copies must remain valid");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void productsOpenOnlyTheirOwnMembers(GameTestHelper h) {
        var player=net.minecraftforge.common.util.FakePlayerFactory.get(h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"ContentFixture"));
        for(String pid:List.of("product_ja_1101000","product_ja_111113004","product_ja_2201016")) {
            player.getInventory().clearContent();
            var item=(HistoricalProductItem)ContentItems.PRODUCTS.get(pid).get();
            var stack=new ItemStack(item,2);player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,stack);
            item.use(h.getLevel(),player,net.minecraft.world.InteractionHand.MAIN_HAND);
            h.assertTrue(stack.getCount()==1,"Opening must consume exactly one product");
            var ids=HistoricalContent.playableRows(item.product()).stream().map(r->HistoricalContent.str(r,"id")).collect(java.util.stream.Collectors.toSet());
            int total=0;Set<String> obtained=new HashSet<>();
            for(var card:player.getInventory().items) {
                var def=CardItem.definitionOf(card);if(def==null)continue;
                total+=card.getCount();obtained.add(def.getId());
                h.assertTrue(ids.contains(def.getId()),"Opening must never draw a card from another product");
                h.assertTrue(card.hasTag()&&card.getTag().getString("HistoricalProduct").equals(HistoricalContent.str(item.product(),"product_id")),"Opened cards must preserve product provenance");
            }
            if(HistoricalContent.booster(item.product()))h.assertTrue(total==5,"Booster and strengthening packs must give five product members");
            else {
                h.assertTrue(total==0,"Preconstructed product must give a box rather than loose cards");
                var boxes=player.getInventory().items.stream().filter(s->s.is(ModItems.DECK_BOX.get())).toList();
                h.assertTrue(boxes.size()==1&&boxes.get(0).getCount()==1,"Opening must give exactly one original deck box");
                h.assertTrue(DeckContents.countMain(DeckContents.handlerFor(boxes.get(0)))==40,"Opened box must contain 40 main cards");
            }
        }
        player.getInventory().clearContent();h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void allPreconstructedBoxesAreValidAndPersist(GameTestHelper h) {
        int decks=0;
        for(var p:java.util.stream.Stream.concat(HistoricalContent.PRODUCTS.values().stream(),HistoricalContent.RETIRED_PRODUCTS.values().stream()).toList()) {
            h.assertTrue(HistoricalContent.str(p,"name").matches(".*[\\x{4e00}-\\x{9fff}].*"),"Every product must have a Chinese display name");
            if(HistoricalContent.booster(p))continue;
            decks++;
            var box=HistoricalProductItem.buildDeckBox(p);
            box=ItemStack.of(box.save(new net.minecraft.nbt.CompoundTag()));
            var handler=DeckContents.handlerFor(box);
            h.assertTrue(box.is(ModItems.DECK_BOX.get())&&DeckContents.countMain(handler)==40,"All historical decks must survive original deck-box NBT with 40 main cards: "+p);
            var error=DeckContents.validate(handler);
            h.assertTrue(error==null,"Every delivered deck must pass original size and same-name limits: "+HistoricalContent.str(p,"id")+" / "+(error==null?"":error.getString()));
            var allowed=new HashSet<String>();
            for(var source:p.getAsJsonObject("deck_recipe").getAsJsonArray("source_products")) {
                var product=HistoricalContent.anyProduct("product_"+source.getAsString().replace(':','_'));
                HistoricalContent.playableRows(product).forEach(r->allowed.add(HistoricalContent.str(r,"id")));
            }
            for(int i=0;i<handler.getSlots();i++) {
                var s=handler.getStackInSlot(i);if(s.isEmpty())continue;
                var def=CardItem.definitionOf(s);
                h.assertTrue(def!=null&&allowed.contains(def.getId()),"Box must contain only the corresponding product or documented base edition");
                h.assertTrue(i<60?!def.isExtraDeckCard():def.isExtraDeckCard(),"Main and Extra cards must be stored in original matching slots");
                h.assertTrue(s.getTag().getString("HistoricalProduct").equals(HistoricalContent.str(p,"product_id")),"Box card provenance must survive save/load");
            }
            h.assertTrue(box.hasCustomHoverName()&&box.getHoverName().getString().equals(HistoricalContent.str(p,"name")),"Box name must remain Chinese after save/load");
        }
        h.assertTrue(decks==119,"Four strengthening packs must be classified as boosters; 119 preconstructed products must give deck boxes");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void importedCardsReachNativeBoard(GameTestHelper h) {
        var chosen=HistoricalContent.CARDS.values().stream().filter(c->HistoricalContent.str(c,"id").startsWith("mdpro_c")&&HistoricalContent.num(c,"type")==17).limit(5).toList();
        h.assertTrue(chosen.size()==5,"New normal-monster fixture must exist");
        var deck=new ArrayList<DeckContents.DeckEntry>();
        for(int i=0;i<40;i++)deck.add(new DeckContents.DeckEntry(Cards.byId(HistoricalContent.str(chosen.get(i%5),"id")),CardRarity.COMMON));
        var a=new com.kiki.yugioh.duel.DuelSide(UUID.randomUUID(),"Imported A",deck,List.of());
        var b=new com.kiki.yugioh.duel.DuelSide(UUID.randomUUID(),"Imported B",deck,List.of());
        var duel=new com.kiki.yugioh.duel.Duel(h.getLevel().getServer(),h.absolutePos(net.minecraft.core.BlockPos.ZERO),a,b);
        dev.codex.yugiohfix.duel.DuelRules.set(duel,new dev.codex.yugiohfix.duel.DuelRules.Rules(true,true,false,false));
        duel.start(0);
        dev.codex.yugiohmdpro.duel.HostTestAwait.waiting(duel);
        try {
            var tag=DuelBridge.view(duel,0);var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(tag.getByteArray("board")));
            try {
                var state=com.kiki.yugioh.network.S2CDuelStatePacket.decode(buf);
                h.assertTrue(state.self().hand().size()>=5&&state.self().hand().stream().allMatch(c->c.cardId().startsWith("mdpro_c")),"New inventory cards must reach native hand and original GUI with stable imported IDs");
            }finally{buf.release();}
        }finally{duel.endDuel(-1,Component.literal("Imported inventory test complete"));}
        h.succeed();
    }
}
