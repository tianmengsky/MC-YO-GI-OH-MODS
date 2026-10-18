package dev.codex.yugiohmdpro.visual.client;

import com.google.gson.JsonObject;
import com.kiki.yugioh.client.texture.CardArtTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.codex.yugiohmdpro.client.CardTextureCache;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL11;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/** Development-only GPU checks, run as part of the existing visual fixture. */
public final class MemorySmoke {
    private static final JsonObject RESULT=new JsonObject();
    public static void verifyInitial() throws Exception {
        var mc=Minecraft.getInstance();
        var resources=mc.getResourceManager().listResources("textures/item",p->p.getNamespace().equals("ygostar")&&p.getPath().endsWith(".png"));
        int optimized=0;
        for(var entry:resources.entrySet()) {
            try(var stream=entry.getValue().open()) {
                byte[] header=stream.readNBytes(24);var b=ByteBuffer.wrap(header);
                int w=b.getInt(16),h=b.getInt(20);
                if(Math.max(w,h)>256)throw new IllegalStateException("Oversized legacy sprite still selected: "+entry.getKey());
                if(w==256&&h==256)optimized++;
            }
        }
        if(optimized!=88)throw new IllegalStateException("Expected all 88 optimized legacy sprites, got "+optimized);
        RESULT.addProperty("optimized_sprites",optimized);
        verifyAtlas("initial");
        int loaded=0;
        ResourceLocation first=null,last=null;
        for(var card:HistoricalContent.CARDS.values()) {
            last=new ResourceLocation("yugioh","textures/card/"+HistoricalContent.str(card,"id")+".png");
            if(first==null)first=last;
            CardArtTextures.smooth(last);
            if(++loaded%16==0)CardTextureCache.trim();
            if(loaded==240)break;
        }
        if(CardTextureCache.contains(first))throw new IllegalStateException("Old card texture was not evicted");
        int cutouts=0;
        for(var card:HistoricalContent.CARDS.values()) {
            var path=new ResourceLocation("yugioh_mdpro_addon","textures/closeup/"+HistoricalContent.num(card,"code")+".png");
            if(mc.getResourceManager().getResource(path).isEmpty())continue;
            last=CardArtTextures.smooth(path);
            if(++cutouts%16==0)CardTextureCache.trim();
            if(cutouts==180)break;
        }
        CardTextureCache.trim();
        if(CardTextureCache.count()>CardTextureCache.MAX_TEXTURES||CardTextureCache.estimatedBytes()>CardTextureCache.MAX_BYTES)
            throw new IllegalStateException("Card texture cache exceeded its live memory budget");
        RESULT.addProperty("stress_cards",loaded);RESULT.addProperty("stress_cutouts",cutouts);
        RESULT.addProperty("cache_textures_after_trim",CardTextureCache.count());
        RESULT.addProperty("cache_bytes_after_trim",CardTextureCache.estimatedBytes());
        int textureId=mc.getTextureManager().getTexture(last).getId();
        CardArtTextures.invalidate();
        if(CardTextureCache.count()!=0||CardTextureCache.estimatedBytes()!=0||GL11.glIsTexture(textureId))
            throw new IllegalStateException("Invalidate did not release cached GPU images");
        CardArtTextures.smooth(first);
        if(!CardTextureCache.contains(first)||!GL11.glIsTexture(mc.getTextureManager().getTexture(first).getId()))
            throw new IllegalStateException("Evicted card texture did not reload");
        RESULT.addProperty("eviction_release_and_reload",true);
    }
    public static void verifyReload() throws Exception {
        if(CardTextureCache.count()!=0)throw new IllegalStateException("Full resource reload retained card GPU cache");
        verifyAtlas("reloaded");
        RESULT.addProperty("full_resource_reload",true);
        var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        RESULT.addProperty("heap_used_bytes",heap.getUsed());RESULT.addProperty("heap_max_bytes",heap.getMax());
        RESULT.addProperty("cache_max_bytes",CardTextureCache.MAX_BYTES);
        RESULT.addProperty("cache_max_textures",CardTextureCache.MAX_TEXTURES);
        RESULT.addProperty("baseline_atlas_width",32768);RESULT.addProperty("baseline_atlas_height",16384);
        Files.writeString(FMLPaths.GAMEDIR.get().resolve("../generated/memory-smoke-result.json").normalize(),RESULT.toString());
    }
    private static void verifyAtlas(String phase) {
        var texture=Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        RenderSystem.bindTexture(texture.getId());
        int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);
        int h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
        long bytes=0;
        for(int level=0;level<=Minecraft.getInstance().options.mipmapLevels().get();level++)
            bytes+=(long)GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,level,GL11.GL_TEXTURE_WIDTH)*
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,level,GL11.GL_TEXTURE_HEIGHT)*4;
        RenderSystem.bindTexture(previous);
        if(w<=0||h<=0||w>8192||h>8192)throw new IllegalStateException("Unexpected oversized item atlas: "+w+"x"+h);
        RESULT.addProperty(phase+"_atlas_width",w);RESULT.addProperty(phase+"_atlas_height",h);
        RESULT.addProperty(phase+"_atlas_rgba_bytes_with_mips",bytes);
    }
}
