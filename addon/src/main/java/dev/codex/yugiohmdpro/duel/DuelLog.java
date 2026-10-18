package dev.codex.yugiohmdpro.duel;

import dev.codex.yugiohmdpro.engine.DuelCatalog;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns core messages into the duel-log lines the right-hand panel shows.
 *
 * <p>Without this the log carried only four message kinds (turn, activation, chain end, automatic
 * answer), so a player could not reconstruct what happened even by reading it. Every parser here is
 * length-checked and returns nothing instead of throwing: a wrong log line is cosmetic, an
 * exception would cancel the duel.
 *
 * <p><b>Public information only.</b> One history string is built per duel and sent to both players
 * ({@code DuelBridge.Game.snapshot}), so a line may never name a card the opponent is not allowed
 * to see: cards drawn into a hand, cards added from the deck, cards set face-down, or a face-down
 * card whose position changed while still face-down. Those moves are logged with the zones only.
 *
 * <p>Layouts follow this frozen ABI's framing table ({@code CoreMessages.split}), the host's own
 * message names ({@code mdpromod-host-source/DuelHostProgram.decompiled.cs:2647-2748}) and the
 * field layout already used by {@code DuelBridge} and {@code NativeBoard}.
 */
public final class DuelLog {
    private DuelLog() {}

    public static List<String> describe(DuelBridge.Game game,int kind,byte[] data) {
        if(kind==110) {
            List<String> lines=new ArrayList<>(1);
            try { attack(game,data,lines); } catch(RuntimeException malformed) { lines.clear(); }
            return lines;
        }
        return describe(kind,data);
    }

    /**
     * Message-only entry point: everything except the attack line, which needs the rendered board to
     * turn a position word into a card name. Kept free of Minecraft so the framing offsets and the
     * public-information rule can be asserted without a running duel.
     */
    public static List<String> describe(int kind,byte[] data) {
        List<String> lines=new ArrayList<>(2);
        try {
            switch(kind) {
                case 50 -> move(data,lines);
                case 53 -> position(data,lines);
                case 54 -> lines.add("盖放了一张卡");
                case 55 -> swap(data,lines);
                case 60 -> summon(data,"召唤",lines);
                case 62 -> summon(data,"特殊召唤",lines);
                case 64 -> summon(data,"翻转召唤",lines);
                case 90 -> draw(data,lines);
                case 91 -> amount(data,"受到伤害",lines);
                case 92 -> amount(data,"回复生命值",lines);
                case 94 -> amount(data,"生命值变为",lines);
                case 100 -> amount(data,"支付生命值",lines);
                default -> { }
            }
        } catch(RuntimeException malformed) {
            // A display-only parse must never be the reason a duel stops.
            lines.clear();
        }
        return lines;
    }

    private static void move(byte[] data,List<String> lines) {
        if(data.length<17) return;
        int code=int32(data,1)&0x7fffffff;
        int fromPlayer=data[5]&255, fromLocation=data[6]&255, fromSequence=data[7]&255, fromPosition=data[8]&255;
        int toPlayer=data[9]&255, toLocation=data[10]&255, toSequence=data[11]&255, toPosition=data[12]&255;
        int reason=int32(data,13);
        String who=publicSource(fromLocation,fromPosition) && publicTarget(toLocation,toPosition)
                ? DuelCatalog.name(code) : "里侧卡片";
        String from=zone(fromPlayer,fromLocation,fromSequence);
        String to=zone(toPlayer,toLocation,toSequence);
        lines.add(who+"："+from+" → "+to+((reason&1)!=0?"（破坏）":""));
    }

    private static void position(byte[] data,List<String> lines) {
        if(data.length<10) return;
        int code=int32(data,1)&0x7fffffff;
        int position=data[9]&255;
        String who=faceDown(position) ? "里侧卡片" : DuelCatalog.name(code);
        lines.add("表示变更："+who+" → "+positionName(position));
    }

    private static void swap(byte[] data,List<String> lines) {
        if(data.length<17) return;
        lines.add("控制权转移："+DuelCatalog.name(int32(data,1)&0x7fffffff));
    }

    private static void summon(byte[] data,String verb,List<String> lines) {
        if(data.length<9) return;
        lines.add(verb+"："+DuelCatalog.name(int32(data,1)&0x7fffffff));
    }

    /** Draws are private: the count is public, the drawn cards are not. */
    private static void draw(byte[] data,List<String> lines) {
        if(data.length<3) return;
        lines.add("抽卡：P"+((data[1]&255)+1)+" 抽 "+(data[2]&255)+" 张");
    }

    private static void amount(byte[] data,String verb,List<String> lines) {
        if(data.length<6) return;
        lines.add("P"+((data[1]&255)+1)+" "+verb+" "+int32(data,2));
    }

    private static void attack(DuelBridge.Game game,byte[] data,List<String> lines) {
        if(data.length<9) return;
        int attackerUid=game.board.uidAtPacked(game,int32(data,1));
        int targetUid=game.board.uidAtPacked(game,int32(data,5));
        String attacker=name(game,attackerUid,"一只怪兽");
        lines.add("攻击宣言："+attacker+" → "+(targetUid<0?"直接攻击":name(game,targetUid,"一只怪兽")));
    }

    private static String name(DuelBridge.Game game,int uid,String fallback) {
        if(uid<0) return fallback;
        String printed=game.board.nameOf(uid);
        return printed==null?fallback:printed;
    }

    /** A card the opponent could already see: on the field face-up, in a graveyard, or banished. */
    private static boolean publicSource(int location,int position) {
        return switch(location&0x7f) {
            case 16, 32 -> true;
            case 4, 8 -> !faceDown(position);
            default -> false;
        };
    }

    /** A destination the opponent may see: not a hand, not a deck, not a face-down field slot. */
    private static boolean publicTarget(int location,int position) {
        return switch(location&0x7f) {
            case 1, 2, 64 -> false;
            case 4, 8 -> !faceDown(position);
            default -> true;
        };
    }

    static boolean faceDown(int position) {return (position&0x0A)!=0;}

    private static String zone(int enginePlayer,int location,int sequence) {
        return "P"+(enginePlayer+1)+" "+zoneName(location,sequence);
    }

    private static String zoneName(int location,int sequence) {
        return switch(location&0x7f) {
            case 1 -> "卡组"; case 2 -> "手牌"; case 4 -> "怪兽区 "+(sequence+1);
            case 8 -> sequence==5 ? "场地" : sequence>=6 ? "灵摆区 "+(sequence-5) : "魔陷区 "+(sequence+1);
            case 16 -> "墓地"; case 32 -> "除外区"; case 64 -> "额外卡组"; default -> "区域 "+location;
        };
    }

    private static String positionName(int position) {
        if((position&0x8)!=0) return "里侧守备表示";
        if((position&0x2)!=0) return "里侧表示";
        if((position&0x4)!=0) return "守备表示";
        return "攻击表示";
    }

    private static int int32(byte[] data,int at) {
        return ByteBuffer.wrap(data,at,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
