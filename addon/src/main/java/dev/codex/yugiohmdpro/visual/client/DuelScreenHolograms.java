// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.kiki.yugioh.client.texture.CardArtTextures
 *  com.kiki.yugioh.network.CardView
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.resources.ResourceLocation
 */
package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.client.texture.CardArtTextures;
import com.kiki.yugioh.network.CardView;
import dev.codex.yugiohmdpro.visual.board.ScreenPanel;
import dev.codex.yugiohmdpro.visual.client.HologramArt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class DuelScreenHolograms {
    private static int panelsThisFrame;
    private static int panelsLastFrame;

    private DuelScreenHolograms() {
    }

    public static void beginFrame() {
        panelsThisFrame = 0;
    }

    public static void endFrame() {
        panelsLastFrame = panelsThisFrame;
    }

    public static int panelsLastFrame() {
        return panelsLastFrame;
    }

    public static void draw(GuiGraphics graphics, int zoneX, int zoneY, int zoneW, int zoneH, CardView view) {
        if (!ScreenPanel.standsUp(view)) {
            return;
        }
        ResourceLocation cutout = HologramArt.of(view.cardId());
        if (cutout == null) {
            return;
        }
        int[] size = CardArtTextures.dimensionsOf((ResourceLocation)cutout);
        if (size[0] <= 0 || size[1] <= 0) {
            return;
        }
        int[] rect = ScreenPanel.standingAt(zoneX, zoneY, zoneW, zoneH, (double)size[0] / (double)size[1], 1.3);
        int lift = ScreenPanel.bobPixels((double)System.currentTimeMillis() / 1000.0, view.uid(), rect[3]);
        graphics.setColor(1.0f, 1.0f, 1.0f, 0.55f);
        graphics.blit(cutout, rect[0], rect[1] - lift, rect[2], rect[3], 0.0f, 0.0f, size[0], size[1], size[0], size[1]);
        graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        ++panelsThisFrame;
    }
}

