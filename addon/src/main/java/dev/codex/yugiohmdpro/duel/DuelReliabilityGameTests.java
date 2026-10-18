package dev.codex.yugiohmdpro.duel;

import com.kiki.yugioh.card.*;
import com.kiki.yugioh.duel.*;
import com.kiki.yugioh.item.DeckContents;
import dev.codex.yugiohmdpro.engine.CorePrompt;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("yugioh_mdpro_addon")
@PrefixGameTestTemplate(false)
public final class DuelReliabilityGameTests {
    private static Duel start(GameTestHelper h,int starter,boolean spells) {
        var deck=new ArrayList<DeckContents.DeckEntry>();
        for(int i=0;i<40;i++)deck.add(new DeckContents.DeckEntry(Cards.byId(spells && i<5?"prohibition":"celtic_guardian"),CardRarity.COMMON));
        var duel=new Duel(h.getLevel().getServer(),h.absolutePos(BlockPos.ZERO),
                new DuelSide(UUID.randomUUID(),"Reliability A",deck,List.of()),
                new DuelSide(UUID.randomUUID(),"Reliability B",deck,List.of()));
        dev.codex.yugiohfix.duel.DuelRules.set(duel,new dev.codex.yugiohfix.duel.DuelRules.Rules(spells,true,false,false));
        duel.start(starter);HostTestAwait.waiting(duel);
        h.assertTrue(DuelBridge.active(duel),"Actual Forge/native takeover must start");return duel;
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void completeNaturalDuelsBothStartersAndDeclarations(GameTestHelper h) {
        int before=DuelBridge.closedCount(),completed=0;
        for(boolean spells:new boolean[]{false,true})for(int starter:new int[]{0,1}) {
            Duel duel=start(h,starter,spells);int steps=0,declarations=0;
            try {
                var game=DuelBridge.game(duel);
                while(!duel.isFinished() && steps++<12000) {
                    if(game.prompt==null){HostTestAwait.waiting(duel);}
                    else {if(game.prompt.kind==142)declarations++;game.auto();}
                }
                h.assertTrue(duel.isFinished() && duel.winner()>=0,"Full native AI game must reach a natural winner, starter="+starter+" spells="+spells);
                if(spells)h.assertTrue(declarations>0,"AI must actually answer constrained card declarations");
                h.assertTrue(!DuelBridge.active(duel),"Natural finish must close the session");completed++;
            } finally {if(!duel.isFinished())DuelBridge.cancel(duel,h.getLevel().getServer(),Component.literal("Test cleanup"));}
        }
        h.assertTrue(completed==4 && DuelBridge.closedCount()==before+4,"Every full match closes exactly once");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void rejectedDuplicateAndStaleAnswersRecover(GameTestHelper h) {
        Duel duel=start(h,0,false);
        try {
            var game=DuelBridge.game(duel);CorePrompt q=game.prompt;int revision=game.revision;
            game.pump();h.assertTrue(game.prompt==q && game.revision==revision,"Pumping while waiting preserves the live request");
            game.answer(1,revision,new int[]{0},false,"");
            h.assertTrue(game.prompt==q && game.revision==revision,"Opponent cannot answer this player's question");
            game.answer(0,revision,new int[]{Integer.MAX_VALUE},false,"");
            h.assertTrue(!duel.isFinished() && game.session.waiting() && game.revision==revision+1,"Rejected GUI answer must unlock a new revision");
            game.answer(0,revision,new int[]{0},false,"");
            h.assertTrue(game.revision==revision+1,"Stale revision cannot reach native response");
            game.lastPrompt=q;game.prompt=null;game.session.respond(Integer.MAX_VALUE);HostTestAwait.waiting(duel);
            h.assertTrue(game.prompt!=null && game.prompt.kind==q.kind && game.session.waiting() && game.retries==1,"Real MSG_RETRY must reissue the original GUI");
            revision=game.revision;int end=-1;
            for(int i=0;i<q.entries.size();i++)if(q.entries.get(i).command() && q.entries.get(i).value()==7)end=i;
            h.assertTrue(end>=0,"Native main phase offers end turn");
            game.answer(0,revision,new int[]{end},false,"");HostTestAwait.waiting(duel);int next=game.revision;
            game.answer(0,revision,new int[]{end},false,"");
            h.assertTrue(!duel.isFinished() && game.revision==next && game.session.waiting(),"Duplicate answer must not consume the next question");
        }finally{DuelBridge.cancel(duel,h.getLevel().getServer(),Component.literal("Test cleanup"));}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void isolatedSessionsAndCancellationIsNotDraw(GameTestHelper h) {
        Duel a=start(h,0,false),b=start(h,1,false);int before=DuelBridge.closedCount();
        try {
            var second=DuelBridge.game(b);CorePrompt q=second.prompt;int revision=second.revision;
            DuelBridge.game(a).auto();
            h.assertTrue(second.prompt==q && second.revision==revision && second.session.waiting(),"Interleaved games keep independent pending requests");
            DuelBridge.cancel(a,h.getLevel().getServer(),Component.literal("Injected cancellation"));
            h.assertTrue(a.isFinished() && a.winner()==-2 && !DuelBridge.active(a),"Cancellation is explicitly without a result");
            DuelBridge.cancel(a,h.getLevel().getServer(),Component.literal("Duplicate cancel"));
            h.assertTrue(DuelBridge.closedCount()==before+1 && DuelBridge.active(b),"Cancel closes once and leaves other matches live");
            b.endDuel(-1,Component.literal("Rules draw fixture"));
            h.assertTrue(b.winner()==-1 && DuelBridge.closedCount()==before+2,"An actual rules draw retains the legacy draw outcome");
        } finally {
            if(!a.isFinished())DuelBridge.cancel(a,h.getLevel().getServer(),Component.literal("Cleanup"));
            if(!b.isFinished())DuelBridge.cancel(b,h.getLevel().getServer(),Component.literal("Cleanup"));
        }
        h.succeed();
    }
}
