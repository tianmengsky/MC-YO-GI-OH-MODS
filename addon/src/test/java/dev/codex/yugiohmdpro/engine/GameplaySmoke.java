package dev.codex.yugiohmdpro.engine;

import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the actual Java request decoder against frozen traces and live core responses. */
public final class GameplaySmoke {
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); int decoded=0;
        int framed=0;
        for(String name:List.of("normal","chain-draw","fusion","ritual","synchro","xyz","pendulum")) {
            JsonArray trace=JsonParser.parseString(Files.readString(root.resolve("generated/mdpro-abi/"+name+"-trace.json"))).getAsJsonArray();
            for(var e:trace) {
                var event=e.getAsJsonObject();
                byte[] raw=HexFormat.of().parseHex(event.get("hex").getAsString());
                var messages=CoreMessages.split(raw);framed+=messages.size();
                check(messages.stream().mapToInt(m->m.length).sum()==raw.length,"Batch framing must preserve every byte");
                if((event.get("status").getAsInt()&0x10000000)==0) continue;
                var q=CorePrompt.parse(HexFormat.of().parseHex(event.get("hex").getAsString()));
                check(q.player==0 || q.player==1,"Prompt player");decoded++;
            }
        }
        // All stages of this test use the same parser/response encoder as the Minecraft UI.
        int summons=0,activations=0,drawn=0;
        List<Integer> deck=new ArrayList<>();
        for(int i=0;i<40;i++)deck.add(i%3==0?55144522:91152256);
        try(var runtime=new MdproRuntime(root.resolve("generated/native-runtime"));var game=runtime.create(17,deck,deck)) {
            game.start(true);
            for(int step=0;step<500 && (summons<1 || activations<1 || drawn<12);step++) {
                var batch=game.process();byte[] data=batch.bytes();
                if(data.length==0)continue;
                int kind=Byte.toUnsignedInt(data[0]);check(kind!=1,"Live UI response must not cause MSG_RETRY");
                if(kind==90)drawn+=Byte.toUnsignedInt(data[2]);
                if(!batch.waiting())continue;
                var q=CorePrompt.parse(data);int[] selected={0};boolean cancel=false;
                if(kind==11) {
                    int choice=-1;
                    for(int i=0;i<q.entries.size();i++) {
                        int type=q.entries.get(i).value()&0xffff;
                        if(type==0) {choice=i;summons++;break;}
                        if(type==5 && q.entries.get(i).code()==55144522) {choice=i;activations++;break;}
                    }
                    if(choice<0) for(int i=0;i<q.entries.size();i++) if(q.entries.get(i).value()==7)choice=i;
                    check(choice>=0,"End-turn choice");selected[0]=choice;
                } else if(kind==16) {
                    if(q.cancelable)cancel=true;
                } else if(kind==15 || kind==20) {
                    selected=new int[q.min];for(int i=0;i<selected.length;i++)selected[i]=i;
                } else if(kind==18 || kind==24) {
                    selected=new int[q.min];for(int i=0;i<selected.length;i++)selected[i]=i;
                } else if(kind!=12 && kind!=13 && kind!=14 && kind!=19) throw new AssertionError("Unexpected live prompt "+kind);
                byte[] response=q.response(selected,cancel,"");
                if(kind==15 || kind==20 || kind==18 || kind==24)game.respond(response);
                else game.respond(ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).getInt());
            }
        }
        check(summons>0 && activations>0 && drawn>=12,"Live summon/draw chain must complete");
        // Exact encoding for multi-card and sum requests, including fixed mandatory slots.
        CorePrompt sum=CorePrompt.parse(HexFormat.of().parseHex("1700000800000001020101000000000400020000000202000000000401030000000300000000040206000000"));
        byte[] r=sum.response(new int[]{2},false,"");
        check(Arrays.equals(r,new byte[]{2,0,1}),"Mandatory level 2 plus optional level 6 must reach 8");
        boolean rejected=false;
        try {sum.response(new int[]{1,2},false,"");}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Mandatory level 2 plus 3 and 6 must be rejected instead of sending an impossible sum");
        check(framed>decoded,"Multi-event batches must be framed");
        System.out.println("PASS: framed "+framed+" events; decoded "+decoded+" live-captured prompts across 7 mechanics; live Java summon="+summons+", spell="+activations+", drawn="+drawn+"; sum encoding");
    }
}
