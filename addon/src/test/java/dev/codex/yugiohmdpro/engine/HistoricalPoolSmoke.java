package dev.codex.yugiohmdpro.engine;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.*;
import java.util.*;
/** Initializes all reviewed cards in the actual DLL, including official-inventory additions. */
public final class HistoricalPoolSmoke {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);var data=JsonParser.parseString(Files.readString(root.resolve("generated/content/resources/data/yugioh_mdpro_addon/historical-content.json"))).getAsJsonObject();
        var normal=new ArrayList<Integer>();var extra=new ArrayList<Integer>();
        for(var c:data.getAsJsonArray("cards")) {
            var o=c.getAsJsonObject();int code=o.get("code").getAsInt(),t=o.get("type").getAsInt();
            if(!DuelCatalog.allowed(code))throw new AssertionError("Admission mismatch "+code);
            ((t&(0x40|0x2000|0x800000))==0?normal:extra).add(code);
        }
        int checked=0;
        try(var runtime=new MdproRuntime(root.resolve("generated/native-runtime"))) {
            verifyHarpies(runtime);
            for(int i=0,j=0;i<normal.size()||j<extra.size();) {
                int ni=Math.min(normal.size(),i+40),nj=Math.min(extra.size(),j+15);
                try(var session=runtime.create(177,normal.subList(i,ni),extra.subList(j,nj),List.of(),List.of())){}
                checked+=ni-i+nj-j;i=ni;j=nj;
            }
        }
        if(checked!=8231)throw new AssertionError("Pool cardinality "+checked);
        System.out.println("PASS: all "+checked+" reviewed cards initialized by the frozen native DLL and Lua scripts; effect-resolution acceptance remains separate.");
    }
    private static void verifyHarpies(MdproRuntime runtime) {
        for(int code:List.of(27927359,54415063,80316585,91932350)) {
            try(var game=runtime.create(12,List.of(code),List.of())) {
                var query=ByteBuffer.wrap(game.query(0,1,5)).order(ByteOrder.LITTLE_ENDIAN);
                if(query.getInt()!=16||query.getInt()!=5||query.getInt()!=code||query.getInt()!=76812113)
                    throw new AssertionError("Permanent Harpie name adapter "+code);
            }
        }
        boolean summoned=false;
        try(var game=runtime.create(17,Collections.nCopies(40,91932350),Collections.nCopies(40,91152256))) {
            game.start(true);
            for(int step=0;step<200;step++) {
                var batch=game.process();
                var field=ByteBuffer.wrap(game.query(0,4,0x105)).order(ByteOrder.LITTLE_ENDIAN);
                while(field.remaining()>=4) {
                    int start=field.position(),size=field.getInt();
                    if(size>4) {
                        int flags=field.getInt(),code=field.getInt(),name=field.getInt(),atk=field.getInt();
                        if(flags!=0x105||code!=91932350||name!=76812113)throw new AssertionError("Harpie Lady 1 lost its identity or permanent name");
                        // MSG_SUMMONING exposes the card before continuous effects apply.
                        if(atk==1600)summoned=true;
                    }
                    field.position(start+size);
                }
                if(summoned)break;
                if(batch.ended())throw new AssertionError("Harpie smoke ended before summon");
                if(!batch.waiting())continue;
                var q=CorePrompt.parse(batch.bytes());int chosen=0;
                if(q.kind==11) {
                    chosen=-1;
                    for(int i=0;i<q.entries.size();i++)if((q.entries.get(i).value()&0xffff)==0){chosen=i;break;}
                    if(chosen<0)throw new AssertionError("Harpie must offer normal summon");
                }
                byte[] response=q.response(new int[]{chosen},q.kind==16&&q.cancelable,"");
                if(q.kind==15||q.kind==20||q.kind==18||q.kind==24)game.respond(response);
                else game.respond(ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).getInt());
            }
        }
        if(!summoned)throw new AssertionError("Harpie summon timed out");
        System.out.println("PASS: all four Harpie variants keep permanent rule name; Harpie Lady 1 resolves WIND attack aura to 1600 ATK after normal summon.");
    }
}
