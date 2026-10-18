// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.kiki.yugioh.network.CardView
 */
package dev.codex.yugiohmdpro.visual.board;

import com.kiki.yugioh.network.CardView;
import dev.codex.yugiohmdpro.visual.board.BoardLayout;
import java.util.ArrayList;
import java.util.List;

public final class BoardPlan {
    public static final float SPELL_ROW = 1.0f;
    public static final float ROW_PITCH = 2.4f;
    public static final float MONSTER_ROW = 3.4f;
    public static final float DUELIST_DISTANCE = 11.8f;
    public static final float FOE_MONSTER_ROW = 8.4f;
    public static final float FOE_SPELL_ROW = 10.8f;
    public static final float GROUND_GAP = 0.02f;

    private BoardPlan() {
    }

    public static double[] opponentStand(double originX, double originZ, double forwardX, double forwardZ) {
        return new double[]{originX + forwardX * (double)11.8f, originZ + forwardZ * (double)11.8f};
    }

    public static List<Slot> plan(double originX, double originY, double originZ, double forwardX, double forwardZ, List<CardView> selfMonsters, List<CardView> selfSpells, List<CardView> foeMonsters, List<CardView> foeSpells, boolean selfIsInitiator) {
        double[] right = BoardLayout.right(forwardX, forwardZ);
        List<CardView> nearSpells = selfIsInitiator ? selfSpells : foeSpells;
        List<CardView> nearMonsters = selfIsInitiator ? selfMonsters : foeMonsters;
        List<CardView> farMonsters = selfIsInitiator ? foeMonsters : selfMonsters;
        List<CardView> farSpells = selfIsInitiator ? foeSpells : selfSpells;
        ArrayList<Slot> slots = new ArrayList<Slot>();
        BoardPlan.addSlots(slots, originX, originY, originZ, forwardX, forwardZ, right, 1.0f, nearSpells, false, true);
        BoardPlan.addSlots(slots, originX, originY, originZ, forwardX, forwardZ, right, 3.4f, nearMonsters, true, true);
        BoardPlan.addSlots(slots, originX, originY, originZ, forwardX, forwardZ, right, 8.4f, farMonsters, true, false);
        BoardPlan.addSlots(slots, originX, originY, originZ, forwardX, forwardZ, right, 10.8f, farSpells, false, false);
        return List.copyOf(slots);
    }

    private static void addSlots(List<Slot> slots, double originX, double originY, double originZ, double forwardX, double forwardZ, double[] right, float row, List<CardView> zones, boolean monsterRow, boolean anchorRow) {
        double ownerZ;
        double ownerX = anchorRow ? -forwardX : forwardX;
        double ownerRightX = ownerZ = anchorRow ? -forwardZ : forwardZ;
        double ownerRightZ = -ownerX;
        for (int zone = 0; zone < zones.size() && zone < 5; ++zone) {
            CardView card = zones.get(zone);
            if (card == null || !card.present()) continue;
            float lateral = BoardLayout.lateral(zone);
            double centerX = originX + right[0] * (double)lateral + forwardX * (double)row;
            double centerY = originY + (double)0.02f;
            double centerZ = originZ + right[1] * (double)lateral + forwardZ * (double)row;
            boolean defensive = card.position().usesDefense();
            float[][] box = BoardLayout.cardBox(centerX, centerY, centerZ, ownerX, ownerZ, ownerRightX, ownerRightZ, defensive);
            float[] projection = null;
            if (monsterRow && card.isKnown() && card.definition() != null && card.definition().isMonster() && !card.position().isFaceDown()) {
                projection = new float[]{(float)centerX, (float)centerY, (float)centerZ};
            }
            slots.add(new Slot(card, box, !card.isKnown() || card.position().isFaceDown(), defensive, projection, anchorRow));
        }
    }

    public record Slot(CardView card, float[][] box, boolean sleeve, boolean defensive, float[] projection, boolean facesForward) {
    }
}

