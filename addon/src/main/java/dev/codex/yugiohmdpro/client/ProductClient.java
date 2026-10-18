package dev.codex.yugiohmdpro.client;

import com.google.gson.JsonObject;
import com.kiki.yugioh.client.texture.CardArtTextures;
import com.mojang.blaze3d.vertex.*;
import dev.codex.yugiohmdpro.content.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import java.util.*;

/** Covers load on demand; item icons use the original generated-item model. */
public final class ProductClient {
    public static ResourceLocation cover(JsonObject p) {
        return new ResourceLocation(p.get("cover").getAsBoolean()?"yugioh_mdpro_addon":"yugioh",
            p.get("cover").getAsBoolean()?"textures/product/"+HistoricalContent.str(p,"id")+".png":"textures/item/booster.png");
    }
    public static void open(JsonObject p){Minecraft.getInstance().setScreen(new ProductScreen(p));}
    public static final class Extensions implements IClientItemExtensions {
        private BlockEntityWithoutLevelRenderer renderer;
        @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            if(renderer==null)renderer=new ProductRenderer();return renderer;
        }
    }
    private static final class ProductRenderer extends BlockEntityWithoutLevelRenderer {
        ProductRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
        @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
            ResourceLocation source;
            if(stack.getItem() instanceof HistoricalProductItem item)source=cover(item.product());
            else if(stack.getItem() instanceof MemorialItem memorial)source=new ResourceLocation("yugioh_mdpro_addon","textures/memorial/"+HistoricalContent.str(memorial.data(),"id")+".png");
            else return;
            var location=CardArtTextures.smooth(source);
            int[] size=CardArtTextures.dimensionsOf(location);float w=Math.min(0.9f,0.9f*size[0]/size[1]),h=Math.min(0.9f,0.9f*size[1]/size[0]);
            float left=(1-w)/2,right=1-left,bottom=(1-h)/2,top=1-bottom;
            var consumer=buffers.getBuffer(RenderType.entityCutoutNoCull(location));var matrix=pose.last();
            vertex(consumer,matrix,left,bottom,0.51f,0,1,light,overlay);vertex(consumer,matrix,right,bottom,0.51f,1,1,light,overlay);
            vertex(consumer,matrix,right,top,0.51f,1,0,light,overlay);vertex(consumer,matrix,left,top,0.51f,0,0,light,overlay);
        }
        private static void vertex(VertexConsumer c,PoseStack.Pose p,float x,float y,float z,float u,float v,int light,int overlay) {
            c.vertex(p.pose(),x,y,z).color(255,255,255,255).uv(u,v).overlayCoords(overlay).uv2(light).normal(p.normal(),0,0,1).endVertex();
        }
    }
    public static final class ProductScreen extends Screen {
        private final JsonObject product;
        private final List<JsonObject> all;
        private List<JsonObject> filtered;
        private int page;
        private EditBox search;
        public ProductScreen(JsonObject product) {
            super(Component.literal(HistoricalContent.str(product,"name")));this.product=product;
            all=product.getAsJsonArray("cards").asList().stream().map(e->e.getAsJsonObject()).toList();filtered=all;
        }
        @Override protected void init() {
            search=new EditBox(font,width/2-45,45,Math.min(220,width/2+30),20,Component.literal("卡名 / 密码 / CID"));
            search.setHint(Component.literal("搜索卡名 / 密码 / CID"));search.setResponder(q->{
                String query=q.toLowerCase(Locale.ROOT);filtered=all.stream().filter(r->{
                    var card=HistoricalContent.CARDS.get(HistoricalContent.str(r,"id"));
                    return (r.toString()+(card==null?"":card.toString())).toLowerCase(Locale.ROOT).contains(query);
                }).toList();page=0;
            });addRenderableWidget(search);
            addRenderableWidget(Button.builder(Component.literal("上一页"),b->page=Math.max(0,page-1)).bounds(width-170,height-30,60,20).build());
            addRenderableWidget(Button.builder(Component.literal("下一页"),b->page=Math.max(0,Math.min((filtered.size()-1)/perPage(),page+1))).bounds(width-75,height-30,60,20).build());
            addRenderableWidget(Button.builder(Component.literal("完成"),b->onClose()).bounds(15,height-30,65,20).build());
        }
        private int perPage(){return Math.max(1,(height-110)/24);}
        @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial) {
            renderBackground(g);super.render(g,mouseX,mouseY,partial);
            g.drawCenteredString(font,font.plainSubstrByWidth(title.getString(),width-24),width/2,12,0xffffff);
            String date=HistoricalContent.str(product,"release_date");if(date.isEmpty())date=HistoricalContent.str(product,"index_year")+" 年（日期未知）";
            g.drawCenteredString(font,HistoricalContent.kindLabel(product)+" · "+date+" · "+all.size()+" 种收录",width/2,28,0xbcc8d8);
            var texture=CardArtTextures.smooth(cover(product));int[] size=CardArtTextures.dimensionsOf(texture);
            int maxW=Math.max(35,width/2-100),maxH=Math.max(30,height-160);float scale=Math.min((float)maxW/size[0],(float)maxH/size[1]);
            int w=(int)(size[0]*scale),h=(int)(size[1]*scale);
            g.blit(texture,30,77,w,h,0,0,size[0],size[1],size[0],size[1]);
            if(!product.get("cover").getAsBoolean())g.drawString(font,"暂无已核验封面",20,82+h,0xaaaaaa,false);
            int start=page*perPage();
            for(int i=start;i<Math.min(filtered.size(),start+perPage());i++) {
                var row=filtered.get(i);String id=HistoricalContent.str(row,"id");var card=HistoricalContent.CARDS.get(id);
                String name=card==null?"纪念奖品卡 CID "+HistoricalContent.str(row,"cid"):HistoricalContent.str(card,"name");
                int y=78+(i-start)*24,x=width/2-45;
                var item=card==null?ContentItems.MEMORIAL.get(id).get():com.kiki.yugioh.registry.ModItems.cardItem(id);
                if(item!=null)g.renderItem(new ItemStack(item),x,y);
                g.drawString(font,font.plainSubstrByWidth(name,width-x-50),x+23,y+1,0xffffff,false);
                g.drawString(font,font.plainSubstrByWidth((card==null?"不可决斗":String.format("%08d",HistoricalContent.num(row,"code")))+" · "+HistoricalContent.rarityLabel(HistoricalContent.str(row,"rarity_ids")),width-x-38),x+23,y+12,0x9eacbf,false);
                if(mouseX>=x&&mouseX<width-15&&mouseY>=y&&mouseY<y+23&&item!=null)g.renderTooltip(font,new ItemStack(item),mouseX,mouseY);
            }
            g.drawCenteredString(font,(page+1)+"/"+Math.max(1,(filtered.size()+perPage()-1)/perPage()),width-92,height-24,0xffffff);
        }
        @Override public boolean isPauseScreen(){return false;}
    }
}
