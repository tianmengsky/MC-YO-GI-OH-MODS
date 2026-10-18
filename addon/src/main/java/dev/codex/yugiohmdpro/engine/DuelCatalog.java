package dev.codex.yugiohmdpro.engine;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class DuelCatalog {
    private static final JsonObject DATA;
    static {
        try (var stream = DuelCatalog.class.getResourceAsStream("/data/yugioh_mdpro_addon/duel-catalog.json")) {
            if (stream == null) throw new IOException("Missing duel catalog");
            DATA = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    public static int code(String id) {
        var value = DATA.getAsJsonObject("mapping").get(id.contains(":") ? id : "yugioh:" + id);
        if (value == null) throw new IllegalArgumentException("卡片尚无已核验的内核映射：" + id);
        int code = value.getAsInt();
        if (!allowed(code)) throw new IllegalArgumentException("卡片不在已验证的 2017-03-24 / MR3 卡池：" + name(code));
        return code;
    }
    public static boolean allowed(int code) {
        var card = card(code); return card != null && card.get("allowed").getAsBoolean();
    }
    public static String legacyId(int code) {
        return LegacyIds.VALUES.getOrDefault(code&0x7fffffff,"");
    }
    private static final class LegacyIds {
        static final Map<Integer,String> VALUES=new HashMap<>();
        static {for(var e:DATA.getAsJsonObject("mapping").entrySet())VALUES.putIfAbsent(e.getValue().getAsInt(),e.getKey().replace("yugioh:",""));}
    }
    private static JsonObject card(int code) { return DATA.getAsJsonObject("cards").getAsJsonObject(Integer.toString(code & 0x7fffffff)); }
    public static String name(int code) {
        if (code == 0) return "里侧卡片";
        var card = card(code); return card == null ? "卡号 " + code : card.get("name").getAsString();
    }
    public static String text(int code) {
        var card = card(code); return card == null ? "" : card.get("text").getAsString();
    }
    public static String description(int value) {
        if (value >= 10000) {
            var card = card(value >>> 4); int index = value & 15;
            if (card != null) {
                var s = card.getAsJsonArray("strings").get(index);
                if (!s.isJsonNull() && !s.getAsString().isBlank()) return s.getAsString();
                return name(value >>> 4) + " 的效果 " + (index + 1);
            }
        }
        return switch(value) {
            case 200 -> "选择要破坏的卡"; case 201 -> "选择要除外的卡";
            case 500 -> "选择素材"; case 501 -> "选择要召唤的卡";
            case 502 -> "选择特殊召唤的卡"; case 503 -> "选择要加入手牌的卡";
            case 504 -> "选择要送去墓地的卡"; case 505 -> "选择要解放的卡";
            case 506 -> "选择要弃置的卡"; default -> "选择效果 / 选项 " + Integer.toUnsignedString(value);
        };
    }
    public static String location(int player, int location, int sequence) {
        String zone = switch(location & 0x7f) {
            case 1 -> "卡组"; case 2 -> "手牌"; case 4 -> "怪兽区";
            case 8 -> sequence == 5 ? "场地" : sequence >= 6 ? "灵摆区" : "魔陷区";
            case 16 -> "墓地"; case 32 -> "除外"; case 64 -> "额外卡组"; default -> "素材";
        };
        return "P" + (player + 1) + " " + zone + " " + (sequence + 1);
    }
    public static List<Integer> search(String input) { return search(input,Set.of()); }
    /** Same search, restricted to {@code only} when it is non-empty (MSG_ANNOUNCE_CARD's allowed codes). */
    public static List<Integer> search(String input,Set<Integer> only) {
        String q = input.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (var e : DATA.getAsJsonObject("cards").entrySet()) {
            int code = Integer.parseInt(e.getKey());
            if(!e.getValue().getAsJsonObject().get("allowed").getAsBoolean())continue;
            if (!only.isEmpty() && !only.contains(code)) continue;
            if (e.getKey().equals(q) || e.getValue().getAsJsonObject().get("name").getAsString().toLowerCase(Locale.ROOT).contains(q))
                result.add(code);
            if (result.size() == 200) break;
        }
        return result;
    }
}
