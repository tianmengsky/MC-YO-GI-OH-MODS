package dev.codex.yugiohmdpro.engine;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real host -> GUI choice model -> validated reply -> core regressions. */
public final class ReferenceHostSmoke {
    private static final int FILLER=91152256,MAXX=23434538,CYBER=70095154,GOBLIN=25259669;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static List<Integer> deck(int... opening){var result=new ArrayList<Integer>(Collections.nCopies(40-opening.length,FILLER));for(int code:opening)result.add(code);return result;}
    private static final class Trace {
        int turnPlayer,turn,expectedSummon;boolean cyberSummoned,maxxActivated,selectedSummoned,sanganActivated;
        void observe(byte[] bytes){if(bytes.length==0)return;int k=bytes[0]&255;
            if(k==1)throw new AssertionError("Reference core rejected response");
            if(k==40){turnPlayer=bytes[1]&255;turn++;}
            if(k==63)cyberSummoned=true;
            if(k==70&&code(bytes)==MAXX)maxxActivated=true;
            if(k==70&&code(bytes)==26202165)sanganActivated=true;
            if(k==62&&code(bytes)==expectedSummon)selectedSummoned=true;
        }
        static int code(byte[] b){return ByteBuffer.wrap(b,1,4).order(ByteOrder.LITTLE_ENDIAN).getInt()&0x7fffffff;}
    }
    private static CorePrompt next(ReferenceHost.Session session,Trace trace) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end){var batch=session.process();trace.observe(batch.bytes());
            if(batch.waiting()){check(session.fieldInfo()[1]==3,"Must use Master Rule 3");return CorePrompt.fromHost(batch.prompt());}
            check(!batch.ended(),"Duel ended before expected state");if(batch.bytes().length==0)Thread.sleep(2);
        }
        throw new AssertionError("Timed out waiting for reference prompt");
    }
    private static int find(CorePrompt q,java.util.function.Predicate<CorePrompt.Entry> predicate){for(int i=0;i<q.entries.size();i++)if(predicate.test(q.entries.get(i)))return i;return -1;}
    private static void choose(ReferenceHost.Session s,CorePrompt q,int index){check(index>=0,"Missing action: "+q.title+" "+q.entries);q.replyTo(s,q.response(new int[]{index},false,""));}
    private static void answer(ReferenceHost.Session s,CorePrompt q){
        if(q.kind==16){check(q.cancelable,"Unexpected forced effect in fixture");q.replyTo(s,q.response(new int[0],true,""));return;}
        if(q.kind==12||q.kind==13){choose(s,q,find(q,e->e.value()==1));return;}
        if(q.kind==10||q.kind==11){choose(s,q,find(q,e->e.command()&&(e.value()==7||e.value()==3)));return;}
        if(q.kind==26){q.replyTo(s,q.response(q.finishable?new int[0]:new int[]{0},q.finishable,""));return;}
        if(q.kind==23){int[] weights=q.entries.stream().mapToInt(CorePrompt.Entry::weight).toArray();int[] selected=MaterialSelection.select(weights,q.mandatory,q.min,q.max,q.target,q.mode);check(selected!=null,"Missing materials");q.replyTo(s,q.response(selected,false,""));return;}
        int[] indices=new int[q.multiple?q.min:1];for(int i=0;i<indices.length;i++)indices[i]=i;q.replyTo(s,q.response(indices,false,""));
    }
    public static void maxx(ReferenceHost runtime,boolean activate) throws Exception {
        try(var s=runtime.create(1,deck(MAXX,FILLER,FILLER,FILLER,FILLER),List.of(),deck(CYBER,FILLER,FILLER,FILLER,FILLER),List.of())){
            s.start(false);Trace t=new Trace();boolean ownDeclined=false,otherDeclined=false,normal=false,cyberSent=false,accepted=false;
            for(int step=0;step<250;step++){
                CorePrompt q=next(s,t);int maxx=find(q,e->e.code()==MAXX);
                if(q.kind==16&&maxx>=0){
                    check(q.cancelable,"Maxx C must be optional on both turns, including special=1");
                    if(activate&&t.cyberSummoned&&!accepted){choose(s,q,maxx);accepted=true;}
                    else {q.replyTo(s,q.response(new int[0],true,""));if(t.turnPlayer==0)ownDeclined=true;else if(t.cyberSummoned)otherDeclined=true;}
                }else if(q.kind==11&&q.player==0&&!normal){choose(s,q,find(q,e->e.code()==FILLER&&(e.value()&65535)==0));normal=true;}
                else if(q.kind==11&&q.player==1&&!cyberSent){choose(s,q,find(q,e->e.code()==CYBER&&(e.value()&65535)==1));cyberSent=true;}
                else answer(s,q);
                if(t.cyberSummoned&&(activate?t.maxxActivated:otherDeclined)&&q.kind==11&&q.player==1){
                    check(ownDeclined,"Own-turn optional chain must have been declined");check(activate==t.maxxActivated,"Declining must never activate Maxx C");
                    System.out.println("PASS reference Maxx C own/opponent turn "+(activate?"activate":"decline")+", actual Cyber Dragon summon");return;
                }
            }
            throw new AssertionError("Maxx C regression did not complete");
        }
    }
    private static void extra(ReferenceHost runtime,int aiSeat) throws Exception {
        int human=aiSeat==0?1:0;
        var main=deck(FILLER,FILLER,FILLER,FILLER,GOBLIN);
        var extras=List.of(84013237,12014404,84013237);
        try(var s=runtime.create(1,main,human==0?extras:List.of(),main,human==1?extras:List.of())){
            s.start(false,aiSeat);Trace t=new Trace();boolean normal=false,selected=false;int chosenCode=0;
            for(int step=0;step<200;step++){
                CorePrompt q=next(s,t);
                if(t.selectedSummoned&&q.kind==11){check(selected,"Extra monster must have been chosen");System.out.println("PASS reference extra deck: non-first candidate "+chosenCode+", materials consumed, aiSeat="+aiSeat);return;}
                if(q.kind==11&&q.player==human&&!normal){choose(s,q,find(q,e->e.code()==GOBLIN&&(e.value()&65535)==0));normal=true;}
                else if(q.kind==11&&q.player==human&&!selected){
                    var candidates=new ArrayList<Integer>();for(int i=0;i<q.entries.size();i++){var e=q.entries.get(i);if(e.location()==64&&(e.value()&65535)==1)candidates.add(i);}
                    check(candidates.size()>=3,"All eligible extra monsters must be exposed: "+q.entries);
                    check(candidates.stream().map(i->q.entries.get(i).sequence()).distinct().count()==3,"Duplicate copies need distinct extra addresses");
                    int choice=candidates.get(1);chosenCode=q.entries.get(choice).code();t.expectedSummon=chosenCode;choose(s,q,choice);selected=true;
                }else answer(s,q);
            }
            throw new AssertionError("Extra deck regression did not complete");
        }
    }
    private static void concurrent(ReferenceHost runtime) throws Exception {
        try(var a=runtime.create(9,deck(),List.of(),deck(),List.of());var b=runtime.create(10,deck(),List.of(),deck(),List.of())){
            a.start(false);b.start(false);Trace ta=new Trace(),tb=new Trace();
            for(int i=0;i<5;i++){answer(a,next(a,ta));answer(b,next(b,tb));}
            a.close();answer(b,next(b,tb));check(tb.turn>1,"Other duel must progress after first closes");System.out.println("PASS reference concurrent session/close isolation");
        }
    }
    private static void mandatory(ReferenceHost runtime,int aiSeat) throws Exception {
        try(var s=runtime.create(1,deck(FILLER,FILLER,FILLER,53129443,26202165),List.of(),deck(),List.of())) {
            s.start(false,aiSeat);Trace t=new Trace();boolean summoned=false,darkHole=false,forcedSeen=false;
            for(int step=0;step<120;step++) {
                CorePrompt q=next(s,t);
                if(q.kind==16&&find(q,e->e.code()==26202165)>=0) {
                    check(!q.cancelable,"Sangan's real mandatory trigger must remain mandatory");
                    boolean rejected=false;try{q.response(new int[0],true,"");}catch(IllegalArgumentException expected){rejected=true;}
                    check(rejected,"GUI validation must reject cancelling a mandatory trigger");forcedSeen=true;choose(s,q,0);
                } else if(q.kind==11&&!summoned) {choose(s,q,find(q,e->e.code()==26202165&&(e.value()&65535)==0));summoned=true;}
                else if(q.kind==11&&!darkHole) {choose(s,q,find(q,e->e.code()==53129443&&(e.value()&65535)==5));darkHole=true;}
                else {if(t.sanganActivated&&q.kind==11){System.out.println("PASS reference real mandatory Sangan trigger and resolution, aiSeat="+aiSeat+" (single trigger auto chains)");return;}answer(s,q);}
            }
            throw new AssertionError("Mandatory trigger fixture did not complete");
        }
    }
    private static void ai(ReferenceHost runtime,int seat) throws Exception {
        try(var s=runtime.create(7,deck(),List.of(),deck(),List.of())) {
            s.start(false,seat);Trace t=new Trace();
            for(int step=0;step<80;step++) {
                CorePrompt q=next(s,t);check(q.player!=seat,"Reference Windbot must answer its own prompts");
                if(t.turn>=4){System.out.println("PASS reference Windbot seat "+seat+" with human opponent");return;}
                answer(s,q);
            }
            throw new AssertionError("Reference Windbot did not advance turns");
        }
    }
    private static void retry(ReferenceHost runtime) throws Exception {
        try(var s=runtime.create(7,deck(),List.of(),deck(),List.of())) {
            s.start(false);Trace t=new Trace();CorePrompt first=next(s,t);check(first.kind==11,"Retry fixture must start at main phase");s.respond(Integer.MAX_VALUE);
            int rejected=0;CorePrompt restored=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline) {
                var batch=s.process();if(batch.bytes().length>0&&batch.bytes()[0]==1)rejected++;
                if(batch.waiting()){restored=CorePrompt.fromHost(batch.prompt());break;}Thread.sleep(2);
            }
            check(rejected==1&&restored!=null&&restored.kind==first.kind,"Rejected reply must reissue exactly one original prompt");
            answer(s,restored);next(s,t);System.out.println("PASS reference rejected native reply recovers without a retry loop");
        }
    }
    public static void main(String[] args) throws Exception {
        try(var runtime=new ReferenceHost(Path.of(args[0]).resolve("generated/reference-test-runtime"))){maxx(runtime,false);maxx(runtime,true);mandatory(runtime,-1);mandatory(runtime,1);extra(runtime,-1);extra(runtime,1);extra(runtime,0);concurrent(runtime);ai(runtime,0);ai(runtime,1);retry(runtime);}
        System.out.println("PASS reference host migration smoke");
    }
}
