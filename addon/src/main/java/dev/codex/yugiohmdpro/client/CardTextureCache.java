package dev.codex.yugiohmdpro.client;

import com.kiki.yugioh.client.texture.CardArtTextures;
import com.kiki.yugioh.client.texture.CardTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.codex.yugiohmdpro.MdproAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.LinkedHashMap;
import java.util.List;

/** Render-thread LRU. Evict after drawing so queued vertices keep valid textures. */
@Mod.EventBusSubscriber(modid=MdproAddon.MOD_ID,value=Dist.CLIENT)
public final class CardTextureCache {
    public static final long MAX_BYTES=64L*1024*1024;
    public static final int MAX_TEXTURES=192;
    private static final LinkedHashMap<ResourceLocation,Long> LIVE=new LinkedHashMap<>(64,0.75f,true);
    private static long bytes;

    public static ResourceLocation smooth(ResourceLocation location) {
        RenderSystem.assertOnRenderThreadOrInit();
        if(LIVE.get(location)==null) {
            var size=CardArtTextures.dimensionsOf(location);
            long cost=mipBytes(size[0],size[1]);
            Minecraft.getInstance().getTextureManager().register(location,new CardTexture(location));
            LIVE.put(location,cost);bytes+=cost;
        }
        return location;
    }
    private static long mipBytes(int width,int height) {
        long total=0;
        for(int level=0;level<=6;level++) {
            total+=(long)width*height*4;
            if(width<=1||height<=1)break;
            width>>=1;height>>=1;
        }
        return total;
    }
    public static void trim() {
        RenderSystem.assertOnRenderThreadOrInit();
        var iterator=LIVE.entrySet().iterator();
        while((bytes>MAX_BYTES||LIVE.size()>MAX_TEXTURES)&&iterator.hasNext()) {
            var entry=iterator.next();
            Minecraft.getInstance().getTextureManager().release(entry.getKey());
            bytes-=entry.getValue();iterator.remove();
        }
    }
    public static void clear() {
        RenderSystem.assertOnRenderThreadOrInit();
        for(var location:List.copyOf(LIVE.keySet()))Minecraft.getInstance().getTextureManager().release(location);
        LIVE.clear();bytes=0;
    }
    public static int count(){return LIVE.size();}
    public static long estimatedBytes(){return bytes;}
    public static boolean contains(ResourceLocation location){return LIVE.containsKey(location);}

    @SubscribeEvent public static void frameEnd(TickEvent.RenderTickEvent event) {
        if(event.phase==TickEvent.Phase.END)trim();
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        var mc=Minecraft.getInstance();
        if(mc.isSameThread())clear();else mc.execute(CardTextureCache::clear);
    }
    @Mod.EventBusSubscriber(modid=MdproAddon.MOD_ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class Reload {
        @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((barrier,manager,preparation,reload,background,game)->
                barrier.wait(net.minecraft.util.Unit.INSTANCE).thenRunAsync(CardArtTextures::invalidate,game));
        }
    }
}
