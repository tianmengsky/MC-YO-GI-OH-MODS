package dev.codex.yugiohmdpro.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Hover details for the original card catalog: first release date and containing products. */
public final class CatalogDetails {
    /** Product names printed before the list is collapsed. */
    public static final int COLLAPSED = 8;
    private static Map<String,List<JsonObject>> cache;

    private CatalogDetails() {}

    private static String text(JsonObject row,String key) {
        JsonElement value=row.get(key);
        return value==null||value.isJsonNull()?"":value.getAsString();
    }

    /** Every reviewed source, including compatibility-only rows, decides the first print date. */
    private static Map<String,List<JsonObject>> everySource() {
        Map<String,List<JsonObject>> known=cache;
        if(known!=null)return known;
        Map<String,List<JsonObject>> built=new HashMap<>();
        for(String section:List.of("products","retired_products")) {
            JsonArray products=HistoricalContent.DATA.getAsJsonArray(section);
            if(products==null)continue;
            for(JsonElement element:products) {
                JsonObject product=element.getAsJsonObject();
                JsonArray members=product.getAsJsonArray("cards");
                if(members==null)continue;
                HashSet<String> seen=new HashSet<>();
                for(JsonElement member:members) {
                    String id=text(member.getAsJsonObject(),"id");
                    if(id.isEmpty()||!seen.add(id))continue;
                    built.computeIfAbsent(id,key->new ArrayList<>()).add(product);
                }
            }
        }
        cache=built;
        return built;
    }

    /** Earliest reviewed release date; empty when no source row carries one. */
    public static String releaseDate(String cardId) {
        String best="";
        for(JsonObject product:everySource().getOrDefault(cardId,List.of())) {
            String date=text(product,"release_date");
            if(date.isEmpty()) {
                String year=text(product,"index_year");
                if(!year.isEmpty()&&!year.equals("0"))date=year+" 年";
            }
            if(date.isEmpty())continue;
            if(best.isEmpty()||date.compareTo(best)<0)best=date;
        }
        return best;
    }

    /** Products this mod actually sells, earliest first and without duplicates. */
    public static List<String> obtainablePackNames(String cardId) {
        List<JsonObject> sources=new ArrayList<>(HistoricalContent.SOURCES.getOrDefault(cardId,List.of()));
        sources.sort(Comparator.comparing((JsonObject product)->text(product,"release_date")));
        LinkedHashSet<String> names=new LinkedHashSet<>();
        for(JsonObject product:sources) {
            String name=text(product,"name");
            if(!name.isEmpty())names.add(name);
        }
        return List.copyOf(names);
    }

    /** Compatibility-only products that hold the card but cannot be obtained any more. */
    private static List<String> historicalPackNames(String cardId,List<String> obtainable) {
        HashSet<String> known=new HashSet<>(obtainable);
        LinkedHashSet<String> names=new LinkedHashSet<>();
        for(JsonObject product:everySource().getOrDefault(cardId,List.of())) {
            String name=text(product,"name");
            if(!name.isEmpty()&&!known.contains(name))names.add(name);
        }
        return List.copyOf(names);
    }

    /** Lines appended to the catalog hover tooltip. Never throws: a bad row must not break rendering. */
    public static List<Component> lines(String cardId,boolean expanded) {
        List<Component> out=new ArrayList<>();
        try {
            JsonObject row=HistoricalContent.CARDS.get(cardId);
            if(row!=null&&row.has("code"))out.add(Component.literal(String.format("密码 %08d",row.get("code").getAsInt())).withStyle(ChatFormatting.DARK_GRAY));
            String date=releaseDate(cardId);
            out.add(Component.literal("发行日期："+(date.isEmpty()?"无核验收录":date)).withStyle(ChatFormatting.GRAY));
            List<String> obtainable=obtainablePackNames(cardId);
            if(!obtainable.isEmpty()) {
                if(obtainable.size()<=COLLAPSED||expanded) {
                    out.add(Component.literal("收录卡包："+String.join(" / ",obtainable)).withStyle(ChatFormatting.AQUA));
                }else {
                    out.add(Component.literal("收录卡包："+String.join(" / ",obtainable.subList(0,COLLAPSED))).withStyle(ChatFormatting.AQUA));
                    out.add(Component.literal("…另 "+(obtainable.size()-COLLAPSED)+" 个（共 "+obtainable.size()+" 个，按住 Shift 展开全部）").withStyle(ChatFormatting.DARK_GRAY));
                }
                return out;
            }
            List<String> historical=historicalPackNames(cardId,obtainable);
            out.add(Component.literal(historical.isEmpty()?"收录卡包：无收录记录":"收录卡包：当前无在售产品（历史收录 "+historical.size()+" 个）").withStyle(ChatFormatting.DARK_GRAY));
        }catch(Throwable error) {
            // Defensive: the hover must degrade to the original lines, never crash the screen.
            out.add(Component.literal("图鉴资料读取失败").withStyle(ChatFormatting.DARK_RED));
        }
        return out;
    }
}
