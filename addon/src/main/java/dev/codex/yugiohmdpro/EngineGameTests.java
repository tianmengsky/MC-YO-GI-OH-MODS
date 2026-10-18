package dev.codex.yugiohmdpro;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@GameTestHolder(MdproAddon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EngineGameTests {
    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void originalDuelUsesMdpro(GameTestHelper helper) throws Exception {
        var entries=new java.util.ArrayList<com.kiki.yugioh.item.DeckContents.DeckEntry>();
        for(int i=0;i<40;i++) entries.add(new com.kiki.yugioh.item.DeckContents.DeckEntry(
                com.kiki.yugioh.card.Cards.byId("celtic_guardian"), com.kiki.yugioh.card.CardRarity.COMMON));
        var first=new com.kiki.yugioh.duel.DuelSide(java.util.UUID.randomUUID(),"Native Test A",entries,java.util.List.of());
        var second=new com.kiki.yugioh.duel.DuelSide(java.util.UUID.randomUUID(),"Native Test B",entries,java.util.List.of());
        var duel=new com.kiki.yugioh.duel.Duel(helper.getLevel().getServer(),helper.absolutePos(net.minecraft.core.BlockPos.ZERO),first,second);
        int before=dev.codex.yugiohmdpro.duel.DuelBridge.closedCount();
        dev.codex.yugiohfix.duel.DuelRules.set(duel,new dev.codex.yugiohfix.duel.DuelRules.Rules(false,true,false,false));
        duel.start(1);
        dev.codex.yugiohmdpro.duel.HostTestAwait.waiting(duel);
        helper.assertTrue(dev.codex.yugiohmdpro.duel.DuelBridge.active(duel),"Original start must create MDPro session");
        helper.assertTrue(first.hand.isEmpty() && first.deck.size()==40,"Legacy start must not draw/shuffle or execute old engine");
        var view=dev.codex.yugiohmdpro.duel.DuelBridge.view(duel,0);
        helper.assertTrue(view.getList("rows",10).size()>0,"Native query must produce a visible board snapshot");
        helper.assertTrue(first.lifePoints==8000 && second.lifePoints==8000,"Native LP query must decode both players");
        var display=display(view);
        helper.assertTrue(display.viewerSide()==0 && display.turnPlayer()==1,"Display must preserve legacy viewer and native starter mapping");
        helper.assertTrue(display.self().hand().size()>=5 && display.self().hand().stream().allMatch(com.kiki.yugioh.network.CardView::isKnown),"Original GUI must receive native own hand identities");
        helper.assertTrue(display.self().hand().stream().allMatch(c->c.position()==com.kiki.yugioh.duel.CardPosition.FACE_UP),"Native hidden hand positions must normalize to upright own card faces");
        helper.assertTrue(display.opponent().hand().size()>=5 && display.opponent().hand().stream().noneMatch(com.kiki.yugioh.network.CardView::isKnown),"Opponent hand identities must never be sent");
        helper.assertTrue(display.self().deckCount()+display.self().hand().size()==40,"Native deck and hand counts must agree in the original GUI");
        helper.assertTrue(display.self().monsters().size()==5 && display.self().spells().size()==5,"Original board must have five MR3 monster and spell slots");
        dev.codex.yugiohmdpro.duel.DuelBridge.autoForTest(duel);
        helper.onEachTick(()->{ if(!duel.isFinished()) duel.tick(); });
        helper.succeedWhen(()->{
            helper.assertTrue(dev.codex.yugiohmdpro.duel.DuelBridge.turns(duel)>=6,"Waiting for 6 native turns");
            helper.assertTrue(first.hand.isEmpty(),"Old Java engine must remain idle");
            var board=display(dev.codex.yugiohmdpro.duel.DuelBridge.view(duel,0));
            helper.assertTrue(dev.codex.yugiohmdpro.duel.DuelBridge.view(duel,0).getInt("attackEvent")>0,"Native attack messages must reach projection animation events");
            helper.assertTrue(java.util.stream.Stream.concat(board.self().monsters().stream(),board.opponent().monsters().stream()).anyMatch(com.kiki.yugioh.network.CardView::present),"Native summons must populate original GUI / projection monster slots");
            duel.surrender(0);
            helper.assertTrue(duel.winner()==1 && duel.isFinished(),"Original surrender/winner lifecycle must work");
            helper.assertTrue(!dev.codex.yugiohmdpro.duel.DuelBridge.active(duel),"Native session must be released on endDuel");
            helper.assertTrue(dev.codex.yugiohmdpro.duel.DuelBridge.closedCount()>=before+1,"Native handle must close (other feature tests may close concurrent sessions)");
        });
    }
    private static com.kiki.yugioh.network.S2CDuelStatePacket display(net.minecraft.nbt.CompoundTag tag) {
        var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(tag.getByteArray("board")));
        try {return com.kiki.yugioh.network.S2CDuelStatePacket.decode(buf);}finally {buf.release();}
    }
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void nativeEngineInForge(GameTestHelper helper) throws Exception {
        helper.assertTrue(ModList.get().isLoaded("yugioh"), "Original yugioh mod must remain loaded");
        helper.assertTrue(ForgeRegistries.ITEMS.containsKey(new ResourceLocation("yugioh", "blue_eyes_white_dragon")),
                "Original card registry must remain present");
        helper.assertTrue(ForgeRegistries.ITEMS.getEntries().stream().noneMatch(entry -> entry.getKey().location().getNamespace().equals(MdproAddon.MOD_ID) && entry.getValue() instanceof com.kiki.yugioh.item.CardItem),
                "Imported playable cards must use the original card item registry without namespace duplicates");
        var probe=java.util.concurrent.CompletableFuture.runAsync(()->{
            try {dev.codex.yugiohmdpro.engine.ReferenceProbe.verify(FMLPaths.GAMEDIR.get().resolve("mdpro-reference-probe"));}
            catch(Exception ex){throw new java.util.concurrent.CompletionException(ex);}
        });
        helper.succeedWhen(()->{
            helper.assertTrue(probe.isDone(),"Waiting for reference host verifier");
            probe.join();
        });
    }
}
