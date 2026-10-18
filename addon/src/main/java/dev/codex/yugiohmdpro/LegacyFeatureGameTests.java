package dev.codex.yugiohmdpro;

import com.kiki.yugioh.card.*;
import com.kiki.yugioh.duel.*;
import com.kiki.yugioh.item.*;
import dev.codex.yugiohfix.binder.*;
import dev.codex.yugiohfix.duel.DuelRules;
import dev.codex.yugiohfix.dueldisk.DuelDiskVariants;
import dev.codex.yugiohfix.engine.DuelTransactions;
import dev.codex.yugiohfix.item.GoldCardItems;
import dev.codex.yugiohfix.shop.ShopStock;
import dev.codex.yugiohfix.star.StarWager;
import dev.codex.yugiohmdpro.duel.DuelBridge;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.*;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.nio.file.*;

@GameTestHolder(MdproAddon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LegacyFeatureGameTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void registriesAndPersistentFeatures(GameTestHelper h) throws Exception {
        h.assertTrue(ModList.get().isLoaded("yugioh_interaction_fix") && ModList.get().isLoaded("ygostar") && ModList.get().isLoaded("geckolib"),"All original feature mods and renderer dependency must load");
        h.assertTrue(DuelDiskVariants.ITEMS.size()==20 && DuelDiskVariants.ITEMS.values().stream().allMatch(r->r.isPresent()),"All 20 original duel disks must retain their registrations");
        h.assertTrue(GoldCardItems.GOLD_CARD.isPresent() && GoldCardItems.STARTER_PACK.isPresent() && GoldCardItems.DUEL_DISK_SELECTOR.isPresent(),"Currency, starter pack and selectors must register");
        h.assertTrue(Cards.all().size()>1000,"Expansion card bootstrap must run");
        var blue=Cards.byId("blue_eyes_white_dragon");
        ItemStack stack=new ItemStack(ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("yugioh",blue.getId())));
        var binder=new CardBinder();UUID player=UUID.randomUUID();
        h.assertTrue(binder.insert(player,stack.copy()) && binder.cells(player).size()==1,"Original binder must accept original card items");
        var saved=binder.save(new CompoundTag());var loaded=CardBinder.load(saved);
        h.assertTrue(loaded.cells(player).size()==1 && ItemStack.isSameItemSameTags(loaded.cells(player).get(0).stack(),stack),"Binder save data must retain item identity");
        var stock=new ShopStock();h.assertTrue(stock.add(stack.copy()),"Original global shop must accept cards");
        var shop=ShopStock.load(stock.save(new CompoundTag()));
        h.assertTrue(shop.entries().size()==1 && shop.take(shop.entries().get(0).id()) && shop.entries().isEmpty(),"Shop stock must persist and remove traded cards");
        h.assertTrue(Arrays.equals(StarWager.settle(1,2),new int[]{-2,2}),"Original star wager settlement must remain available");
        var ids=ForgeRegistries.ITEMS.getKeys().stream().filter(k->Set.of("yugioh","yugioh_interaction_fix","ygostar").contains(k.getNamespace())).map(Object::toString).sorted().toList();
        Files.write(FMLPaths.GAMEDIR.get().resolve("../generated/restored-item-registry.txt").normalize(),ids);
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void nativeTrainingAndRuleIsolation(GameTestHelper h) {
        var offered=dev.codex.yugiohfix.duel.DuelRulesOffers.rulesFor(null);
        h.assertTrue(!offered.cancelSurrender() && offered.freeMove() && !offered.privateVoiceRoom() && !offered.training(),"A duel whose initiator never touched a switch must use the screen defaults: surrender allowed, free move on, no voice room");
        var entries=new ArrayList<DeckContents.DeckEntry>();
        var opening=List.of("blue_eyes_white_dragon","dark_magician","celtic_guardian","mystical_elf","summoned_skull");
        for(String id:opening)entries.add(new DeckContents.DeckEntry(Cards.byId(id),CardRarity.COMMON));
        for(int i=5;i<40;i++)entries.add(new DeckContents.DeckEntry(Cards.byId("celtic_guardian"),CardRarity.COMMON));
        var a=new DuelSide(UUID.randomUUID(),"Training A",entries,List.of());var b=new DuelSide(UUID.randomUUID(),"Training B",entries,List.of());
        var duel=new Duel(h.getLevel().getServer(),h.absolutePos(net.minecraft.core.BlockPos.ZERO),a,b);
        DuelRules.set(duel,new DuelRules.Rules(true,false,false,true));
        duel.start(0);
        dev.codex.yugiohmdpro.duel.HostTestAwait.waiting(duel);
        try {
            h.assertTrue(DuelBridge.active(duel),"Training must create native session");
            var tag=DuelBridge.view(duel,0);var buf=new FriendlyByteBuf(Unpooled.wrappedBuffer(tag.getByteArray("board")));
            com.kiki.yugioh.network.S2CDuelStatePacket state;
            try {state=com.kiki.yugioh.network.S2CDuelStatePacket.decode(buf);}finally {buf.release();}
            h.assertTrue(state.self().hand().stream().map(c->c.cardId()).toList().containsAll(opening),"Training opening must use the first five deck-box cards");
            h.assertTrue(tag.getBoolean("surrenderLocked"),"Native client must receive surrender lock");
            duel.surrender(0);h.assertTrue(!duel.isFinished(),"Original surrender lock must apply to native duel");
            boolean[] ran={false};DuelTransactions.run(duel,()->ran[0]=true);
            h.assertTrue(!ran[0] && a.hand.isEmpty(),"Old feature packets must never execute legacy rules");
            h.assertTrue(DuelBridge.animationSnapshot(duel).ownHand()>=5,"Player animations must read native hand counts");
            var info=DuelBridge.publicInfo(duel,0);
            h.assertTrue(info.opponentId().equals(b.playerId) && info.opponentHandRevealed().isEmpty(),"Native public-info/counter queries must preserve opponent identity and hand privacy");
        } finally {duel.endDuel(-1,Component.literal("Native feature test complete"));}
        h.succeed();
    }
}
