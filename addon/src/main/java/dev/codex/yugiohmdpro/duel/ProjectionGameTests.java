package dev.codex.yugiohmdpro.duel;

import com.kiki.yugioh.duel.*;
import com.kiki.yugioh.network.CardView;
import com.kiki.yugioh.card.*;
import com.kiki.yugioh.item.DeckContents;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("yugioh_mdpro_addon")
@PrefixGameTestTemplate(false)
public final class ProjectionGameTests {
    private static Duel start(GameTestHelper helper) {
        var cards=new ArrayList<DeckContents.DeckEntry>();
        for(int i=0;i<40;i++)cards.add(new DeckContents.DeckEntry(Cards.byId("celtic_guardian"),CardRarity.COMMON));
        var duel=new Duel(helper.getLevel().getServer(),helper.absolutePos(BlockPos.ZERO),
                new DuelSide(UUID.randomUUID(),"Projection A",cards,List.of()),
                new DuelSide(UUID.randomUUID(),"Projection B",cards,List.of()));
        dev.codex.yugiohfix.duel.DuelRules.set(duel,new dev.codex.yugiohfix.duel.DuelRules.Rules(false,true,false,false));
        duel.start(0);HostTestAwait.waiting(duel);return duel;
    }
    private static List<CardView> read(net.minecraft.nbt.CompoundTag tag,String key) {
        var buf=new FriendlyByteBuf(Unpooled.wrappedBuffer(tag.getByteArray(key)));
        try{int n=buf.readVarInt();var cards=new ArrayList<CardView>();for(int i=0;i<n;i++)cards.add(CardView.read(buf));return cards;}
        finally{buf.release();}
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void publicProjectionHidesBothSidesSecrets(GameTestHelper helper) {
        for(int action:new int[]{3,0}) {
            Duel duel=start(helper);
            try {
                var game=DuelBridge.game(duel);var prompt=game.prompt;
                helper.assertTrue(prompt!=null && prompt.kind==11,"Must reach native main-phase selection");
                int index=-1;
                for(int i=0;i<prompt.entries.size();i++) {
                    var entry=prompt.entries.get(i);
                    if(!entry.command() && (entry.value()&0xffff)==action){index=i;break;}
                }
                helper.assertTrue(index>=0,"Must offer the actual native summon/set action "+action);
                game.respond(prompt.response(new int[]{index},false,""));HostTestAwait.waiting(duel);
                // Finish the core's placement/position request before inspecting the committed field.
                for(int i=0;i<8 && game.prompt!=null && game.prompt.kind!=11;i++){game.auto();HostTestAwait.waiting(duel);}
                var tag=DuelBridge.projection(duel);
                helper.assertTrue(tag.getAllKeys().equals(Set.of("duel","dimension","x","y","z","yaw",
                        "p0l4","p0l8","p1l4","p1l8","attackEvent","attackerUid","targetUid")),
                        "Public wire format must not contain hands, decks, choices, history or private board bytes");
                var monsters=read(tag,"p0l4");
                helper.assertTrue(monsters.size()==5,"Public MR3 field must have five slots");
                var monster=monsters.stream().filter(CardView::present).findFirst().orElseThrow();
                if(action==3) {
                    helper.assertTrue(!monster.isKnown() && monster.cardId().isEmpty() && monster.atk()==0 && monster.def()==0,
                            "Even the owning player's set monster must be anonymized on the public channel");
                    helper.assertTrue(monster.position().isFaceDown(),"Public board must retain the face-down sleeve position");
                }else helper.assertTrue(monster.isKnown() && monster.cardId().equals("celtic_guardian"),
                        "Native face-up summons must reach bystanders");
                helper.assertTrue(!monster.activatable(),"Public projection must not expose action availability");
            }finally{duel.surrender(0);}
        }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void bystandersUseOriginalRangeAndDimension(GameTestHelper helper) {
        Duel duel=start(helper);
        try {
            var game=DuelBridge.game(duel);var server=helper.getLevel().getServer();
            var player=new ServerPlayer(server,server.getLevel(game.dimension),new GameProfile(UUID.randomUUID(),"Bystander"));
            player.setPos(game.anchorX+12,game.anchorY,game.anchorZ);
            helper.assertTrue(duel.sideOf(player.getUUID())<0,"Fixture must be an unrelated third player");
            helper.assertTrue(DuelBridge.canSeeProjection(duel,player),"Unrelated nearby players must receive the board");
            player.setPos(game.anchorX+32,game.anchorY,game.anchorZ);
            helper.assertTrue(DuelBridge.canSeeProjection(duel,player),"Original 32-block boundary remains visible");
            player.setPos(game.anchorX+32.1,game.anchorY,game.anchorZ);
            helper.assertTrue(!DuelBridge.canSeeProjection(duel,player),"Out-of-range viewers must stop receiving updates");
            var other=new ServerPlayer(server,server.getLevel(net.minecraft.world.level.Level.NETHER),new GameProfile(UUID.randomUUID(),"OtherDimension"));
            other.setPos(game.anchorX,game.anchorY,game.anchorZ);
            helper.assertTrue(!DuelBridge.canSeeProjection(duel,other),"Identical coordinates in another dimension must not receive the board");
        }finally{duel.surrender(0);}
        helper.succeed();
    }
}
