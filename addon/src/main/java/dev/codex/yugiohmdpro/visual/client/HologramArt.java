// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.kiki.yugioh.client.texture.CardArtTextures
 *  net.minecraft.client.Minecraft
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.server.packs.resources.PreparableReloadListener
 *  net.minecraft.server.packs.resources.PreparableReloadListener$PreparationBarrier
 *  net.minecraft.server.packs.resources.ResourceManager
 *  net.minecraft.util.Unit
 *  net.minecraft.util.profiling.ProfilerFiller
 *  net.minecraftforge.api.distmarker.Dist
 *  net.minecraftforge.client.event.RegisterClientReloadListenersEvent
 *  net.minecraftforge.eventbus.api.SubscribeEvent
 *  net.minecraftforge.fml.common.Mod$EventBusSubscriber
 *  net.minecraftforge.fml.common.Mod$EventBusSubscriber$Bus
 */
package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.client.texture.CardArtTextures;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public final class HologramArt {
    private static final Map<String, ResourceLocation> RESOLVED = new HashMap<String, ResourceLocation>();
    private static final ResourceLocation ABSENT = new ResourceLocation("yugioh_interaction_fix", "cardart/absent");

    private HologramArt() {
    }

    public static ResourceLocation of(String cardId) {
        if (cardId == null || cardId.isEmpty()) {
            return null;
        }
        ResourceLocation found = RESOLVED.get(cardId);
        if (found != null && found != ABSENT && !HologramArt.exists(found)) {
            RESOLVED.remove(cardId);
            found = null;
        }
        if (found == null) {
            ResourceLocation candidate = new ResourceLocation("yugioh_interaction_fix", "textures/cardart/" + cardId + ".png");
            if(!HologramArt.exists(candidate)) {
                var card=dev.codex.yugiohmdpro.content.HistoricalContent.CARDS.get(cardId);
                if(card!=null)candidate=new ResourceLocation("yugioh_mdpro_addon","textures/closeup/"+card.get("code").getAsInt()+".png");
            }
            found = HologramArt.exists(candidate) ? candidate : ABSENT;
            RESOLVED.put(cardId, found);
        }
        return found == ABSENT ? null : CardArtTextures.smooth((ResourceLocation)found);
    }

    public static void invalidate() {
        RESOLVED.clear();
    }

    private static boolean exists(ResourceLocation location) {
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
    }

    @Mod.EventBusSubscriber(modid="yugioh_mdpro_addon", value={Dist.CLIENT}, bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class Reload {
        private Reload() {
        }

        @SubscribeEvent
        public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(new PreparableReloadListener(){

                public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier barrier, ResourceManager manager, ProfilerFiller preparation, ProfilerFiller reload, Executor background, Executor game) {
                    HologramArt.invalidate();
                    return barrier.wait((Object)Unit.INSTANCE).thenApply(ignored -> null);
                }
            });
        }
    }
}

