// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.kiki.yugioh.network.CardView
 */
package dev.codex.yugiohmdpro.visual.board;

import com.kiki.yugioh.network.CardView;
import dev.codex.yugiohmdpro.visual.board.HologramMotion;

public final class ScreenPanel {
    public static final double WIDTH_FACTOR = 1.3;
    public static final float ALPHA = 0.55f;
    public static final double BOB_FRACTION = 0.08;
    public static final double MAX_HEIGHT_FACTOR = 1.25;
    public static final int[] STAR_ROWS = new int[]{4, 14, 31, 21, 17};
    public static final int STAR_SIZE = 5;
    public static final int BADGE_HEIGHT = 9;

    private ScreenPanel() {
    }

    public static boolean standsUp(CardView view) {
        if (view == null || !view.present() || view.definition() == null) {
            return false;
        }
        return view.definition().isMonster() && !view.position().isFaceDown();
    }

    public static int[] standingAt(int zoneX, int zoneY, int zoneW, int zoneH, double aspect, double widthFactor) {
        int ceiling;
        int width = Math.max(1, (int)Math.round((double)zoneW * widthFactor));
        int height = Math.max(1, (int)Math.round((double)width / aspect));
        if (height > (ceiling = Math.max(1, (int)Math.round((double)zoneH * 1.25)))) {
            height = ceiling;
            width = Math.max(1, (int)Math.round((double)height * aspect));
        }
        return new int[]{zoneX + (zoneW - width) / 2, zoneY + zoneH - height, width, height};
    }

    public static int bobPixels(double seconds, int uid, int panelHeight) {
        return (int)Math.round(Math.sin(HologramMotion.hoverPhase(seconds, uid)) * (double)panelHeight * 0.08);
    }

    public static int[] counterBadgeAt(int zoneX, int zoneY, int numberWidth) {
        int width = 9 + Math.max(0, numberWidth);
        return new int[]{zoneX + 1, zoneY + 1, width, 9};
    }

    public static int[] counterStarAt(int badgeX, int badgeY) {
        return new int[]{badgeX + 1, badgeY + 2};
    }
}

