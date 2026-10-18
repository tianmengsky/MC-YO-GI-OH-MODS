package dev.codex.yugiohmdpro.engine;

import java.nio.file.*;
import java.util.*;

/** Real frozen-DLL checks for response lifecycle, retries, and noninteractive WAIT yields. */
public final class ReliabilitySmoke {
    static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);
        check(!new MdproRuntime.Batch(0x10000000,new byte[]{2,9,0,1,0,0,0}).waiting(),"Result hint is a yield, not a question");
        check(!new MdproRuntime.Batch(0x10000000,new byte[0]).waiting(),"Empty yield needs no response");
        byte[] chainBytes=HexFormat.of().parseHex("10000100010000000000210000006c8389010002050a00000000");
        byte[] opaqueThenPrompt=new byte[3+chainBytes.length];opaqueThenPrompt[0]=6;opaqueThenPrompt[1]=0x12;opaqueThenPrompt[2]=0x34;
        System.arraycopy(chainBytes,0,opaqueThenPrompt,3,chainBytes.length);
        var framed=CoreMessages.split(opaqueThenPrompt);
        check(framed.size()==2 && framed.get(0)[0]==6 && framed.get(1)[0]==16,
                "Opaque core notifications must preserve a trailing interactive request");
        check(!new MdproRuntime.Batch(0x10000000,new byte[]{6,1,2,3}).waiting(),
                "An opaque noninteractive notification must not strand the session in WAIT");
        check(!MaterialSelection.valid(new int[]{1|(3<<16)},2,0),"A hole between alternative levels is not reachable");
        check(MaterialSelection.valid(new int[]{1|(3<<16),1},2,0),"Discrete alternatives can reach an exact sum");
        check(!MaterialSelection.valid(new int[]{3,3,3},6,1),"At-least materials cannot contain a redundant card");
        check(MaterialSelection.valid(new int[]{3,3},6,1),"Minimal at-least materials are legal");
        Random random=new Random(17);
        for(int test=0;test<500;test++) {
            int[] weights=new int[2+random.nextInt(7)];for(int i=0;i<weights.length;i++)weights[i]=(1+random.nextInt(6))|((random.nextBoolean()?0:1+random.nextInt(6))<<16);
            int forced=random.nextInt(2),min=random.nextInt(3),max=weights.length-forced,target=1+random.nextInt(24),mode=random.nextInt(2);boolean exists=false;
            for(int mask=0;mask<(1<<(weights.length-forced));mask++) {
                int count=Integer.bitCount(mask);if(count<min || count>max)continue;
                int[] values=new int[forced+count];System.arraycopy(weights,0,values,0,forced);int at=forced;
                for(int i=forced;i<weights.length;i++)if((mask&(1<<(i-forced)))!=0)values[at++]=weights[i];
                if(MaterialSelection.valid(values,target,mode))exists=true;
            }
            int[] answer=MaterialSelection.select(weights,forced,min,max,target,mode);
            check((answer!=null)==exists,"Material solver must agree with exhaustive subset enumeration, case="+test);
            if(answer!=null) {
                int[] values=new int[forced+answer.length];System.arraycopy(weights,0,values,0,forced);
                for(int i=0;i<answer.length;i++)values[forced+i]=weights[answer[i]];
                check(Arrays.stream(answer).distinct().count()==answer.length && MaterialSelection.valid(values,target,mode),"Solver returns distinct legal materials");
            }
        }
        var place=CorePrompt.parse(HexFormat.of().parseHex("120000e0ffffff"));
        check(Arrays.equals(place.response(new int[0],true,""),new byte[]{0,0,0}),"Optional placement cancels with a zero location triple");
        var race=CorePrompt.parse(HexFormat.of().parseHex("8c00020f000000"));
        check(Arrays.equals(race.response(new int[]{0,2},false,""),CorePrompt.integer(5)),"Multi-race declaration is a bitmask");
        var sort=CorePrompt.parse(HexFormat.of().parseHex("190003010000000004000200000000040103000000000402"));
        check(Arrays.equals(sort.response(new int[]{1,2,0},false,""),new byte[]{2,0,1}),"Sort encodes original-entry ranks");
        var counters=CorePrompt.parse(HexFormat.of().parseHex("16000100640001010000000004006400"));
        check(Arrays.equals(counters.counterResponse(new int[]{100}),new byte[]{100,0}),"Compressed counters support counts above the 63-index list limit");
        int complete=0,retries=0,declarations=0,yields=0;
        try(var runtime=new MdproRuntime(root.resolve("generated/native-runtime"))) {
            verifyDeclinedEffects(runtime);
            var monsters=runtime.declarableCodes(new int[]{1,0x40000102});
            check(monsters.contains(89631139) && !monsters.contains(43711255),"Card-name search must evaluate native type predicates");
            for(int spell:new int[]{0,43711255,58577036}) {
                var deck=new ArrayList<Integer>();
                for(int i=0;i<40;i++)deck.add(spell==0 || i<35?91152256:spell);
                try(var game=runtime.create(19,deck,Collections.nCopies(40,91152256))) {
                    game.start(true);boolean injected=false,activated=false,won=false;
                    CorePrompt last=null;
                    for(int step=0;step<10000;step++) {
                        var batch=game.process();var events=CoreMessages.split(batch.bytes());
                        byte[] tail=events.isEmpty()?new byte[0]:events.get(events.size()-1);
                        for(byte[] event:events)if(event[0]==5)won=true;
                        if(won)break;
                        if(batch.ended())throw new AssertionError("No winner before end");
                        if(!batch.waiting()) {if((batch.status()&0x10000000)!=0)yields++;continue;}
                        check(game.waiting(),"Session/batch waiting must agree");
                        if(tail[0]==1) {retries++;check(last!=null,"Retry retains last request");}
                        else {last=CorePrompt.parse(tail);last.prepareDeclaration(runtime);}
                        if(!injected && last.kind==11) {game.respond(Integer.MAX_VALUE);injected=true;continue;}
                        int[] selected={0};boolean cancel=false;String text="";
                        if(last.kind==11 || last.kind==10) {
                            int pick=-1;
                            if(!activated && last.player==0 && spell!=0)
                                for(int i=0;i<last.entries.size();i++)if(last.entries.get(i).code()==spell && (last.entries.get(i).value()&0xffff)==5){pick=i;activated=true;break;}
                            if(pick<0)for(int i=0;i<last.entries.size();i++)if(last.entries.get(i).command() && (last.entries.get(i).value()==7 || last.entries.get(i).value()==3)){pick=i;break;}
                            check(pick>=0,"Can end turn");selected[0]=pick;
                        } else if(last.kind==16)cancel=last.cancelable;
                        else if(last.kind==142) {
                            declarations++;System.out.println("DECLARE payload="+HexFormat.of().formatHex(tail)+" candidates="+last.allowed.size());
                            // Select through the actual server search/response path.
                            last.entries.add(new CorePrompt.Entry("Blue Eyes",89631139,89631139,0));text="89631139";
                        } else if(last.multiple) {
                            selected=new int[last.min];for(int i=0;i<selected.length;i++)selected[i]=i;
                        }
                        last.replyTo(game,last.response(selected,cancel,text));
                    }
                    check(won,"Full match reaches native winner for spell "+spell);complete++;
                    if(spell!=0)check(activated,"Spell scenario must activate "+spell);
                }
            }
        }
        check(retries==3,"Each match must recover from one rejected native answer");
        check(declarations>0,"Must exercise announce-card");
        System.out.println("PASS: reliability full_matches="+complete+" retries="+retries+" declarations="+declarations+" noninteractive_yields="+yields);
    }

    /** A real quick-play effect offered by the frozen DLL, tested both negatively and positively. */
    private static void verifyDeclinedEffects(MdproRuntime runtime)throws Exception {
        var apiField=MdproRuntime.class.getDeclaredField("api");apiField.setAccessible(true);
        var api=(OcgApi)apiField.get(runtime);
        var pointerField=MdproRuntime.Session.class.getDeclaredField("duel");pointerField.setAccessible(true);
        for(int copies:new int[]{1,2})for(boolean accept:new boolean[]{false,true})try(var game=runtime.create(29,Collections.nCopies(40,91152256),Collections.nCopies(40,91152256))) {
            game.start(true);
            var pointer=(com.sun.jna.Pointer)pointerField.get(game);
            for(int i=0;i<copies;i++)api.new_card(pointer,25789292,(byte)0,(byte)0,(byte)2,(byte)i,(byte)8); // Forbidden Chalice
            api.new_card(pointer,91152256,(byte)0,(byte)0,(byte)4,(byte)0,(byte)1);
            boolean offered=false,activated=false,finished=false;CorePrompt last=null;
            for(int step=0;step<300;step++) {
                var batch=game.process();var events=CoreMessages.split(batch.bytes());
                for(byte[] event:events) {
                    check(event[0]!=1,"Declining optional effects must not cause MSG_RETRY");
                    if(event[0]==70 && java.nio.ByteBuffer.wrap(event,1,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()==25789292)activated=true;
                    if(offered && event[0]==40)finished=true;
                }
                if(activated || finished)break;
                if(!batch.waiting())continue;
                last=CorePrompt.parse(events.get(events.size()-1));
                if(last.kind==16)System.out.println("CHAIN fixture accept="+accept+" cancelable="+last.cancelable+" raw="+HexFormat.of().formatHex(events.get(events.size()-1)));
                int[] selected={0};boolean cancel=false;
                if(last.kind==16) {
                    boolean chalice=last.player==0 && last.entries.stream().anyMatch(e->e.code()==25789292);
                    if(chalice) {
                        offered=true;
                        check(last.cancelable,"Actual optional quick-play chain must never be misread as forced");
                        check(last.entries.size()==copies && last.entries.stream().allMatch(e->e.code()==25789292 && e.location()==2),"Every native chain candidate must retain its code and location");
                        byte[] forced=events.get(events.size()-1).clone();forced[13]=1;
                        CorePrompt mandatory=CorePrompt.parse(forced);
                        check(!mandatory.cancelable,"Forced flag is byte 3, independent of candidate count and timing");
                        boolean rejected=false;try{mandatory.response(new int[0],true,"");}catch(IllegalArgumentException expected){rejected=true;}
                        check(rejected,"Actual mandatory chain format must reject decline");
                        for(int i=0;i<last.entries.size();i++)if(last.entries.get(i).code()==25789292){selected=new int[]{i};break;}
                        cancel=!accept;
                    } else cancel=last.cancelable;
                } else if(last.kind==10 || last.kind==11) {
                    int end=-1;for(int i=0;i<last.entries.size();i++)if(last.entries.get(i).command() && (last.entries.get(i).value()==7 || last.entries.get(i).value()==3))end=i;
                    check(end>=0,"Fixture can end turn");selected=new int[]{end};
                } else if(last.kind==12 || last.kind==13)cancel=!accept;
                else if(last.multiple) {selected=new int[last.min];for(int i=0;i<selected.length;i++)selected[i]=i;}
                byte[] answer=last.response(selected,cancel,"");
                if(cancel && last.kind==16)check(Arrays.equals(answer,CorePrompt.integer(-1)),"No chain must reach native as integer -1");
                if(cancel && (last.kind==12 || last.kind==13))check(Arrays.equals(answer,CorePrompt.integer(0)),"No effect must reach native as integer 0");
                last.replyTo(game,answer);
            }
            check(offered,"Native core must offer the actual optional quick-play effect");
            check(activated==accept,"Only accepting may activate Forbidden Chalice; accept="+accept+" activated="+activated);
        }
        System.out.println("PASS: actual optional chain decline does not activate; positive control does activate");
    }
}
