package dev.codex.yugiohmdpro.client.ui;

import com.kiki.yugioh.network.CardView;
import dev.codex.yugiohmdpro.engine.DuelCatalog;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Structured view of the server's duel state: which interaction the engine is waiting for, which
 * entries the player may pick, and where each entry lives on the board.
 *
 * <p>All indices here are positions in the original {@code choices} list, which is exactly what the
 * server expects back, so the UI never has to re-derive a response.</p>
 */
public final class ChoiceModel {
    /** One selectable entry from the engine. */
    public static final class Choice {
        public final int index,code,value,action,player,location,sequence,weight;
        public final boolean command;
        public final String label,note,detail,name,text;
        Choice(int index,CompoundTag tag,int viewerSide) {
            this.index=index;
            this.label=tag.getString("label");
            this.code=tag.getInt("code");
            this.value=tag.getInt("value");
            this.action=tag.contains("action")?tag.getInt("action"):-1;
            this.command=tag.getBoolean("command");
            this.player=tag.getInt("player");
            this.location=tag.getInt("location");
            this.sequence=tag.getInt("sequence");
            this.weight=tag.getInt("weight");
            this.note=tag.getString("note");
            this.detail=tag.getString("detail");
            // A card entry is named by its code; an engine option (position, effect, race, number)
            // carries the monster's code too, so its own label is the only correct text.
            this.name=code!=0?DuelCatalog.name(code):strip(label);
            this.text=strip(label);
        }
        public boolean isCard() { return !command && player>=0 && location!=0; }
        public String actionName() { return note.isEmpty()?"选择":note; }
        public String badge() { return ChoiceModel.actionBadge(action,note); }
        /** Text for an engine option, never the card name; falls back to the card name when empty. */
        public String optionText() { return text.isEmpty()?name:text; }
        /** Lowest and highest material value this entry may contribute (kind 23 encodes both). */
        public int low() { return weight&0xffff; }
        public int high() { return weight>>>16; }
        @Override public String toString() { return index+":"+name+(note.isEmpty()?"":"("+note+")"); }
    }

    private static final Pattern LOCATION_TAIL=Pattern.compile("^(.*) · P\\d+ [^·]*$");

    /** Removes the "· P1 怪兽区 3" tail and the "通常召唤：" prefix that the server embeds. */
    static String strip(String label) {
        String text=label;
        var matcher=LOCATION_TAIL.matcher(text);
        if(matcher.matches()) text=matcher.group(1);
        int colon=text.indexOf('：');
        if(colon>0 && colon<8) text=text.substring(colon+1);
        return text;
    }

    public final int kind,phase,turn,turnPlayer,mandatory,min,max,target,mode,viewerSide;
    public final boolean canAct,multiple,cancelable,finishable;
    public final String prompt,requirement,notice;
    public final List<Choice> choices=new ArrayList<>();
    /** Codes of the effects already on the current chain, in link order. */
    public final List<Integer> chain=new ArrayList<>();
    private final Map<String,List<Choice>> byZone=new HashMap<>();

    public ChoiceModel(CompoundTag tag,int viewerSide) {
        this.viewerSide=viewerSide;
        this.canAct=tag.getBoolean("canAct");
        this.kind=tag.contains("kind")?tag.getInt("kind"):-1;
        this.phase=tag.contains("phase")?tag.getInt("phase"):0;
        this.turn=tag.getInt("turn");
        this.turnPlayer=tag.contains("turnPlayer")?tag.getInt("turnPlayer"):-1;
        this.mandatory=tag.getInt("mandatory");
        this.min=tag.getInt("min");
        this.max=tag.getInt("max");
        this.target=tag.contains("target")?tag.getInt("target"):0;
        this.mode=tag.contains("mode")?tag.getInt("mode"):0;
        this.multiple=tag.getBoolean("multiple");
        this.cancelable=tag.getBoolean("cancelable");
        this.finishable=tag.getBoolean("finishable");
        this.prompt=tag.getString("prompt");
        this.requirement=tag.getString("requirement");
        this.notice=tag.getString("notice");
        ListTag list=tag.getList("choices",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++) {
            Choice choice=new Choice(i,(CompoundTag)list.get(i),viewerSide);
            choices.add(choice);
            if(choice.isCard()) byZone.computeIfAbsent(key(choice.player,choice.location,choice.sequence),k->new ArrayList<>()).add(choice);
        }
        for(var item:tag.getList("chain",Tag.TAG_COMPOUND)) {
            var link=(CompoundTag)item; chain.add(link.getInt("code"));
        }
    }
    private static String key(int player,int location,int sequence) { return player+"/"+location+"/"+sequence; }

    public List<Choice> at(int player,int location,int sequence) {
        return byZone.getOrDefault(key(player,location,sequence),List.of());
    }
    /** Entries that are engine commands (advance phase, end turn, tidy hand). */
    public List<Choice> commands() {
        List<Choice> result=new ArrayList<>();
        for(Choice choice:choices) if(choice.command) result.add(choice);
        return result;
    }
    /** Commands whose action value is one of the given engine values. */
    public Choice command(int... values) {
        for(Choice choice:commands()) for(int value:values) if(choice.action==value) return choice;
        return null;
    }
    public Choice byIndex(int index) { return index>=0 && index<choices.size()?choices.get(index):null; }
    public boolean idle() { return kind==10 || kind==11; }
    public boolean chain() { return kind==16; }
    public boolean yesNo() { return kind==12 || kind==13; }
    public boolean position() { return kind==19; }
    public boolean place() { return kind==18 || kind==24; }
    public boolean option() { return kind==14 || kind==143; }
    public boolean announce() { return kind==140 || kind==141; }
    public boolean declareName() { return kind==142; }
    public boolean counters() { return kind==22; }
    public boolean materials() { return kind==23; }
    public boolean sort() { return kind==25; }
    public boolean unselect() { return kind==26; }
    public boolean rockPaperScissors() { return kind==132; }
    /** True when the player picks cards one by one instead of choosing one menu entry. */
    public boolean cardPicking() {
        return kind==15 || kind==20 || kind==22 || kind==23 || kind==25 || kind==26;
    }
    public boolean cancelAllowed() { return yesNo() || cancelable || finishable; }
    public int target() { return min; }
    public String requirementText() {
        if(!multiple) return "选择 1 项";
        if(kind==23) return requirement.isEmpty()?"选择素材":"选择素材";
        if(kind==22) return requirement.isEmpty()?"分配指示物":"分配指示物";
        return "已选 "+min+"～"+max+" 项";
    }
    /** Number of entries the engine has already forced (kind 23 mandatory materials). */
    public int forced() { return mandatory; }

    /** Action verb for an idle command group (0..5); falls back to the stored note. */
    public static String actionName(int kind,int action,String note) {
        if(kind==10) return switch(action) { case 0->"发动"; case 1->"攻击"; default->note; };
        if(kind==11) return switch(action) {
            case 0->"通常召唤"; case 1->"特殊召唤"; case 2->"变更表示"; case 3->"盖放怪兽"; case 4->"盖放魔陷"; case 5->"发动";
            default->note;
        };
        return note.isEmpty()?"选择":note;
    }
    /** Single-character badge, because the Minecraft font has no duel icon set. */
    public static String actionBadge(int action,String note) {
        return switch(action) {
            case 0->"召"; case 1->"特"; case 2->"转"; case 3->"盖"; case 4->"覆"; case 5->"发"; case 6->"战"; case 7->"终"; case 8->"整";
            default->note.isEmpty()?"选":note.substring(0,1);
        };
    }
    /** Badge for the command entries, which carry engine values rather than group indices. */
    public static String commandBadge(int value) {
        return switch(value) { case 2->"主2"; case 3->"终"; case 6->"战"; case 7->"终"; case 8->"整"; default->"令"; };
    }
    public static String commandName(int value) {
        return switch(value) {
            case 2->"进入主要阶段 2"; case 3,7->"结束回合"; case 6->"进入战斗阶段"; case 8->"整理手牌"; default->"引擎指令 "+value;
        };
    }
    /** The engine's place-selection value, reconstructed from the entry the user clicked. */
    public static int placeValue(Choice choice) { return choice.player|(choice.location<<8)|(choice.sequence<<16); }

    public static String describeCard(CardView card) {
        if(card==null || !card.present()) return "空位";
        if(!card.isKnown()) return "里侧卡片";
        try { return DuelCatalog.name(DuelCatalog.code(card.cardId())); }
        catch(RuntimeException notMapped) { return card.cardId(); }
    }
}
