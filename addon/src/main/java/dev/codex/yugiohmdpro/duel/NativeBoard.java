package dev.codex.yugiohmdpro.duel;

import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.duel.*;
import com.kiki.yugioh.network.*;
import dev.codex.yugiohmdpro.engine.*;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import java.nio.*;
import java.util.*;

/** Display projection only. No legacy Duel action/legality method is consulted. */
public final class NativeBoard {
    private final Map<Integer,String> ids=new HashMap<>();
    private final Map<Integer,CardRarity> rarities=new HashMap<>();
    private final Map<String,Integer> identities=new HashMap<>();
    /** Card code behind every display identity, so the duel log can name a card from its uid. */
    private final Map<Integer,Integer> uidCodes=new HashMap<>();
    private int nextId=1;
    private final Set<Integer> attacked=new HashSet<>();
    private int attackEvent,attackerUid=-1,targetUid=-1;
    private int soundSequence;
    private final Deque<CompoundTag> sounds=new ArrayDeque<>();
    public NativeBoard(Duel duel) {
        for(int p=0;p<2;p++) {
            var side=duel.getSide(p);
            var cards=new ArrayList<>(side.deck);cards.addAll(side.extraDeck);
            for(var c:cards) {
                int code;
                try {code=DuelCatalog.code(c.definition.getId());}catch(IllegalArgumentException unsupported){continue;}
                ids.putIfAbsent(code,c.definition.getId());rarities.putIfAbsent(code,c.rarity);
            }
        }
    }
    private String id(int code) {return ids.computeIfAbsent(code,DuelCatalog::legacyId);}
    private List<CardView> cards(DuelBridge.Game g,int player,int location,int viewer) {
        byte[] bytes=g.session.query(player,location);
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        List<CardView> result=new ArrayList<>();
        while(b.remaining()>=4) {
            int start=b.position(),size=b.getInt();
            if(size<4 || start+size>bytes.length) throw new IllegalStateException("Invalid native display query");
            CardView view=CardView.EMPTY;
            if(size>8) {
                b.getInt();int code=b.getInt()&0x7fffffff,packed=b.getInt();
                b.getInt();b.getInt();b.getInt();int atk=b.getInt(),def=b.getInt();
                int sequence=packed>>>16&255,pos=packed>>>24;
                boolean own=g.legacy(player)==viewer;
                boolean hidden=location==1 || !own && (location==2 && (pos&5)==0 || location==64 && (pos&5)==0 || (location==4 || location==8) && (pos&5)==0) || location==32 && (pos&5)==0;
                CardPosition position=location==4?((pos&8)!=0?CardPosition.FACE_DOWN_DEFENSE:(pos&2)!=0?CardPosition.FACE_DOWN:
                        (pos&4)!=0?CardPosition.DEFENSE:CardPosition.ATTACK):
                        location==8 && (pos&5)==0 || hidden?CardPosition.FACE_DOWN:CardPosition.FACE_UP;
                boolean activatable=false;
                if(g.prompt!=null && g.prompt.player==player)
                    for(var e:g.prompt.entries) if(e.player()==player && e.location()==location && e.sequence()==sequence) {activatable=true;break;}
                String identity=player+":"+location+":"+sequence+":"+code;
                int uid=identities.computeIfAbsent(identity,k->nextId++);
                uidCodes.putIfAbsent(uid,code);
                view=new CardView(true,hidden?"":id(code),uid,position,hidden?0:atk,hidden?0:def,
                        attacked.contains(uid),false,activatable,hidden?CardRarity.COMMON:rarities.getOrDefault(code,CardRarity.COMMON));
            }
            result.add(view);b.position(start+size);
        }
        return result;
    }
    private static CardView at(List<CardView> list,int index) {return index<list.size()?list.get(index):CardView.EMPTY;}
    private static List<CardView> five(List<CardView> list) {
        List<CardView> result=new ArrayList<>();for(int i=0;i<5;i++)result.add(at(list,i));return result;
    }
    private SideView side(DuelBridge.Game g,int player,int viewer,int lp,CompoundTag tag,String prefix) {
        var spells=cards(g,player,8,viewer);
        var removed=cards(g,player,32,viewer);
        tag.putByteArray(prefix+"pendulum",encodeCards(List.of(at(spells,6),at(spells,7))));
        tag.putByteArray(prefix+"removed",encodeCards(removed));
        var extra=cards(g,player,64,viewer);
        return new SideView(g.duel.getSide(g.legacy(player)).playerName,lp,
                (int)cards(g,player,1,viewer).stream().filter(CardView::present).count(),extra.size(),
                cards(g,player,2,viewer),five(cards(g,player,4,viewer)),five(spells),at(spells,5),
                cards(g,player,16,viewer),extra.stream().filter(CardView::isKnown).toList(),g.normalUsed[player]);
    }
    /** Public world geometry only. Never serialize a participant snapshot to spectators. */
    public CompoundTag projection(DuelBridge.Game g) {
        var tag=new CompoundTag();
        tag.putUUID("duel",g.duel.getDuelId());
        tag.putString("dimension",g.dimension.location().toString());
        tag.putDouble("x",g.anchorX);tag.putDouble("y",g.anchorY);tag.putDouble("z",g.anchorZ);tag.putDouble("yaw",g.anchorYaw);
        for(int side=0;side<2;side++)for(int location:new int[]{4,8}) {
            var publicCards=five(cards(g,g.legacy(side),location,-1)).stream()
                    .map(c->new CardView(c.present(),c.cardId(),c.uid(),c.position(),c.atk(),c.def(),
                            c.hasAttacked(),false,false,c.rarity())).toList();
            tag.putByteArray("p"+side+"l"+location,encodeCards(publicCards));
        }
        tag.putInt("attackEvent",attackEvent);tag.putInt("attackerUid",attackerUid);tag.putInt("targetUid",targetUid);
        return tag;
    }
    public void write(DuelBridge.Game g,int viewer,int[] lp,CompoundTag tag) {
        var self=side(g,g.legacy(viewer),viewer,lp[g.legacy(viewer)],tag,"self");
        var foe=side(g,g.legacy(viewer^1),viewer,lp[g.legacy(viewer^1)],tag,"foe");
        Phase phase=switch(g.phase) {case 1->Phase.DRAW;case 2->Phase.STANDBY;case 4->Phase.MAIN1;case 256->Phase.MAIN2;case 512->Phase.END;default->Phase.BATTLE;};
        var display=new S2CDuelStatePacket(viewer,g.legacy(g.turnPlayer),g.turn,phase,g.finishing,-1,Component.empty(),0,
                self,foe,g.history.stream().map(s->(Component)Component.literal(s)).toList(),false,false,-1,Component.empty(),List.of(),0,-1);
        FriendlyByteBuf buf=new FriendlyByteBuf(Unpooled.buffer());
        try {display.encode(buf);byte[] data=new byte[buf.readableBytes()];buf.readBytes(data);tag.putByteArray("board",data);}
        finally {buf.release();}
        tag.putInt("viewer",viewer);
        tag.putInt("attackEvent",attackEvent);tag.putInt("attackerUid",attackerUid);tag.putInt("targetUid",targetUid);
        ListTag soundTags=new ListTag();sounds.forEach(s->soundTags.add(s.copy()));tag.put("sounds",soundTags);
        tag.putDouble("anchorX",g.anchorX);tag.putDouble("anchorY",g.anchorY);tag.putDouble("anchorZ",g.anchorZ);tag.putDouble("anchorYaw",g.anchorYaw);
    }
    public dev.codex.yugiohfix.duel.DuelPublicInfo publicInfo(DuelBridge.Game g,int viewer) {
        var own=g.duel.getSide(viewer).player(g.server);var opponent=g.duel.getSide(viewer^1).player(g.server);
        Map<Integer,Integer> counters=new HashMap<>();
        for(int p=0;p<2;p++)for(int loc:new int[]{4,8}) {
            var visible=cards(g,p,loc,viewer);
            var b=ByteBuffer.wrap(g.session.query(p,loc,0x20003)).order(ByteOrder.LITTLE_ENDIAN);
            while(b.remaining()>=4) {
                int start=b.position(),size=b.getInt();
                if(size<4 || start+size>b.limit())throw new IllegalStateException("Invalid counter query");
                if(size>8) {
                    b.getInt();b.getInt();int packed=b.getInt(),seq=packed>>>16&255,n=b.getInt(),total=0;
                    if(n<0 || n>(start+size-b.position())/4)throw new IllegalStateException("Invalid native counters");
                    for(int i=0;i<n;i++)total+=b.getInt()>>>16;
                    if(total>0 && seq<visible.size() && visible.get(seq).isKnown())counters.put(visible.get(seq).uid(),total);
                }
                b.position(start+size);
            }
        }
        String disk="yugioh_interaction_fix:duel_disk";
        if(opponent!=null) {
            var stack=com.kiki.yugioh.duel.DuelManager.findDuelDisk(opponent);
            if(!stack.isEmpty()) {
                var key=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
                if(key!=null)disk=key.toString();
            }
        }
        return new dev.codex.yugiohfix.duel.DuelPublicInfo(cards(g,g.legacy(viewer),32,viewer),cards(g,g.legacy(viewer^1),32,viewer),
                cards(g,g.legacy(viewer^1),2,viewer).stream().filter(CardView::isKnown).toList(),counters,g.duel.getSide(viewer^1).playerId,
                disk,
                dev.codex.yugiohfix.duel.DuelRules.cancelSurrender(g.duel),
                dev.codex.yugiohfix.duel.GloveAppearance.of(own),dev.codex.yugiohfix.duel.GloveAppearance.of(opponent));
    }
    public dev.codex.yugiohfix.sound.DuelSoundTracker.Snapshot animationSnapshot(DuelBridge.Game g) {
        int[] lp=g.lifePoints();var tag=new CompoundTag();
        var a=side(g,g.legacy(0),0,lp[g.legacy(0)],tag,"a");
        var b=side(g,g.legacy(1),0,lp[g.legacy(1)],tag,"b");
        return new dev.codex.yugiohfix.sound.DuelSoundTracker.Snapshot(a.lifePoints(),b.lifePoints(),a.deckCount(),b.deckCount(),
                a.hand().size(),b.hand().size(),soundCards(a.monsters(),null),soundCards(b.monsters(),null),
                soundCards(a.spells(),a.field()),soundCards(b.spells(),b.field()),a.normalSummonUsed(),b.normalSummonUsed(),
                a.hand().stream().map(CardView::uid).toList(),b.hand().stream().map(CardView::uid).toList());
    }
    private static List<dev.codex.yugiohfix.sound.DuelSoundTracker.CardState> soundCards(List<CardView> cards,CardView field) {
        var all=new ArrayList<>(cards);if(field!=null)all.add(field);
        return all.stream().map(c->new dev.codex.yugiohfix.sound.DuelSoundTracker.CardState(c.present(),c.uid(),c.position().isFaceDown(),c.hasAttacked())).toList();
    }
    public void observe(DuelBridge.Game g,byte[] data) {
        if(data.length==0)return;
        if(data[0]==40)attacked.clear();
        var message=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int kind=Byte.toUnsignedInt(data[0]);
        if(kind==70 && data.length>=5) {
            var definition=com.kiki.yugioh.card.Cards.byId(id(message.getInt(1)));
            sound(definition!=null && definition.isTrap()?"TRAP_ACTIVATE":definition!=null && definition.isSpell()?"SPELL_ACTIVATE":"MONSTER_ACTIVATE");
        }
        if(kind==50 && data.length>=17 && (message.getInt(13)&1)!=0 && (message.getInt(5)>>>8&255)==4)sound("MONSTER_DESTROY");
        if(Byte.toUnsignedInt(data[0])==110 && data.length>=9) {
            var b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);b.get();
            attackerUid=uidAt(g,b.getInt());targetUid=uidAt(g,b.getInt());
            if(attackerUid>=0)attacked.add(attackerUid);attackEvent++;
            sound(targetUid<0?"DIRECT_ATTACK":"ATTACK");
        }
    }
    private void sound(String cue) {
        var tag=new CompoundTag();tag.putInt("sequence",++soundSequence);tag.putString("cue",cue);sounds.addLast(tag);
        while(sounds.size()>32)sounds.removeFirst();
    }
    private int uidAt(DuelBridge.Game g,int packed) {
        int player=packed&255,loc=packed>>>8&255,sequence=packed>>>16&255;
        if(player>1 || loc!=4)return -1;
        var list=cards(g,player,loc,g.legacy(player));
        return sequence<list.size() && list.get(sequence).present()?list.get(sequence).uid():-1;
    }
    /** Display identity of a packed position word, or -1 when it does not address a monster zone. */
    public int uidAtPacked(DuelBridge.Game g,int packed) {return uidAt(g,packed);}
    /** Printed name of a display identity, or null when nothing was rendered for it yet. */
    public String nameOf(int uid) {Integer code=uidCodes.get(uid);return code==null?null:DuelCatalog.name(code);}
    public static byte[] encodeCards(List<CardView> cards) {
        var buf=new FriendlyByteBuf(Unpooled.buffer());
        try {buf.writeVarInt(cards.size());for(var c:cards)c.write(buf);byte[] data=new byte[buf.readableBytes()];buf.readBytes(data);return data;}
        finally {buf.release();}
    }
}
