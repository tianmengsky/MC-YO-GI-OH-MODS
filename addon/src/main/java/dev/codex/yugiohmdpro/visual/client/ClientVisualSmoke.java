package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.duel.CardPosition;
import com.kiki.yugioh.duel.Phase;
import com.kiki.yugioh.network.CardView;
import com.kiki.yugioh.network.S2CDuelStatePacket;
import com.kiki.yugioh.network.S2COpenFormatPacket;
import com.kiki.yugioh.network.SideView;
import dev.codex.yugiohmdpro.client.NativeDuelScreen;
import dev.codex.yugiohmdpro.duel.NativeBoard;
import dev.codex.yugiohmdpro.mixin.DuelScreenAccess;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon",value=Dist.CLIENT)
public final class ClientVisualSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("yugioh.mdpro.visualSmoke");
    private static boolean started,done;
    private static int frames,stage;
    private static CompletableFuture<Void> memoryReload;
    private static final CardView BLUE=new CardView(true,"blue_eyes_white_dragon",1,CardPosition.ATTACK,3000,2500,false,false,false,CardRarity.COMMON);
    private static final CardView DARK=new CardView(true,"dark_magician",2,CardPosition.DEFENSE,2500,2100,false,false,false,CardRarity.COMMON);
    private static List<CardView> slots(CardView first) {return List.of(first,CardView.EMPTY,CardView.EMPTY,CardView.EMPTY,CardView.EMPTY);}
    private static CardView upright(CardView card,int uid) {return new CardView(true,card.cardId(),uid,CardPosition.FACE_UP,card.atk(),card.def(),false,false,false,CardRarity.COMMON);}
    private static CardView imported(int flag,int uid) {
        var row=dev.codex.yugiohmdpro.content.HistoricalContent.CARDS.values().stream().filter(c->c.get("id").getAsString().startsWith("mdpro_c")&&(c.get("type").getAsInt()&flag)!=0).findFirst().orElseThrow();
        return new CardView(true,row.get("id").getAsString(),uid,CardPosition.FACE_UP,row.get("atk").getAsInt(),row.get("def").getAsInt(),false,false,false,CardRarity.COMMON);
    }
    private static CompoundTag fixture() {
        var self=new SideView("测试玩家",8000,30,1,List.of(upright(BLUE,3),imported(0x1000000,20),imported(0x800000,21),imported(0x2000,22),imported(2,23),imported(4,24)),slots(BLUE),slots(CardView.EMPTY),CardView.EMPTY,List.of(upright(DARK,5)),List.of(upright(DARK,6)),false);
        var foe=new SideView("测试对手",6500,35,1,List.of(new CardView(true,"",7,CardPosition.FACE_DOWN,0,0,false,false,false,CardRarity.COMMON)),slots(DARK),slots(CardView.EMPTY),CardView.EMPTY,List.of(),List.of(),false);
        var packet=new S2CDuelStatePacket(0,0,3,Phase.MAIN1,false,-1,Component.empty(),0,self,foe,List.of(Component.literal("Visual fixture / no live duel")),false,false,-1,Component.empty(),List.of(),0,-1);
        var tag=new CompoundTag();var buf=new FriendlyByteBuf(Unpooled.buffer());
        try {packet.encode(buf);byte[] bytes=new byte[buf.readableBytes()];buf.readBytes(bytes);tag.putByteArray("board",bytes);}finally{buf.release();}
        tag.putUUID("duel",UUID.randomUUID());tag.putInt("viewer",0);tag.putInt("revision",1);tag.putInt("kind",11);
        tag.putString("prompt","MDPro 原棋盘 / 投影显示测试");tag.putBoolean("canAct",false);
        var choices=new ListTag();var c=new CompoundTag();c.putString("label","通常召唤：青眼白龙");c.putInt("code",89631139);c.putInt("player",0);c.putInt("location",2);c.putInt("sequence",0);choices.add(c);tag.put("choices",choices);
        for(String p:List.of("self","foe")){tag.putByteArray(p+"pendulum",NativeBoard.encodeCards(List.of(CardView.EMPTY,CardView.EMPTY)));tag.putByteArray(p+"removed",NativeBoard.encodeCards(List.of()));}
        return tag;
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        if(!ENABLED || done)return;
        var mc=Minecraft.getInstance();
        if(mc.getWindow().getGuiScaledWidth()<320||mc.getWindow().getGuiScaledHeight()<200)return;
        try {
            if(!started && mc.getOverlay()==null) {
                for(var card:dev.codex.yugiohmdpro.content.HistoricalContent.CARDS.values()) {
                    String id=card.get("id").getAsString();
                    String shown=Component.translatable("item.yugioh."+id).getString(),expected=card.get("name").getAsString();
                    if(id.startsWith("mdpro_c")?!shown.equals(expected):expected.matches(".*[\\x{4e00}-\\x{9fff}].*")&&!shown.matches(".*[\\x{4e00}-\\x{9fff}].*"))throw new IllegalStateException("Card Chinese name not loaded: "+id+" / "+shown);
                    if(mc.getResourceManager().getResource(new net.minecraft.resources.ResourceLocation("yugioh","textures/card/"+id+".png")).isEmpty())throw new IllegalStateException("Duel-hand face missing: "+id);
                }
                if(dev.codex.yugiohfix.engine.BanList.limitOf("dark_hole")!=3)throw new IllegalStateException("Legacy banlist still active on client");
                var tag=new CompoundTag();var limits=new CompoundTag();limits.putInt(dev.codex.yugiohmdpro.rules.ServerBanlist.key("dark_hole"),0);tag.put("Limits",limits);
                dev.codex.yugiohmdpro.rules.ServerBanlist.receive(tag);
                if(dev.codex.yugiohfix.engine.BanList.limitOf("dark_hole")!=0)throw new IllegalStateException("Client tooltips did not receive dynamic restrictions");
                dev.codex.yugiohmdpro.rules.ServerBanlist.resetClient();
                for(var ref:dev.codex.yugiohmdpro.content.ContentItems.PRODUCTS.values())
                    if(!new net.minecraft.world.item.ItemStack(ref.get()).getHoverName().getString().matches(".*[\\x{4e00}-\\x{9fff}].*"))throw new IllegalStateException("Product Chinese translation not loaded");
                for(var entry:dev.codex.yugiohmdpro.content.HistoricalContent.LEGACY_PRODUCTS.entrySet()) {
                    var item=net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.tryParse(entry.getKey()));
                    var stack=new net.minecraft.world.item.ItemStack(item);
                    if(!stack.getHoverName().getString().equals(entry.getValue().get("name").getAsString()))throw new IllegalStateException("Reused legacy product Chinese name missing: "+entry.getKey());
                    if(mc.getItemRenderer().getModel(stack,null,null,0).isCustomRenderer())throw new IllegalStateException("Reused JP product must use generated-item lighting");
                }
                MemorySmoke.verifyInitial();
                started=true;progress("START: native board");NativeDuelScreen.receive(fixture());return;
            }
            if(stage>0) {
                if(++frames<8)return;
                if(stage==1) {
                    capture("feature-smoke-catalog.png");stage=2;frames=0;
                    com.kiki.yugioh.client.screen.MatchFormatScreen.open(new S2COpenFormatPacket(Component.literal("显示测试对手"),List.of(1,3,5)));return;
                }
                if(stage==2) {
                    capture("feature-smoke-format.png");
                    // The private voice room switch is folded away (see MatchFormatScreenLayoutMixin):
                    // the training row must occupy the voice-room row, and both row indices must hit
                    // that one rectangle, otherwise the visible switch could not be clicked.
                    var screen=mc.screen;
                    var optionsField=screen.getClass().getDeclaredField("options");optionsField.setAccessible(true);
                    int switches=((List<?>)optionsField.get(screen)).size();
                    var rowTop=screen.getClass().getDeclaredMethod("rowTop",int.class);rowTop.setAccessible(true);
                    int folded=(int)rowTop.invoke(screen,switches+3),training=(int)rowTop.invoke(screen,switches+4);
                    if(folded!=training)throw new IllegalStateException("Training row must take the folded voice-room row position");
                    var panelLeft=screen.getClass().getDeclaredMethod("panelLeft");panelLeft.setAccessible(true);
                    var hovers=screen.getClass().getDeclaredMethod("hovers",int.class,int.class,int.class);hovers.setAccessible(true);
                    int left=(int)panelLeft.invoke(screen);
                    if(!(Boolean)hovers.invoke(screen,left+20,training+10,switches+3)||!(Boolean)hovers.invoke(screen,left+20,training+10,switches+4))
                        throw new IllegalStateException("The folded row must be hittable as the training switch");
                    stage=3;frames=0;
                    mc.setScreen(new dev.codex.yugiohfix.client.ItemSelectorScreen(dev.codex.yugiohfix.item.SelectorKind.DUEL_DISK));return;
                }
                if(stage==3) {
                    capture("feature-smoke-selector.png");stage=4;frames=0;
                    mc.setScreen(new dev.codex.yugiohmdpro.client.ProductClient.ProductScreen(dev.codex.yugiohmdpro.content.HistoricalContent.PRODUCTS.get("product_ja_1119003")));return;
                }
                if(stage==4) {
                    capture("content-smoke-product.png");stage=5;frames=0;
                    mc.setScreen(new ProductComparison());return;
                }
                if(stage==5) {
                    capture("content-smoke-product-lighting.png");stage=6;frames=0;
                    mc.setScreen(new com.kiki.yugioh.client.screen.CardCatalogScreen(new TitleScreen()));return;
                }
                if(stage==6) {
                    capture("content-smoke-catalog.png");
                    mc.setScreen(PublicProjectionSmoke.screen());stage=8;frames=0;return;
                }
                if(stage==8) {
                    capture("public-projection-smoke.png");PublicProjectionSmoke.finish();
                    mc.setScreen(new TitleScreen());
                    memoryReload=mc.reloadResourcePacks();stage=7;frames=0;return;
                }
                if(!memoryReload.isDone()||mc.getOverlay()!=null)return;
                memoryReload.join();MemorySmoke.verifyReload();
                Class.forName("com.kiki.yugioh.client.screen.DeckBoxScreen");
                Class.forName("dev.codex.yugiohfix.client.ShopScreen");
                Path out=FMLPaths.GAMEDIR.get().resolve("../generated/visual-smoke-result.txt").normalize();
                Files.writeString(out,"PASS: 8231 Chinese card names and original hand paths, Chinese products, native MDPro frames at 176x257, original/new pack icons under the same lighting, dynamic client restrictions/reset, native board/portraits/LP/2D cutouts/choices, catalog and product membership screen. Synthetic fixtures; live gameplay remains user acceptance.\n");
                done=true;mc.stop();return;
            }
            if(!(event.getScreen() instanceof NativeDuelScreen screen))return;
            frames++;
            if(frames==8) {
                if(DuelScreenHolograms.panelsLastFrame()<2)throw new IllegalStateException("Original field GUI did not render monster projections");
                if(dev.codex.yugiohfix.client.DuelPuppets.puppetsLastFrame()!=2)throw new IllegalStateException("Both original animated player portraits must render");
                if(((DuelScreenAccess)(Object)screen).mdpro$left()<200)throw new IllegalStateException("Portrait LP plates must leave room for the board");
                capture("visual-smoke-board.png");
                var a=(DuelScreenAccess)(Object)screen;
                screen.mouseClicked((a.mdpro$left()+10)*a.mdpro$scale(),(a.mdpro$top()+a.mdpro$boardH()+58)*a.mdpro$scale(),0);
            }
            if(frames==16) {
                capture("visual-smoke-choices.png");
                var imported=dev.codex.yugiohmdpro.content.HistoricalContent.CARDS.values().stream().filter(c->c.get("id").getAsString().startsWith("mdpro_c") && (c.get("type").getAsInt()&1)!=0 && (c.get("type").getAsInt()&0x1000000)!=0).findFirst().orElseThrow();
                var def=com.kiki.yugioh.card.Cards.byId(imported.get("id").getAsString());
                var texture=com.kiki.yugioh.client.texture.CardArtTextures.front(def);
                if(!texture.equals(new net.minecraft.resources.ResourceLocation("yugioh","textures/card/"+def.getId()+".png")))throw new IllegalStateException("Imported face must use the original duel-hand path");
                // Exercise DuelScreen's own direct loader, not only CardArtTextures.
                var loader=com.kiki.yugioh.client.screen.DuelScreen.class.getDeclaredMethod("cardArt",com.kiki.yugioh.card.CardDefinition.class);loader.setAccessible(true);
                var art=loader.invoke(null,def);var t=art.getClass().getDeclaredMethod("texture");t.setAccessible(true);
                if(!t.invoke(art).equals(texture))throw new IllegalStateException("Duel hand still uses a placeholder");
                int[] dimensions=com.kiki.yugioh.client.texture.CardArtTextures.dimensionsOf(texture);
                if(dimensions[0]!=176||dimensions[1]!=257)throw new IllegalStateException("Card face must match original resolution");
                if(HologramArt.of(def.getId())==null)throw new IllegalStateException("Imported Pendulum monster cutout must resolve");
                stage=1;frames=0;mc.setScreen(new com.kiki.yugioh.client.screen.CardCatalogScreen(new TitleScreen()));
            }
        }catch(Throwable error) {
            done=true;
            try {Files.writeString(FMLPaths.GAMEDIR.get().resolve("../generated/visual-smoke-result.txt").normalize(),"FAIL: "+error);}catch(Exception ignored){}
            org.slf4j.LoggerFactory.getLogger("MDProVisualSmoke").error("Visual smoke failed",error);mc.stop();
        }
    }
    private static void capture(String name) throws Exception {
        progress("RENDERED: "+name);
        try(var pixels=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            pixels.writeToFile(FMLPaths.GAMEDIR.get().resolve("../generated/"+name).normalize());
        }
    }
    private static void progress(String message) throws Exception {
        Files.writeString(FMLPaths.GAMEDIR.get().resolve("../generated/visual-smoke-result.txt").normalize(),"IN PROGRESS: "+message+"\n");
        org.slf4j.LoggerFactory.getLogger("MDProVisualSmoke").info(message);
    }
    private static final class ProductComparison extends net.minecraft.client.gui.screens.Screen {
        ProductComparison(){super(Component.literal("复用原卡包 / 日版新产品：相同模型与光照"));}
        @Override public void render(net.minecraft.client.gui.GuiGraphics g,int x,int y,float partial) {
            renderBackground(g);g.drawCenteredString(font,title,width/2,28,0xffffff);
            var items=new ArrayList<net.minecraft.world.item.ItemStack>();
            items.add(new net.minecraft.world.item.ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("yugioh","booster_sod"))));
            for(String id:List.of("product_ja_1101000","product_ja_1119003","product_ja_2201016"))items.add(new ItemStack(dev.codex.yugiohmdpro.content.ContentItems.PRODUCTS.get(id).get()));
            for(int i=0;i<items.size();++i) {
                int spacing=width/(items.size()+1),left=(i+1)*spacing-24;
                g.pose().pushPose();
                g.pose().translate(left,height/2-30,0);
                g.pose().scale(3,3,1);
                g.renderItem(items.get(i),0,0);g.flush();
                g.pose().popPose();
                g.drawCenteredString(font,font.plainSubstrByWidth(items.get(i).getHoverName().getString(),spacing-4),left+24,height/2+28,0xffffff);
            }
        }
    }
}
