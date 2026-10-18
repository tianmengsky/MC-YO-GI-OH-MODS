package dev.codex.yugiohmdpro.engine;

import dev.codex.yugiohmdpro.duel.DuelLog;
import dev.codex.yugiohmdpro.duel.DuelPacer;
import dev.codex.yugiohmdpro.duel.DuelPacing;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Duel presentation pacing, verified against the real reference host.
 *
 * <p>The defect this guards: {@code DuelBridge.pump()} drained the host queue in one server tick and
 * published the duel screen only when the engine asked a player something. In a recorded real duel
 * that meant 99 screen updates for 226 visible actions, and the AI's 208 decisions produced none at
 * all. The fix publishes one beat per visible action and holds the queue for that action's animation
 * length; this smoke asserts the properties that make that correct.
 */
public final class PacingSmoke {
    private static final Set<Integer> EXPECTED_VISIBLE=Set.of(5,40,41,50,53,54,55,60,61,62,63,64,65,
            90,91,92,94,100,101,102,110,111);
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    private static final class Stats {
        int visible,prompts,beats,changes,folded,holds,ticks,maxQueued,events;
        int windowBeats,maxWindowBeats,windowWithMax;boolean ended;
        final List<Integer> windows=new ArrayList<>();
        long previous=Long.MIN_VALUE;
        void message(int kind,long fingerprint,DuelPacer pacer){
            events++;
            if(!DuelPacing.visible(kind))return;
            visible++;
            if(fingerprint!=previous){changes++;previous=fingerprint;}
            if(pacer.beat(kind,fingerprint))beats++;
            else folded++;
            holds+=pacer.hold();
            while(!pacer.ready()){pacer.tick();ticks++;}
            windowBeats++;
        }
        void prompt(){
            prompts++;windows.add(windowBeats);
            if(windowBeats>maxWindowBeats){maxWindowBeats=windowBeats;windowWithMax=windows.size();}
            windowBeats=0;
        }
        String summary(String label){
            return label+": events="+events+" visible="+visible+" prompts="+prompts+" beats="+beats
                    +" folded="+folded+" distinctStates="+changes+" holdTicks="+holds
                    +" pacedSeconds~"+String.format("%.1f",(holds+prompts*DuelPacing.aiDelay())/20.0)
                    +" maxQueued="+maxQueued+" maxBeatsPerRequest="+maxWindowBeats+"@"+windowWithMax;
        }
    }

    /** Policy and state machine: no host, no Minecraft, fully deterministic. */
    static void pure(){
        Set<Integer> seen=new TreeSet<>();
        for(int kind=0;kind<256;kind++)if(DuelPacing.visible(kind))seen.add(kind);
        check(seen.equals(new TreeSet<>(EXPECTED_VISIBLE)),
                "Visible set must stay identical to the adapter snapshot list: "+seen);
        int[] all={5,40,41,50,53,54,55,60,61,62,63,64,65,90,91,92,94,100,101,102,110,111};
        DuelPacing.mode(DuelPacing.NORMAL);
        for(int kind:all){
            int normal=DuelPacing.holdTicks(kind);
            check(normal>=1 && normal<=60,"Normal hold out of range for "+kind+" = "+normal);
            DuelPacing.mode(DuelPacing.FAST);
            int fast=DuelPacing.holdTicks(kind);
            DuelPacing.mode(DuelPacing.SLOW);
            int slow=DuelPacing.holdTicks(kind);
            DuelPacing.mode(DuelPacing.INSTANT);
            int instant=DuelPacing.holdTicks(kind);
            check(instant==0,"Instant mode must not hold ("+kind+")");
            check(fast<=normal && normal<=slow,"Speed ordering broken for "+kind+": "+fast+"/"+normal+"/"+slow);
            DuelPacing.mode(DuelPacing.NORMAL);
        }
        check(DuelPacing.narrative(40)&&DuelPacing.narrative(41)&&DuelPacing.narrative(5)
                &&!DuelPacing.narrative(50),"Narrative set must cover turn/phase/win only");
        check(DuelPacing.aiDelay()==12&&DuelPacing.parse("fast")==DuelPacing.FAST
                &&DuelPacing.parse("nonsense")==DuelPacing.NORMAL,"Mode parsing broken");

        DuelPacer pacer=new DuelPacer(false);
        check(pacer.beat(50,1L),"A changed state must beat");
        check(pacer.hold()==DuelPacing.holdTicks(50)&&!pacer.ready(),"A beat must hold for its animation");
        while(!pacer.ready())pacer.tick();
        check(!pacer.beat(61,1L),"A visible message with an unchanged state must fold into the last beat");
        check(pacer.hold()==0&&pacer.ready(),"Folding must not hold");
        check(pacer.beat(41,1L),"A phase change must beat even when the board is unchanged");
        check(pacer.beats()==2&&pacer.folded()==1,"Beat accounting broken: "+pacer.beats()+"/"+pacer.folded());
        DuelPacer instantPacer=new DuelPacer(true);
        check(instantPacer.beat(50,1L)&&instantPacer.beat(61,1L)&&instantPacer.hold()==0,
                "Instant pacer must publish every visible message");
        System.out.println("PASS pacing policy: "+all.length+" visible kinds, hold table, speed ordering, fold/beat rules");
    }

    /**
     * The duel log must describe every action, pin the frozen message offsets, and never name a card
     * the opponent is not allowed to see. One history string is sent to both players, so a leak here
     * would be a rules defect, not a cosmetic one.
     */
    static void logs(){
        int blue=89631139;
        check(!DuelCatalog.name(blue).startsWith("卡号"),"Log fixture card must exist in the catalog");

        byte[] destroyed=message(50,17);
        put32(destroyed,1,blue);putGps(destroyed,5,0,4,0,1);putGps(destroyed,9,0,16,0,1);put32(destroyed,13,1);
        List<String> lines=DuelLog.describe(50,destroyed);
        check(lines.size()==1&&lines.get(0).contains(DuelCatalog.name(blue)),
                "A public destruction must name the card: "+lines);
        check(lines.get(0).contains("怪兽区 1")&&lines.get(0).contains("墓地")&&lines.get(0).contains("（破坏）"),
                "A move must show both zones and the destruction mark: "+lines);

        byte[] set=message(50,17);
        put32(set,1,blue);putGps(set,5,0,2,0,0);putGps(set,9,0,4,1,8);put32(set,13,0);
        lines=DuelLog.describe(50,set);
        check(lines.size()==1&&lines.get(0).contains("里侧卡片")&&!lines.get(0).contains(DuelCatalog.name(blue)),
                "A card set from hand must stay anonymous in the shared log: "+lines);
        check(DuelLog.describe(54,new byte[]{54,0,0,0,0,0,4,0,0}).equals(List.of("盖放了一张卡")),
                "Setting a card must not reveal its name or type");

        byte[] toHand=message(50,17);
        put32(toHand,1,blue);putGps(toHand,5,0,1,0,0);putGps(toHand,9,0,2,0,0);put32(toHand,13,0);
        check(DuelLog.describe(50,toHand).get(0).contains("里侧卡片"),
                "A card added from the deck to a hand must stay anonymous");

        byte[] draw=message(90,7);
        draw[1]=0;draw[2]=1;put32(draw,3,blue);
        lines=DuelLog.describe(90,draw);
        check(lines.size()==1&&lines.get(0).contains("抽 1 张")&&!lines.get(0).contains(DuelCatalog.name(blue)),
                "Draws are private: the count is public, the cards are not: "+lines);

        byte[] damage=message(91,6);damage[1]=0;put32(damage,2,1200);
        check(DuelLog.describe(91,damage).equals(List.of("P1 受到伤害 1200")),
                "Damage must read as a plain sentence: "+DuelLog.describe(91,damage));

        check(DuelLog.describe(50,new byte[]{50,0,0}).isEmpty(),
                "A truncated message must log nothing instead of throwing");
        check(DuelLog.describe(41,new byte[]{41,0,0}).isEmpty(),"Phase banners are not log lines");
        System.out.println("PASS duel log: frozen ABI offsets and the public-information rule");
    }

    private static byte[] message(int kind,int size){byte[] data=new byte[size];data[0]=(byte)kind;return data;}
    private static void put32(byte[] data,int at,int value){
        data[at]=(byte)value;data[at+1]=(byte)(value>>8);data[at+2]=(byte)(value>>16);data[at+3]=(byte)(value>>24);
    }
    private static void putGps(byte[] data,int at,int player,int location,int sequence,int position){
        data[at]=(byte)player;data[at+1]=(byte)location;data[at+2]=(byte)sequence;data[at+3]=(byte)position;
    }

    private static int find(CorePrompt q,java.util.function.Predicate<CorePrompt.Entry> predicate){
        for(int i=0;i<q.entries.size();i++)if(predicate.test(q.entries.get(i)))return i;return -1;
    }
    private static void choose(ReferenceHost.Session session,CorePrompt q,int index){
        check(index>=0,"Missing action: "+q.title+" "+q.entries);
        q.replyTo(session,q.response(new int[]{index},false,""));
    }
    /** The project's own bot policy, so a paced duel exercises real summons, sets, attacks and chains. */
    static void answer(ReferenceHost.Session session,CorePrompt q){
        int k=q.kind;
        if(k==16){
            if(q.cancelable){q.replyTo(session,q.response(new int[0],true,""));return;}
            choose(session,q,0);return;
        }
        if(k==12||k==13){int yes=find(q,e->e.value()==1);choose(session,q,yes>=0?yes:0);return;}
        if(k==10||k==11){
            int index=-1;
            for(int i=0;i<q.entries.size();i++){int type=q.entries.get(i).value()&0xffff;
                if(k==11&&(type==0||type==1||type==5)||k==10&&type==1){index=i;break;}}
            if(index<0)for(int i=0;i<q.entries.size();i++){int type=q.entries.get(i).value()&0xffff;
                if(k==11&&(type==6||type==7)||k==10&&(type==2||type==3)){index=i;break;}}
            choose(session,q,index);return;
        }
        if(k==26){q.replyTo(session,q.response(q.finishable?new int[0]:new int[]{0},q.finishable,""));return;}
        if(k==23){int[] weights=q.entries.stream().mapToInt(CorePrompt.Entry::weight).toArray();
            int[] selected=MaterialSelection.select(weights,q.mandatory,q.min,q.max,q.target,q.mode);
            check(selected!=null,"Missing materials");q.replyTo(session,q.response(selected,false,""));return;}
        if(k==142){int code=q.allowed.stream().filter(DuelCatalog::allowed).min(Integer::compare).orElseThrow();
            q.entries.clear();q.entries.add(new CorePrompt.Entry(DuelCatalog.name(code),code,code,0));
            q.replyTo(session,q.response(new int[]{0},false,Integer.toString(code)));return;}
        int[] indices=new int[q.multiple?q.min:1];for(int i=0;i<indices.length;i++)indices[i]=i;
        q.replyTo(session,q.response(indices,false,""));
    }

    /**
     * Drives one real duel through the pacer with simulated server ticks. {@code seat>=0} hands that
     * seat to Windbot, so the host answers it internally and Java only sees the human's requests —
     * the production layout.
     */
    private static Stats run(ReferenceHost runtime,String label,int seed,int seat,int promptBudget) throws Exception {
        List<Integer> deck=new ArrayList<>();
        for(int i=0;i<40;i++)deck.add(91152256);
        try(var session=runtime.create(seed,deck,List.of(),deck,List.of())){
            session.start(false,seat);
            Stats stats=new Stats();
            DuelPacer pacer=new DuelPacer(DuelPacing.instant());
            int turn=0,phase=1;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(240);
            while(System.nanoTime()<deadline){
                while(!pacer.ready()){pacer.tick();stats.ticks++;}
                var batch=session.process();
                stats.maxQueued=Math.max(stats.maxQueued,session.queuedEvents());
                if(batch.bytes().length==0){
                    if(batch.ended()){stats.ended=true;break;}
                    Thread.sleep(1);
                    continue;
                }
                byte[] data=batch.bytes();
                int kind=data.length==0?0:data[0]&255;
                if(kind==40)turn++;
                if(kind==41)phase=(data[1]&255)|((data[2]&255)<<8);
                stats.message(kind,session.stateFingerprint()*31+turn*7+phase,pacer);
                if(kind==5){stats.ended=true;break;}
                if(batch.waiting()){
                    CorePrompt q=CorePrompt.fromHost(batch.prompt());
                    stats.prompt();
                    answer(session,q);
                    if(stats.prompts>=promptBudget)break;
                }
            }
            System.out.println("  "+stats.summary(label));
            return stats;
        }
    }

    /** Human against human: every visible action must survive as its own beat. */
    static void humanPaced(ReferenceHost runtime) throws Exception {
        Stats stats=run(runtime,"human vs human",11,-1,60);
        check(stats.prompts>=20,"Fixture did not reach enough requests: "+stats.summary("human"));
        check(stats.beats>=stats.changes,"A visible state change was skipped: "+stats.summary("human"));
        check(stats.beats>stats.prompts,"Pacing must publish more often than the engine raises requests");
        check(stats.holds>0&&stats.ticks>0,"A paced duel must spend time holding animations");
        check(stats.folded>0,"Consecutive core messages folded into one batch should not cost a beat");
        check(stats.maxQueued<4096,"Host event queue overflowed: "+stats.maxQueued);
        System.out.println("PASS paced human duel: "+stats.beats+" beats for "+stats.prompts
                +" requests ("+stats.prompts+" updates before this change)");
    }

    /** Human against Windbot: the AI's own actions must now become beats the player can see. */
    static void aiPaced(ReferenceHost runtime) throws Exception {
        Stats stats=run(runtime,"human vs Windbot seat 0",21,0,40);
        check(stats.beats>stats.prompts,"AI actions must produce beats between the player's own requests");
        check(stats.maxWindowBeats>=2,"At least one request window must show several AI actions: "
                +stats.summary("ai"));
        long multi=stats.windows.stream().filter(w->w>=2).count();
        System.out.println("PASS paced AI duel: "+multi+" of "+stats.windows.size()
                +" request windows now show 2+ beats, worst window "+stats.maxWindowBeats+" beats");
    }

    public static void main(String[] args) throws Exception {
        pure();
        logs();
        Path root=Path.of(args[0]).resolve("generated/reference-test-runtime");
        DuelPacing.mode(DuelPacing.NORMAL);
        try(var runtime=new ReferenceHost(root)){
            humanPaced(runtime);
            aiPaced(runtime);
        }
        System.out.println("PASS duel pacing smoke");
    }
}
