package dev.codex.yugiohmdpro.content;

import com.google.gson.*;
import com.kiki.yugioh.card.*;
import dev.codex.yugiohfix.mixin.CardsAccess;
import dev.codex.yugiohfix.mixin.ExpansionsAccess;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reviewed metadata is the only source of new card identities and product membership. */
public final class HistoricalContent {
    public static final JsonObject DATA;
    public static final Map<String,JsonObject> CARDS=new LinkedHashMap<>();
    public static final Map<Integer,JsonObject> BY_CODE=new LinkedHashMap<>();
    public static final Map<String,JsonObject> PRODUCTS=new LinkedHashMap<>();
    public static final Map<String,JsonObject> RETIRED_PRODUCTS=new LinkedHashMap<>();
    public static final Map<String,JsonObject> LEGACY_PRODUCTS=new LinkedHashMap<>();
    public static final Map<String,List<JsonObject>> SOURCES=new HashMap<>();
    private static boolean registered;
    static {
        try(var in=HistoricalContent.class.getResourceAsStream("/data/yugioh_mdpro_addon/historical-content.json")) {
            if(in==null)throw new IOException("Missing historical inventory");
            DATA=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException e){throw new ExceptionInInitializerError(e);}
        for(var e:DATA.getAsJsonArray("cards")) {
            var c=e.getAsJsonObject(); CARDS.put(str(c,"id"),c);BY_CODE.put(num(c,"code"),c);
        }
        for(var e:DATA.getAsJsonArray("products")) {
            var p=e.getAsJsonObject(); PRODUCTS.put(str(p,"id"),p);
            Set<String> seen=new HashSet<>();
            for(var r:p.getAsJsonArray("cards")) {
                String id=str(r.getAsJsonObject(),"id");
                if(seen.add(id))SOURCES.computeIfAbsent(id,k->new ArrayList<>()).add(p);
            }
        }
    }
    static {
        var retired=DATA.getAsJsonArray("retired_products");
        if(retired!=null)for(var e:retired) {
            var p=e.getAsJsonObject();RETIRED_PRODUCTS.put(str(p,"id"),p);
            if(p.has("legacy_item")) {
                LEGACY_PRODUCTS.put(str(p,"legacy_item"),p);
                Set<String> seen=new HashSet<>();
                for(var r:playableRows(p))if(seen.add(str(r,"id")))SOURCES.computeIfAbsent(str(r,"id"),k->new ArrayList<>()).add(p);
            }
        }
    }
    public static String str(JsonObject o,String key){return o.get(key).getAsString();}
    public static int num(JsonObject o,String key){return o.get(key).getAsInt();}
    /** Database aliases share the deck copy limit, including permanent rule names. */
    public static String copyFamily(CardDefinition def) {
        var row=CARDS.get(def.getId());
        if(row==null)return def.getId();
        int code=num(row,"code"),alias=num(row,"alias");
        return "code:"+(alias==0?code:alias);
    }
    public static boolean booster(JsonObject p) {
        if(p.has("opening_kind"))return str(p,"opening_kind").equals("booster");
        return Set.of("booster_pack","core_booster_pack","special_booster_pack","duelist_pack","duel_terminal").contains(str(p,"product_kind"));
    }
    public static String kindLabel(JsonObject p) {
        if(p.has("opening_kind")&&booster(p))return "预组强化卡包";
        return switch(str(p,"product_kind")) {
            case "starter_deck"->"入门预组";case "structure_deck"->"构筑预组";case "preconstructed_deck"->"预组";
            case "duelist_pack"->"决斗者卡包";case "duel_terminal"->"决斗终端卡包";
            case "core_booster_pack"->"基础补充包";case "special_booster_pack"->"特别补充包";default->"补充包";
        };
    }
    public static String rarityLabel(String ids) {
        return Arrays.stream(ids.split(",")).filter(s->!s.isBlank()).map(s->switch(s) {
            case "1"->"平卡";case "2"->"银字";case "3"->"面闪";case "4"->"金字";case "5"->"银碎";default->"特殊稀有度";
        }).distinct().collect(java.util.stream.Collectors.joining(" / "));
    }
    public static List<JsonObject> playableRows(JsonObject p) {
        return p.getAsJsonArray("cards").asList().stream().map(JsonElement::getAsJsonObject).filter(r->num(r,"code")!=0).toList();
    }
    public static void bootstrap() {
        if(registered)return; registered=true;
        for(var c:CARDS.values()) {
            String id=str(c,"id"),name=str(c,"name_en");int t=num(c,"type");
            CardDefinition.Builder b;
            if((t&1)!=0) {
                b=CardDefinition.monster(id,name).stats(num(c,"level_packed")&255,attribute(num(c,"attribute")),race(num(c,"race")),num(c,"atk"),num(c,"def"))
                  .category((t&0x800000)!=0?MonsterCategory.XYZ:(t&0x2000)!=0?MonsterCategory.SYNCHRO:(t&0x40)!=0?MonsterCategory.FUSION:
                      (t&0x80)!=0?MonsterCategory.RITUAL:(t&0x20)!=0?MonsterCategory.EFFECT:MonsterCategory.NORMAL);
            }else if((t&2)!=0) {
                b=CardDefinition.spell(id,name).spellKind((t&0x80000)!=0?SpellKind.FIELD:(t&0x10000)!=0?SpellKind.QUICK_PLAY:
                   (t&0x20000)!=0?SpellKind.CONTINUOUS:(t&0x40000)!=0?SpellKind.EQUIP:(t&0x80)!=0?SpellKind.RITUAL:SpellKind.NORMAL);
            }else b=CardDefinition.trap(id,name).trapKind((t&0x100000)!=0?TrapKind.COUNTER:(t&0x20000)!=0?TrapKind.CONTINUOUS:TrapKind.NORMAL);
            b.desc(str(c,"text"));
            // Native Lua remains the sole effect implementation.
            // Existing IDs are immutable compatibility identities. Their original
            // CardDefinition remains authoritative; imported metadata is used for
            // native code, source lists and the new IDs only.
            if(Cards.byId(id)==null)CardsAccess.fix$register(b);
        }
        verifyLegacyPacks();
    }
    private static void verifyLegacyPacks() {
        // Exact JP equivalents reuse original objects and their unmodified contents.
        for(var entry:LEGACY_PRODUCTS.entrySet())if(booster(entry.getValue())) {
            var old=Expansions.byId(entry.getKey().substring("yugioh:booster_".length()));
            var ids=new HashSet<>(playableRows(entry.getValue()).stream().map(r->str(r,"id")).toList());
            if(old==null||!new HashSet<>(old.cardIds()).equals(ids))throw new IllegalStateException("Japanese legacy pack mismatch: "+entry.getKey());
            ExpansionsAccess.fix$register(new Expansions.Expansion(old.id(),old.code(),str(entry.getValue(),"name"),old.cardIds()));
        }
    }
    public static List<JsonObject> visibleProducts() {
        var result=new ArrayList<>(PRODUCTS.values());result.addAll(LEGACY_PRODUCTS.values());return List.copyOf(result);
    }
    public static JsonObject anyProduct(String id) {
        var p=PRODUCTS.get(id);return p!=null?p:RETIRED_PRODUCTS.get(id);
    }
    public static boolean legacyVisible(net.minecraft.world.item.Item item) {
        var key=net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        return key!=null&&LEGACY_PRODUCTS.containsKey(key.toString());
    }
    private static Attribute attribute(int a){return switch(a){case 1->Attribute.EARTH;case 2->Attribute.WATER;case 4->Attribute.FIRE;case 8->Attribute.WIND;case 16->Attribute.LIGHT;case 32->Attribute.DARK;case 64->Attribute.DIVINE;default->Attribute.NONE;};}
    private static MonsterType race(int r) {
        String name=switch(r){case 1->"WARRIOR";case 2->"SPELLCASTER";case 4->"FAIRY";case 8->"FIEND";case 16->"ZOMBIE";case 32->"MACHINE";
            case 64->"AQUA";case 128->"PYRO";case 256->"ROCK";case 512->"WINGED_BEAST";case 1024->"PLANT";case 2048->"INSECT";
            case 4096->"THUNDER";case 8192->"DRAGON";case 16384->"BEAST";case 32768->"BEAST_WARRIOR";case 65536->"DINOSAUR";
            case 131072->"FISH";case 262144->"SEA_SERPENT";case 524288->"REPTILE";case 1048576->"PSYCHIC";case 2097152->"DIVINE_BEAST";
            case 4194304->"CREATOR_GOD";case 8388608->"WYRM";default->"NONE";};
        return MonsterType.valueOf(name);
    }
}
