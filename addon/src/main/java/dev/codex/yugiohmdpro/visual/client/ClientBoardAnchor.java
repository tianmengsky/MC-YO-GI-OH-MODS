// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.world.phys.Vec3
 *  net.minecraftforge.api.distmarker.Dist
 *  net.minecraftforge.client.event.ClientPlayerNetworkEvent$LoggingOut
 *  net.minecraftforge.eventbus.api.SubscribeEvent
 *  net.minecraftforge.fml.common.Mod$EventBusSubscriber
 */
package dev.codex.yugiohmdpro.visual.client;

import dev.codex.yugiohmdpro.visual.board.PinnedAnchor;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon", value={Dist.CLIENT})
public final class ClientBoardAnchor {
    private static final PinnedAnchor ANCHOR = new PinnedAnchor();
    private static UUID duelId;
    private static boolean viewerIsInitiator;
    private static Vec3 origin;

    private ClientBoardAnchor() {
    }

    public static Vec3 current() {
        return origin;
    }

    public static boolean pinned() {
        return ANCHOR.pinned();
    }

    public static double[] forward() {
        return ANCHOR.forward();
    }

    public static boolean viewerIsInitiator() {
        return viewerIsInitiator;
    }

    public static void accept(UUID session, double x, double y, double z, double yawDegrees, boolean viewedAsInitiator) {
        if (!session.equals(duelId)) {
            ANCHOR.clear();
            duelId = session;
        }
        ANCHOR.pin(x, y, z, yawDegrees);
        origin = new Vec3(ANCHOR.x(), ANCHOR.y(), ANCHOR.z());
        viewerIsInitiator = viewedAsInitiator;
    }

    public static void clear() {
        ANCHOR.clear();
        duelId = null;
        origin = null;
        viewerIsInitiator = false;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        dev.codex.yugiohmdpro.client.NativeDuelScreen.clear();
    }
}

