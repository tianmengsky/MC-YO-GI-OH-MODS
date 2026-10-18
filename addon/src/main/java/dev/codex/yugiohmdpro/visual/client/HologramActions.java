// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.kiki.yugioh.network.CardView
 */
package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.network.CardView;
import dev.codex.yugiohmdpro.visual.board.HologramMotion;
import java.util.HashMap;
import java.util.Map;

public final class HologramActions {
    public static final double SETTLE_SECONDS = 0.2;
    public static final class Context {
        private final Map<Integer, Playing> playing = new HashMap<>();
        private final Map<Integer, Boolean> attacked = new HashMap<>();
        private final Map<Integer, HologramMotion.Action> showingAction = new HashMap<>();
        private final Map<Integer, Long> showingSince = new HashMap<>();
    }
    private static Context context=new Context();
    /** UIDs are local to each duel; isolate animation state for simultaneous world boards. */
    public static void inContext(Context next,Runnable render) {
        Context previous=context;context=next;
        try{render.run();}finally{context=previous;}
    }

    private HologramActions() {
    }

    public static HologramMotion.Action action(CardView view, long nowMillis) {
        Playing playing;
        HologramMotion.Action decided;
        if (view == null) {
            return HologramMotion.Action.IDLE;
        }
        boolean attacked = view.hasAttacked();
        boolean before = Boolean.TRUE.equals(context.attacked.put(view.uid(), attacked));
        if (attacked && !before) {
            context.playing.put(view.uid(), new Playing(HologramMotion.Action.ATTACK, nowMillis));
        }
        HologramMotion.Action action = decided = (playing = context.playing.get(view.uid())) == null ? HologramMotion.Action.IDLE : playing.action();
        if (context.showingAction.put(view.uid(), decided) != decided) {
            context.showingSince.put(view.uid(), nowMillis);
        }
        return decided;
    }

    public static double secondsInto(int uid, long nowMillis) {
        Playing playing = context.playing.get(uid);
        return playing == null ? 0.0 : Math.max(0.0, (double)(nowMillis - playing.startedAt()) / 1000.0);
    }

    public static double secondsShowing(int uid, long nowMillis) {
        Long since = context.showingSince.get(uid);
        return since == null ? 0.0 : Math.max(0.0, (double)(nowMillis - since) / 1000.0);
    }

    public static void attackDeclared(int targetUid, long nowMillis) {
        if (targetUid < 0) {
            return;
        }
        context.playing.put(targetUid, new Playing(HologramMotion.Action.HIT, nowMillis));
    }
    public static void attack(int uid,long nowMillis) {
        if(uid>=0)context.playing.put(uid,new Playing(HologramMotion.Action.ATTACK,nowMillis));
    }

    public static void finished(int uid) {
        context.playing.remove(uid);
    }

    public static void clear() {
        context.playing.clear();
        context.attacked.clear();
        context.showingAction.clear();
        context.showingSince.clear();
    }

    private record Playing(HologramMotion.Action action, long startedAt) {
    }

}
