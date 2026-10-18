package dev.codex.yugiohmdpro.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The scissor band that turns the duel portraits into upper-body busts.
 *
 * <p>The band is opened around the portrait's mesh and closed again as soon as the model has been
 * emitted, because the duel disk and glove are drawn after the body: clipping them away removed the
 * disk the original mod shows. The flag lives here, in an ordinary class, so both mixins can share it
 * without referencing each other.</p>
 */
public final class PuppetClip {
    private static boolean clipping;
    private static int attachments;
    private static int disks;
    public static void diskDrawn() {disks++;}
    public static int diskCount() {return disks;}

    private PuppetClip() {}

    /** Clips everything below {@code bandBottom} (virtual pixels), converted to screen pixels here. */
    public static void begin(GuiGraphics graphics,int virtualWidth,float scale,int bandBottom) {
        graphics.enableScissor(0,0,Math.max(1,Math.round(virtualWidth*scale)),
                Math.max(1,Math.round(bandBottom*scale)));
        clipping=true;
    }

    /** Ends the band, if one is open. Safe to call again and safe when nothing was opened. */
    public static void end(GuiGraphics graphics) {
        if(!clipping)return;
        graphics.disableScissor();
        clipping=false;
    }

    /**
     * Ends the band because an attachment (the duel disk) is about to be drawn, which must stay visible.
     * Counted so the harness can prove the attachment hook is actually installed.
     */
    public static void endForAttachments(GuiGraphics graphics) {
        if(!clipping)return;
        graphics.disableScissor();
        clipping=false;
        attachments++;
    }

    /** Attachments drawn outside the crop since startup; zero means the hook did not apply. */
    public static int attachmentCount() {return attachments;}
}
