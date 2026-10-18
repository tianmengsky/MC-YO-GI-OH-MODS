package dev.codex.yugiohmdpro.engine;

import java.nio.*;
import java.util.*;
import com.google.gson.*;

/** GUI choice model and validated reply encoding. Production requests come from the reference host. */
public final class CorePrompt {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("MDProDuel");
    /**
     * One selectable entry. {@code note} is the short structured verb (通常召唤 / 发动 / 攻击 …) and
     * {@code detail} the effect text, so the client never has to split the human-readable
     * {@code label}. Entries with {@code player < 0} are engine commands (advance phase, end turn,
     * tidy hand) rather than cards.
     */
    public record Entry(String label,int code,int value,int weight,int player,int location,int sequence,String note,String detail) {
        public Entry(String label,int code,int value,int weight) { this(label,code,value,weight,-1,0,-1,"",""); }
        public Entry(String label,int code,int value,int weight,int player,int location,int sequence) {
            this(label,code,value,weight,player,location,sequence,"","");
        }
        public Entry(String label,int code,int value,int weight,int player,int location,int sequence,String note) {
            this(label,code,value,weight,player,location,sequence,note,"");
        }
        /** True for engine commands such as 进入战斗阶段 / 结束回合 / 整理手牌. */
        public boolean command() { return player<0; }
        public Entry withDetail(String value) {
            return new Entry(label,code,this.value,weight,player,location,sequence,note,value);
        }
    }
    public final int kind, player;
    public String title = "请选择";
    public final List<Entry> entries = new ArrayList<>();
    /** Evaluated declaration candidates; the wire payload itself is an RPN predicate. */
    public final Set<Integer> allowed = new HashSet<>();
    private int[] declarationProgram=new int[0];
    public void prepareDeclaration(MdproRuntime runtime) {
        if(kind==142) {allowed.clear();allowed.addAll(runtime.declarableCodes(declarationProgram));}
    }
    public void prepareDeclaration(ReferenceHost runtime) {
        if(kind==142) {allowed.clear();allowed.addAll(runtime.declarableCodes(declarationProgram));}
    }
    public int min = 1, max = 1, mandatory, target, mode;
    public boolean multiple, cancelable, finishable;
    private CorePrompt(int kind, int player) { this.kind=kind; this.player=player; }
    private void option(String label, int value) { entries.add(new Entry(label,0,value,0)); }
    /** Option that keeps the card code, so the client can show the card it belongs to. */
    private void option(String label, int value, int code) { entries.add(new Entry(label,code,value,0)); }
    private void card(ByteBuffer b, String verb, int value, int extraBytes) {
        int code=b.getInt() & 0x7fffffff, p=u(b), l=u(b), s=u(b);
        int extra = extraBytes == 4 ? b.getInt() : extraBytes == 2 ? Short.toUnsignedInt(b.getShort()) : extraBytes == 1 ? u(b) : 0;
        if(p!=player && (l==1 || l==2 || (l==4 || l==8 || l==32) && extraBytes==1 && (extra&5)==0)) code=0;
        boolean described = extraBytes == 4 && (kind==10 || kind==11 || kind==16);
        String detail = described ? DuelCatalog.description(extra) : "";
        entries.add(new Entry(verb + DuelCatalog.name(code) + " · " + DuelCatalog.location(p,l,s)
                + (described ? " · " + detail : ""),code,value,extra,p,l,s,action(verb),detail));
    }
    /** "通常召唤：" -> "通常召唤", so the GUI can label the action without splitting the label. */
    private static String action(String verb) {
        int colon=verb.lastIndexOf('：');
        return colon<0?verb:verb.substring(0,colon);
    }
    private static int u(ByteBuffer b) { return Byte.toUnsignedInt(b.get()); }
    /** Cancellation uses ivalue=-1 even for requests whose normal response is a byte list. */
    public void replyTo(MdproRuntime.Session session, byte[] answer) {
        boolean cancelled = Arrays.equals(answer, integer(-1));
        boolean list = switch(kind) {case 15,18,20,22,23,24,25,26 -> true;default -> false;};
        if(cancelled || !list) {
            if(answer.length!=4) throw new IllegalArgumentException("Integer response must be 4 bytes");
            session.respond(ByteBuffer.wrap(answer).order(ByteOrder.LITTLE_ENDIAN).getInt());
        } else session.respond(answer);
    }
    public void replyTo(ReferenceHost.Session session, byte[] answer) {
        boolean list=switch(kind){case 15,18,20,22,23,24,25,26->true;default->false;};
        if(Arrays.equals(answer,integer(-1)) || !list) {
            if(answer.length!=4)throw new IllegalArgumentException("Integer response must be 4 bytes");
            session.respond(ByteBuffer.wrap(answer).order(ByteOrder.LITTLE_ENDIAN).getInt());
        } else session.respond(answer);
    }
    private static int number(JsonObject o,String key,int fallback){return o.has(key)?o.get(key).getAsInt():fallback;}
    private static boolean flag(JsonObject o,String key){return o.has(key)&&o.get(key).getAsBoolean();}
    private static JsonArray list(JsonObject o,String key){return o.has(key)?o.getAsJsonArray(key):new JsonArray();}
    private void hostCards(JsonObject host,String key,String verb,int action,boolean described,String weightKey) {
        for(JsonElement item:list(host,key)) {
            JsonObject c=item.getAsJsonObject();int index=number(c,"index",0),code=number(c,"code",0)&0x7fffffff;
            int p=number(c,"controller",player),loc=number(c,"location",0),seq=number(c,"sequence",0),pos=number(c,"position",0);
            if(p!=player&&(loc==1||loc==2||loc==64||(loc==4||loc==8||loc==32)&&(pos&5)==0))code=0;
            int value=action<0?index:(index<<16)|action;
            if(key.equals("unselectCards"))value+=list(host,"cards").size();
            String detail=described?DuelCatalog.description(number(c,"description",0)):"";
            entries.add(new Entry(verb+DuelCatalog.name(code)+" · "+DuelCatalog.location(p,loc,seq)+(detail.isEmpty()?"":" · "+detail),
                code,value,weightKey.isEmpty()?0:number(c,weightKey,0),p,loc,seq,action(verb),detail));
        }
    }
    /** Translate the example host's choices without inferring forced status from timing or turn. */
    public static CorePrompt fromHost(JsonObject host) {
        int k=number(host,"function",-1),p=number(host,"player",-1);
        if(k==21){byte[] raw=HexFormat.of().parseHex(host.get("raw").getAsString());raw[0]=25;return parse(raw);}
        // The example GUI omits structured sum/counter/declaration metadata. Keep the existing GUI's
        // detailed editors using the exact raw request already framed by its host.
        if(k==22||k==23||k==132||k==140||k==141||k==142||k==143)
            return parse(HexFormat.of().parseHex(host.get("raw").getAsString()));
        if(p<0||p>1)throw new IllegalArgumentException("Reference prompt has invalid player");
        CorePrompt q=new CorePrompt(k,p);
        switch(k) {
            case 10,11 -> {
                q.title=k==11?"主要阶段：选择行动":"战斗阶段：选择行动";
                String[] keys=k==11?new String[]{"summonCards","spsummonCards","positionCards","monsterSetCards","spellSetCards","activateCards"}:new String[]{"activateCards","attackCards"};
                String[] verbs=k==11?new String[]{"通常召唤：","特殊召唤：","变更表示：","盖放怪兽：","盖放魔陷：","发动："}:new String[]{"发动：","攻击："};
                for(int a=0;a<keys.length;a++)q.hostCards(host,keys[a],verbs[a],a,keys[a].equals("activateCards"),"");
                if(flag(host,k==11?"toBattle":"toMain2"))q.option(k==11?"进入战斗阶段":"进入主要阶段 2",k==11?6:2);
                if(flag(host,"toEnd"))q.option("结束回合",k==11?7:3);
            }
            case 12,13 -> {
                int code=number(host,"code",0);q.title=k==12?"是否发动 "+DuelCatalog.name(code)+"？ "+DuelCatalog.description(number(host,"description",0)):DuelCatalog.description(number(host,"description",0));
                q.option("是，发动",1,code);q.option("否",0,code);
            }
            case 14 -> {q.title="选择效果";for(JsonElement item:list(host,"options")){JsonObject o=item.getAsJsonObject();q.option(DuelCatalog.description(number(o,"description",0)),number(o,"index",0));}}
            case 15,20 -> {q.cancelable=flag(host,"cancelable");q.min=number(host,"min",1);q.max=number(host,"max",1);q.multiple=true;q.title=k==20?"选择解放的怪兽":"选择卡片";q.hostCards(host,"cards","",-1,false,k==20?"releases":"");}
            case 16 -> {q.cancelable=!flag(host,"forced");q.title=q.cancelable?"是否连锁？":"选择必须发动的效果";q.hostCards(host,"cards","连锁：",-1,true,"");
                if(q.entries.size()!=number(host,"count",-1))throw new IllegalArgumentException("Incomplete reference chain prompt");}
            case 18,24 -> {int count=number(host,"count",1);q.min=q.max=Math.max(1,count);q.multiple=true;q.cancelable=k==18&&count==0;q.title=k==18?"选择放置区域":"选择禁用区域";
                for(JsonElement item:list(host,"places")){JsonObject c=item.getAsJsonObject();int pl=number(c,"player",p),loc=number(c,"location",4),seq=number(c,"sequence",0);q.entries.add(new Entry(DuelCatalog.location(pl,loc,seq),0,pl|(loc<<8)|(seq<<16),0,pl,loc,seq));}}
            case 19 -> {q.title="选择表示形式";String[] labels={"表侧攻击表示","里侧攻击表示","表侧守备表示","里侧守备表示"};for(JsonElement item:list(host,"options")){int v=number(item.getAsJsonObject(),"index",0);if(v!=1&&v!=2&&v!=4&&v!=8)throw new IllegalArgumentException("Invalid position");q.option(labels[Integer.numberOfTrailingZeros(v)],v);}}
            case 25 -> {q.title="按顺序选择卡片（先点击的排在前面）";q.min=q.max=number(host,"count",0);q.multiple=true;q.cancelable=true;q.hostCards(host,"cards","",-1,false,"");}
            case 26 -> {q.title="选择 / 取消素材，完成后点击确认结束";q.finishable=flag(host,"finish");q.cancelable=flag(host,"cancelable");q.min=number(host,"min",0);q.max=number(host,"max",1);q.hostCards(host,"cards","选择：",-1,false,"");q.hostCards(host,"unselectCards","取消选择：",-1,false,"");}
            default -> throw new IllegalArgumentException("尚未适配的示例宿主请求："+k);
        }
        if(k==10||k==11) {
            // The example GUI drops activation descriptions and the hand-shuffle command. Restore
            // those display fields from its already-framed raw payload; response indices stay host-owned.
            CorePrompt metadata=parse(HexFormat.of().parseHex(host.get("raw").getAsString()));
            for(int i=0;i<q.entries.size();i++) {
                Entry e=q.entries.get(i);
                for(Entry raw:metadata.entries)if(raw.value()==e.value()&&raw.player()==e.player()&&raw.location()==e.location()&&raw.sequence()==e.sequence()) {
                    if(!raw.detail().isEmpty())q.entries.set(i,new Entry(raw.label(),e.code(),e.value(),e.weight(),e.player(),e.location(),e.sequence(),e.note(),raw.detail()));
                    break;
                }
            }
            for(Entry e:metadata.entries)if(e.command()&&e.value()==8)q.entries.add(e);
        }
        return q;
    }
    public static CorePrompt parse(byte[] bytes) {
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int k=u(b), p=u(b), sumMode=0;
        if(k==23) { sumMode=p; p=u(b); }
        CorePrompt q=new CorePrompt(k,p); q.mode=sumMode;
        switch(k) {
            case 10,11 -> {
                q.title=k==11 ? "主要阶段：选择行动" : "战斗阶段：选择行动";
                String[] verbs=k==11 ? new String[]{"通常召唤：","特殊召唤：","变更表示：","盖放怪兽：","盖放魔陷：","发动："} : new String[]{"发动：","攻击："};
                for(int g=0;g<verbs.length;g++) {
                    int count=u(b);
                    for(int i=0;i<count;i++) q.card(b,verbs[g],(i<<16)|g,k==11 ? (g==5?4:0) : (g==0?4:1));
                }
                if(u(b)!=0) q.option(k==11?"进入战斗阶段":"进入主要阶段 2",k==11?6:2);
                if(u(b)!=0) q.option("结束回合",k==11?7:3);
                if(k==11 && u(b)!=0) q.option("整理手牌",8);
            }
            case 12 -> {
                int code=b.getInt(); b.getInt(); int desc=b.getInt();
                q.title="是否发动 " + DuelCatalog.name(code) + "？ " + DuelCatalog.description(desc);
                q.option("是，发动",1,code); q.option("否",0,code);
            }
            case 13 -> { q.title=DuelCatalog.description(b.getInt()); q.option("是",1); q.option("否",0); }
            case 14,143 -> {
                q.title=k==143?"宣言数字":"选择效果"; int count=u(b);
                for(int i=0;i<count;i++) { int value=b.getInt(); q.option(k==143?Integer.toString(value):DuelCatalog.description(value),i); }
            }
            case 15,20 -> {
                q.cancelable=u(b)!=0; q.min=u(b); q.max=u(b); q.multiple=true;
                q.title=k==20?"选择解放的怪兽":"选择卡片"; int count=u(b);
                for(int i=0;i<count;i++) q.card(b,"",i,1);
            }
            case 16 -> {
                int count=u(b);
                // Reference host layout: special count, two timings, then per-entry flag + forced.
                if(bytes.length!=12+count*14)throw new IllegalArgumentException("Invalid frozen chain request length");
                u(b); b.getInt(); b.getInt();boolean forced=false;
                for(int i=0;i<count;i++) { u(b);forced|=u(b)!=0; q.card(b,"连锁：",i,0); b.get(); int desc=b.getInt();
                    Entry old=q.entries.remove(q.entries.size()-1); String text=DuelCatalog.description(desc);
                    q.entries.add(new Entry(old.label()+" · "+text,old.code(),old.value(),0,old.player(),old.location(),old.sequence(),
                            old.note(),text)); }
                q.title=forced?"选择必须发动的效果":"是否连锁？"; q.cancelable=!forced;
            }
            case 18,24 -> {
                int rawMin=u(b); q.min=q.max=Math.max(1,rawMin); q.multiple=true; int forbidden=b.getInt();
                // The engine permits "place nothing" exactly when it sends min=0; MDPro3 makes that cancelable.
                if(k==18 && rawMin==0) q.cancelable=true;
                q.title=k==18?"选择放置区域":"选择禁用区域";
                for(int bit=0;bit<32;bit++) if((forbidden&(1<<bit))==0) {
                    int pl=p^(bit/16), loc=bit%16<8?4:8, seq=bit%8;
                    q.entries.add(new Entry(DuelCatalog.location(pl,loc,seq),0,pl|(loc<<8)|(seq<<16),0,pl,loc,seq));
                }
            }
            case 19 -> {
                int code=b.getInt(), positions=u(b); q.title="选择表示形式："+DuelCatalog.name(code);
                String[] labels={"表侧攻击表示","里侧攻击表示","表侧守备表示","里侧守备表示"};
                for(int i=0;i<4;i++) if((positions&(1<<i))!=0) q.option(labels[i],1<<i,code);
                if(q.entries.isEmpty())throw new IllegalArgumentException("Invalid position mask: "+positions);
            }
            case 22 -> {
                int type=Short.toUnsignedInt(b.getShort()); q.target=Short.toUnsignedInt(b.getShort());
                q.multiple=true; q.min=0; q.max=q.target; q.title="移除 "+q.target+" 个指示物（点击卡片增加数量）";
                int count=u(b); for(int i=0;i<count;i++) q.card(b,"",i,2);
            }
            case 23 -> {
                q.target=b.getInt(); q.min=u(b); q.max=u(b); q.multiple=true;
                q.title="选择素材："+(sumMode==0?"合计恰好 ":"合计至少 ")+q.target;
                q.mandatory=u(b); for(int i=0;i<q.mandatory;i++) q.card(b,"必选：",i,4);
                int count=u(b); for(int i=0;i<count;i++) q.card(b,"",i,4);
                if(sumMode!=0) {q.min=0;q.max=count;}
            }
            case 25 -> {
                q.min=q.max=u(b); q.multiple=true; q.cancelable=true;
                q.title="按顺序选择卡片（先点击的排在前面）";
                for(int i=0;i<q.max;i++) q.card(b,"",i,0);
            }
            case 26 -> {
                q.finishable=u(b)!=0; q.cancelable=u(b)!=0; q.min=u(b); q.max=u(b);
                q.title="选择 / 取消素材，完成后点击确认结束";
                int count=u(b); for(int i=0;i<count;i++) q.card(b,"选择：",i,1);
                int other=u(b); for(int i=0;i<other;i++) q.card(b,"取消选择：",count+i,1);
            }
            case 132 -> { q.title="猜拳"; q.option("石头",1); q.option("剪刀",2); q.option("布",3); }
            case 140,141 -> {
                q.min=q.max=u(b); q.multiple=true; int mask=b.getInt(); q.title=k==140?"宣言种族":"宣言属性";
                String[] races={"战士","魔法师","天使","恶魔","不死","机械","水","炎","岩石","鸟兽","植物","昆虫","雷","龙","兽","兽战士","恐龙","鱼","海龙","爬虫类","念动力","幻神兽","创造神","幻龙","电子界"};
                String[] attrs={"地","水","炎","风","光","暗","神"};
                for(int i=0;i<(k==140?races.length:attrs.length);i++) if((mask&(1<<i))!=0) q.option((k==140?races:attrs)[i],1<<i);
            }
            case 142 -> {
                q.title="宣言卡名：输入中文卡名或卡号搜索";
                int count=u(b);
                if(b.remaining()!=count*4)throw new IllegalArgumentException("Invalid card declaration predicate");
                q.declarationProgram=new int[count];
                for(int i=0;i<count;i++)q.declarationProgram[i]=b.getInt();
            }
            default -> throw new IllegalArgumentException("尚未支持的内核请求："+k);
        }
        if(b.hasRemaining())throw new IllegalArgumentException("Interactive request has trailing bytes: "+b.remaining());
        if(q.player<0 || q.player>1) throw new IllegalArgumentException("Invalid prompt player");
        return q;
    }
    public byte[] response(int[] selected, boolean cancel, String text) {
        if(cancel) {
            // Declining an effect yes/no is 0; declining a chain selection is -1.
            // Never substitute the first (affirmative) option for a GUI cancellation.
            if(kind==12 || kind==13)return integer(0);
            if(!cancelable && !finishable) throw new IllegalArgumentException("此选择不能取消");
            if(kind==18)return new byte[]{(byte)player,0,0};
            return integer(-1);
        }
        if(kind==142) {
            int code=Integer.parseInt(text);
            if(!allowed.contains(code) || selected.length!=1 || selected[0]<0 || selected[0]>=entries.size() || entries.get(selected[0]).value()!=code)
                throw new IllegalArgumentException("请先搜索并选择要宣言的卡片");
            return integer(code);
        }
        if(selected.length>(kind==22?65535:63)) throw new IllegalArgumentException("选择数量过多");
        Set<Integer> seen=new HashSet<>();
        for(int i:selected) if(i<0 || i>=entries.size() || (kind!=22 && !seen.add(i))) throw new IllegalArgumentException("无效或重复选择");
        if(!multiple) {
            if(selected.length!=1) throw new IllegalArgumentException("请选择一项");
            if(kind==26) return new byte[]{1,(byte)entries.get(selected[0]).value()};
            return integer(entries.get(selected[0]).value());
        }
        if(kind!=20 && kind!=22 && kind!=23 && (selected.length<min || selected.length>max)) throw new IllegalArgumentException("选择数量不符合要求");
        if(kind==22) {
            int[] counts=new int[entries.size()];for(int i:selected)counts[i]++;
            return counterResponse(counts);
        }
        if(kind==18 || kind==24) {
            ByteBuffer b=ByteBuffer.allocate(selected.length*3);
            for(int i:selected) { int v=entries.get(i).value(); b.put((byte)v).put((byte)(v>>8)).put((byte)(v>>16)); } return b.array();
        }
        if(kind==140 || kind==141) { int value=0; for(int i:selected) value|=entries.get(i).value(); return integer(value); }
        if(kind==25) { byte[] b=new byte[selected.length]; for(int i=0;i<selected.length;i++) b[selected[i]]=(byte)i; return b; }
        if(kind==20 && (selected.length>max || Arrays.stream(selected).map(i->entries.get(i).weight()).sum()<min))
            throw new IllegalArgumentException("解放数量或解放值不符合要求");
        if(kind==23 && !validMaterials(selected))throw new IllegalArgumentException("素材数量或合计不符合要求");
        if(kind==23 && selected.length+mandatory>63) throw new IllegalArgumentException("素材过多");
        byte[] reply=new byte[selected.length+mandatory+1]; reply[0]=(byte)(selected.length+mandatory);
        for(int i=0;i<mandatory;i++) reply[i+1]=(byte)i;
        for(int i=0;i<selected.length;i++) {
            int index=selected[i]; if(kind==23 && index<mandatory) throw new IllegalArgumentException("必选素材已自动计入");
            reply[1+mandatory+i]=(byte)entries.get(index).value();
        }
        return reply;
    }
    public boolean validMaterials(int[] selected) {
        if(selected.length<min || selected.length>max)return false;
        int[] weights=new int[mandatory+selected.length];
        for(int i=0;i<mandatory;i++)weights[i]=entries.get(i).weight();
        for(int i=0;i<selected.length;i++) {
            if(selected[i]<mandatory || selected[i]>=entries.size())return false;
            weights[mandatory+i]=entries.get(selected[i]).weight();
        }
        return MaterialSelection.valid(weights,target,mode);
    }
    public byte[] counterResponse(int[] counts) {
        if(kind!=22 || counts.length!=entries.size() || counts.length>32)throw new IllegalArgumentException("指示物分配无效");
        ByteBuffer b=ByteBuffer.allocate(counts.length*2).order(ByteOrder.LITTLE_ENDIAN);int total=0;
        for(int i=0;i<counts.length;i++) {
            int count=counts[i];if(count<0 || count>entries.get(i).weight())throw new IllegalArgumentException("指示物不足");
            total+=count;b.putShort((short)count);
        }
        if(total!=target)throw new IllegalArgumentException("指示物数量不正确");return b.array();
    }
    public static byte[] integer(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
}
