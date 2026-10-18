package dev.codex.yugiohmdpro.rules;
import com.kiki.yugioh.card.*;
import com.kiki.yugioh.item.*;
import dev.codex.yugiohfix.engine.BanList;
import dev.codex.yugiohmdpro.content.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("yugioh_mdpro_addon") @PrefixGameTestTemplate(false)
public final class ServerBanlistGameTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void opRulesPersistAndNeverRewriteCards(GameTestHelper h) throws Exception {
        var server=h.getLevel().getServer();var rules=ServerBanlist.get(server);var before=rules.limits();
        var dispatcher=server.getCommands().getDispatcher();
        var op=server.createCommandSourceStack().withPermission(2).withSuppressedOutput();
        var guest=op.withPermission(0);
        var product=HistoricalContent.PRODUCTS.get("product_ja_2201016");
        String recipe=product.toString(),card=HistoricalContent.CARDS.get("dark_hole").toString();
        ItemStack box=HistoricalProductItem.buildDeckBox(product),savedBox=box.copy();
        try {
            rules.clear(server);
            h.assertTrue(BanList.limitOf("dark_hole")==3&&!BanList.banned("pot_of_greed")&&BanList.entries().isEmpty(),"Legacy baked-in bans must be completely disabled by default");
            h.assertTrue(dispatcher.execute("mdpro banlist set 53129443 0",op)==1&&BanList.banned("dark_hole"),"OP must be able to ban using a native card password");
            h.assertTrue(BanList.entries().size()==1&&BanList.source().contains("服务器"),"Original tooltip/list APIs must use dynamic server rules");
            var snapshot=rules.limits();boolean denied=false;
            try {dispatcher.execute("mdpro banlist set 53129443 3",guest);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){denied=true;}
            h.assertTrue(denied&&rules.limits().equals(snapshot),"Non-OP must be denied without modifying rules");
            boolean invalid=false;
            try {dispatcher.execute("mdpro banlist set 53129443 4",op);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){invalid=true;}
            h.assertTrue(invalid&&rules.limits().equals(snapshot),"Invalid limits must fail atomically");
            var tag=rules.save(new CompoundTag());var loaded=ServerBanlist.load(tag);
            h.assertTrue(loaded.limits().equals(snapshot),"Dynamic restrictions must round-trip SavedData independently of card data");
            server.overworld().getDataStorage().save();
            var path=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/yugioh_mdpro_banlist.dat");
            var disk=net.minecraft.nbt.NbtIo.readCompressed(path.toFile());
            h.assertTrue(ServerBanlist.load(disk.getCompound("data")).limits().equals(snapshot),"Per-world restrictions must actually persist to disk for restart");
            h.assertTrue(dispatcher.execute("mdpro banlist set yugioh:dark_hole 2",op)==1&&BanList.limitOf("dark_hole")==2,"Registry ID and password must address the same card");
            dispatcher.execute("mdpro banlist set 76812113 1",op);
            var a=dev.codex.yugiohmdpro.engine.DuelCatalog.legacyId(76812113);
            var b=dev.codex.yugiohmdpro.engine.DuelCatalog.legacyId(27927359);
            h.assertTrue(BanList.limitOf(b)==1&&ServerBanlist.validate(List.of(Cards.byId(a),Cards.byId(b)))!=null,"Aliases must share a dynamically restricted copy limit");
            dispatcher.execute("mdpro banlist set 89631139 0",op);
            var main=new ArrayList<DeckContents.DeckEntry>();
            main.add(new DeckContents.DeckEntry(Cards.byId("blue_eyes_white_dragon"),CardRarity.COMMON));
            for(int i=1;i<40;i++)main.add(new DeckContents.DeckEntry(Cards.byId("celtic_guardian"),CardRarity.COMMON));
            var first=new com.kiki.yugioh.duel.DuelSide(UUID.randomUUID(),"Rules A",main,List.of());
            var second=new com.kiki.yugioh.duel.DuelSide(UUID.randomUUID(),"Rules B",main,List.of());
            var duel=new com.kiki.yugioh.duel.Duel(server,h.absolutePos(net.minecraft.core.BlockPos.ZERO),first,second);duel.start(0);
            h.assertTrue(duel.isFinished()&&!dev.codex.yugiohmdpro.duel.DuelBridge.active(duel),"Server must reject a newly banned deck before creating a native duel");
            h.assertTrue(first.deck.size()==40&&second.deck.size()==40,"Rejecting a duel must not remove its cards");
            String awarded=product.getAsJsonObject("deck_recipe").getAsJsonArray("main").get(0).getAsJsonObject().get("id").getAsString();
            rules.set(server,awarded,0);
            h.assertTrue(DeckContents.validate(DeckContents.handlerFor(box))!=null,"Original deck-box validation must obey a live server ban");
            h.assertTrue(ItemStack.matches(box,savedBox)&&product.toString().equals(recipe)&&HistoricalContent.CARDS.get("dark_hole").toString().equals(card),"Changing restrictions must never rewrite physical cards, preconstructed recipes or deck-box contents");
            var newlyOpened=HistoricalProductItem.buildDeckBox(product);
            h.assertTrue(ItemStack.matches(newlyOpened,savedBox),"Opening a product under a ban must award exactly its unchanged recipe");
            dispatcher.execute("mdpro banlist clear",op);
            h.assertTrue(BanList.limitOf("dark_hole")==3&&BanList.limitOf(b)==3&&DeckContents.validate(DeckContents.handlerFor(box))==null,"Clearing rules must immediately restore default deck acceptance");
        } finally {
            rules.clear(server);
            for(var e:before.entrySet()) {
                var def=ServerBanlist.representative(e.getKey());if(def!=null)rules.set(server,def.getId(),e.getValue());
            }
            server.overworld().getDataStorage().save();
        }
        h.succeed();
    }
}
