package dev.codex.yugiohmdpro.rules;

import com.kiki.yugioh.card.*;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import dev.codex.yugiohmdpro.duel.DuelNetwork;
import dev.codex.yugiohfix.engine.BanList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Rules only: never edits card metadata, inventory, product recipes or deck-box NBT. */
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon")
public final class ServerBanlist extends SavedData {
    private static final String STORAGE="yugioh_mdpro_banlist";
    private static volatile MinecraftServer owner;
    private static volatile Map<String,Integer> serverRules=Map.of(),clientRules=Map.of();
    private static final Map<String,String> REPRESENTATIVES=representatives();
    private final Map<String,Integer> rules=new TreeMap<>();
    public static ServerBanlist get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ServerBanlist::load,ServerBanlist::new,STORAGE);
    }
    public static String key(String id) {
        var c=HistoricalContent.CARDS.get(id);
        if(c==null)return "id:"+id;
        int alias=HistoricalContent.num(c,"alias");
        return "code:"+(alias==0?HistoricalContent.num(c,"code"):alias);
    }
    public static Map<String,Integer> snapshot(){return owner==null?clientRules:serverRules;}
    public static int limit(String id){return id==null?3:snapshot().getOrDefault(key(id),3);}
    public static ServerBanlist load(CompoundTag tag) {
        var data=new ServerBanlist();var values=tag.getCompound("Limits");
        for(String k:values.getAllKeys()) {
            int limit=values.getInt(k);
            if((k.startsWith("code:")||k.startsWith("id:"))&&limit>=0&&limit<3)data.rules.put(k,limit);
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        var values=new CompoundTag();rules.forEach(values::putInt);tag.put("Limits",values);tag.putInt("Version",1);return tag;
    }
    public Map<String,Integer> limits(){return Map.copyOf(rules);}
    public void set(MinecraftServer server,String id,int limit) {
        if(limit<0||limit>3)throw new IllegalArgumentException("Limit must be 0..3");
        if(limit==3)rules.remove(key(id));else rules.put(key(id),limit);
        setDirty();publish(server);
    }
    public void clear(MinecraftServer server){rules.clear();setDirty();publish(server);}
    private void publish(MinecraftServer server) {
        owner=server;serverRules=Map.copyOf(rules);
        var tag=save(new CompoundTag());
        for(var player:server.getPlayerList().getPlayers())DuelNetwork.sendRestrictions(player,tag);
    }
    public static void receive(CompoundTag tag){clientRules=load(tag).limits();}
    public static void resetClient(){clientRules=Map.of();}
    @SubscribeEvent public static void started(ServerStartedEvent event){get(event.getServer()).publish(event.getServer());}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){if(owner==event.getServer()){owner=null;serverRules=Map.of();clientRules=Map.of();}}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if(event.getEntity() instanceof ServerPlayer player)DuelNetwork.sendRestrictions(player,get(player.getServer()).save(new CompoundTag()));
    }
    public static CardDefinition representative(String key) {
        if(key.startsWith("id:"))return Cards.byId(key.substring(3));
        var id=REPRESENTATIVES.get(key);return id==null?null:Cards.byId(id);
    }
    private static Map<String,String> representatives() {
        var result=new HashMap<String,String>();
        for(var c:HistoricalContent.CARDS.values()) {
            var id=HistoricalContent.str(c,"id");result.putIfAbsent(key(id),id);
            if(HistoricalContent.num(c,"alias")==0)result.put(key(id),id);
        }
        return Map.copyOf(result);
    }
    public static List<BanList.Entry> entries() {
        var entries=new ArrayList<BanList.Entry>();
        new TreeMap<>(snapshot()).forEach((key,limit)->{
            var c=representative(key);if(c!=null)entries.add(new BanList.Entry(c.getId(),c.getEnglishName(),limit,limit==0?"forbidden":limit==1?"limited":"semi_limited"));
        });return List.copyOf(entries);
    }
    /** Whole main/extra/side family; aliases cannot evade a server restriction. */
    public static Component validate(Collection<CardDefinition> cards) {
        var counts=new HashMap<String,Integer>();
        for(var card:cards) {
            int limit=limit(card.getId());
            if(counts.merge(key(card.getId()),1,Integer::sum)>limit&&limit<3)
                return Component.literal("服务器禁限：").append(Component.translatable("item.yugioh."+card.getId())).append(Component.literal(" 最多投入 "+limit+" 张。"));
        }
        return null;
    }
}
