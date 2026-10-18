package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.card.CardType;
import com.kiki.yugioh.client.screen.CardCatalogScreen;
import dev.codex.yugiohfix.client.BinderView;
import dev.codex.yugiohfix.client.BinderClient;
import dev.codex.yugiohmdpro.client.CatalogWorkspace;
import dev.codex.yugiohmdpro.client.CardFilterPanel;
import dev.codex.yugiohmdpro.client.DeckEditorControls;
import dev.codex.yugiohmdpro.client.CatalogDetails;
import dev.codex.yugiohmdpro.mixin.CatalogAccess;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance fixture for the catalog work. It waits for the visual fixture to open
 * the original catalog screen, swaps in a catalog that renders the hover at fixed coordinates so the
 * screenshot always shows the tooltip, and asserts the reviewed hover, search, layout and paging
 * through the real screen methods, so the mixin wiring is covered and not just its helper logic.
 */
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon",value=Dist.CLIENT)
public final class CatalogSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("yugioh.mdpro.visualSmoke");
    private static final String CARD="blue_eyes_white_dragon";
    private static final String PASSCODE="89631139";
    private static final int CID=4007;
    /** Probe card for search: reviewed Japanese name リミット・リバース, passcode 27551. */
    private static final String SECOND="mdpro_c00027551";
    private static boolean checked;

    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        if(!ENABLED||checked)return;
        if(!(event.getScreen() instanceof CardCatalogScreen))return;
        if(event.getScreen() instanceof HoverCatalog)return;
        checked=true;
        Minecraft minecraft=Minecraft.getInstance();
        try {
            HoverCatalog screen=new HoverCatalog();
            minecraft.setScreen(screen);
            verify(screen,event.getGuiGraphics());
            evidence(screen);
        }catch(Throwable error) {
            try {Files.writeString(result(),"FAIL: catalog fixture: "+error);}catch(Exception ignored) {}
            LoggerFactory.getLogger("MDProCatalogSmoke").error("Catalog fixture failed",error);
            minecraft.stop();
        }
    }

    private static void verify(HoverCatalog screen,GuiGraphics graphics) throws Exception {
        CatalogAccess access=(CatalogAccess)(Object)screen;
        List<String> notes=new ArrayList<>();
        // 1. The real hover method, including every mixin contribution.
        CardDefinition probe=com.kiki.yugioh.card.Cards.byId(CARD);
        require(probe!=null,"probe card missing: "+CARD);
        String shown=flat(cast(hoverTooltip(screen,probe)));
        String name=Component.translatable("item.yugioh."+CARD).getString();
        require(shown.contains(name),"hover lost the card name: "+name);
        require(shown.contains("发行日期："),"hover lost the release date");
        require(shown.contains("收录卡包："),"hover lost the containing products");
        String effect=Component.translatable(probe.descriptionKey()).getString();
        require(effect.isBlank()||shown.contains(probe(effect)),"hover lost the card effect");
        require(effect.isBlank()||occurrences(shown,probe(effect))==1,"hover printed the rules text "+occurrences(shown,probe(effect))+" times");
        require(!shown.contains("日版收录"),"hover still prints the redundant legacy source summary");
        require(!CatalogDetails.releaseDate(CARD).isEmpty(),"no reviewed release date for "+CARD);
        List<String> products=CatalogDetails.obtainablePackNames(CARD);
        require(products.size()>CatalogDetails.COLLAPSED,"probe card should exceed the collapsed list: "+products.size());
        require(shown.contains(flat(products.get(0))),"hover lost the first containing product");
        require(!shown.contains(flat(products.get(products.size()-1))),"collapsed hover printed every product");
        require(shown.contains("Shift"),"collapsed hover does not explain the expansion");
        String expanded=flat(CatalogDetails.lines(CARD,true));
        for(String product:products)require(expanded.contains(flat(product)),"expanded list lost "+product);
        notes.add("hover products="+products.size()+" date="+CatalogDetails.releaseDate(CARD));

        // 2. Search by passcode, CID, Japanese name, English name and Chinese name.
        EditBox search=access.mdpro$search();
        require(search!=null,"catalog search box missing");
        search.setValue(PASSCODE);
        require(found(access,CARD),"passcode "+PASSCODE+" found no "+CARD+" ("+access.mdpro$results().size()+" results)");
        search.setValue(String.valueOf(CID));
        require(found(access,CARD),"CID "+CID+" found no "+CARD+" ("+access.mdpro$results().size()+" results)");
        search.setValue("リミット・リバース");
        require(found(access,SECOND),"Japanese name found no "+SECOND+" ("+access.mdpro$results().size()+" results)");
        search.setValue("Limit Reverse");
        require(found(access,SECOND),"English name found no "+SECOND+" ("+access.mdpro$results().size()+" results)");
        search.setValue("27551");
        require(found(access,SECOND),"passcode 27551 found no "+SECOND+" ("+access.mdpro$results().size()+" results)");
        search.setValue("青眼白龙");
        require(found(access,CARD),"Chinese name found no "+CARD+" ("+access.mdpro$results().size()+" results)");
        notes.add("search passcode/CID/ja/en/zh");

        // 3. Toolbar and adaptive card hit geometry at the supported small and large sizes.
        int actualW=screen.width,actualH=screen.height;
        for(int[] size:new int[][]{{320,240},{427,246},{638,364},{640,450},{854,480},{1280,720}}) {
            screen.init(Minecraft.getInstance(),size[0],size[1]);
            var l=CatalogWorkspace.layout(screen);
            access.mdpro$buildChips();require(access.mdpro$chips().isEmpty(),"filters must not occupy permanent rows");
            require(access.mdpro$headerHeight()==52,"toolbar height must remain fixed");
            for(int i=0;i<l.cols()*l.rows();i++) {
                int[] r=l.cell(i);
                require(r[0]>=14&&r[0]+r[2]<l.track()[0]&&r[1]>=58&&r[1]+r[3]<=size[1]-24,"card cells must fit inside grid");
                require(l.hit(r[0]+.25,r[1]+.25)==i&&l.hit(r[0]+r[2]-.25,r[1]+r[3]-.25)==i,"card draw/hit must agree");
            }
            int[] f=l.filter(false);screen.mouseClicked(f[0]+1,f[1]+1,0);
            require(CatalogWorkspace.filtersOpen(screen),"real filter button must open popup");
            var popup=DeckEditorControls.popup(screen.width,screen.height);
            for(var choice:CardFilterPanel.choices(Minecraft.getInstance().font,popup,CatalogWorkspace.model(screen))) {
                var chip=choice.chip();
                require(chip.x()>=popup[0]&&chip.x()+chip.w()<=popup[0]+popup[2]&&chip.y()+12<=popup[1]+popup[3],"filter choices must fit popup");
            }
            screen.keyPressed(256,0,0);require(!CatalogWorkspace.filtersOpen(screen),"Escape closes filter only");
            notes.add(size[0]+"x"+size[1]+"="+l.cols()+"x"+l.rows());
        }
        screen.init(Minecraft.getInstance(),actualW,actualH);
        search=access.mdpro$search();search.setValue("");
        var model=CatalogWorkspace.model(screen);model.reset();
        int dragon=CardFilterPanel.races().indexOf(com.kiki.yugioh.card.MonsterType.DRAGON);
        var l=CatalogWorkspace.layout(screen);int[] f=l.filter(false);screen.mouseClicked(f[0]+1,f[1]+1,0);
        var choice=CardFilterPanel.choices(Minecraft.getInstance().font,DeckEditorControls.popup(screen.width,screen.height),model).stream().filter(c->"race".equals(c.group())&&c.index()==dragon).findFirst().orElseThrow();
        screen.mouseClicked(choice.chip().x()+1,choice.chip().y()+1,0);
        require(!access.mdpro$results().isEmpty()&&access.mdpro$results().stream().allMatch(d->d.getMonsterType()==com.kiki.yugioh.card.MonsterType.DRAGON),"catalog race filter must select dragons");
        search.setValue(PASSCODE);require(found(access,CARD),"race and password search must compose");
        model.reset();search.setValue("");screen.keyPressed(256,0,0);
        // Actual catalog frame, without a hover obscuring the expanded card area.
        screen.hover(-20,-20);shot(screen,graphics,"catalog-layout");
        f=l.filter(false);screen.mouseClicked(f[0]+1,f[1]+1,0);shot(screen,graphics,"catalog-filters");screen.keyPressed(256,0,0);

        // 4. The scroll label must describe the row windows it counts, not call them pages.
        String label=Component.translatable("yugioh.gui.list_scroll",7,822).getString();
        require(label.equals("滚动位置 7 / 822 · 滚轮或 Shift 翻页"),"scroll label not corrected: "+label);
        notes.add("label="+label);

        // 5. Wheel and keyboard paging through the real screen methods.
        search.setValue("");
        int max=CatalogWorkspace.layout(screen).maxScroll(access.mdpro$results().size());
        int rows=Math.max(1,access.mdpro$visibleRows());
        require(max>0,"the full pool should need paging");
        require(access.mdpro$results().size()>1000,"full pool should list every imported card");
        access.mdpro$scroll(0);
        key(access,GLFW.GLFW_KEY_PAGE_DOWN,true);
        require(access.mdpro$scroll()==Math.min(rows,max),"PageDown did not page: "+access.mdpro$scroll());
        key(access,GLFW.GLFW_KEY_END,false);
        require(access.mdpro$scroll()==max,"End did not reach the last row window: "+access.mdpro$scroll()+"/"+max);
        key(access,GLFW.GLFW_KEY_HOME,false);
        require(access.mdpro$scroll()==0,"Home did not return to the first row window: "+access.mdpro$scroll());
        key(access,GLFW.GLFW_KEY_END,true);
        require(access.mdpro$scroll()==0,"End must stay with the search box while it is edited");
        key(access,GLFW.GLFW_KEY_PAGE_DOWN,false);
        int paged=access.mdpro$scroll();
        wheel(access,1);
        require(access.mdpro$scroll()==Math.max(0,paged-1),"wheel up did not move one row: "+access.mdpro$scroll());
        wheel(access,-1);
        require(access.mdpro$scroll()==paged,"wheel down did not return: "+access.mdpro$scroll());
        notes.add("paging rows="+rows+" max="+max+" total="+access.mdpro$results().size());

        // 6. Leave the probe card hovered so the captured screenshot shows the tooltip.
        search.setValue(PASSCODE);
        search.setFocused(false);
        access.mdpro$scroll(0);
        int index=indexOf(access.mdpro$results(),CARD);
        require(index>=0,"the probe card is missing from its own passcode search");
        int[] cell=CatalogWorkspace.layout(screen).cell(index);
        screen.hover(cell[0]+cell[2]/2,cell[1]+cell[3]/2);
        shot(screen,graphics,"catalog-tooltip");
        // Both tabs share the adaptive grid; binder filters retain the actual server cell indices.
        var session=java.util.UUID.randomUUID();BinderClient.begin(session);
        var seeded=new ArrayList<dev.codex.yugiohfix.binder.CardBinder.Cell>();
        for(int i=0;i<90;i++) {
            var item=com.kiki.yugioh.registry.ModItems.cardItem(i%2==0?CARD:SECOND);
            seeded.add(new dev.codex.yugiohfix.binder.CardBinder.Cell(i*2,new net.minecraft.world.item.ItemStack(item,2)));
        }
        BinderClient.receive(new dev.codex.yugiohfix.binder.BinderNetwork.Snapshot(session,java.util.UUID.randomUUID(),180,seeded,""));
        search.setValue("");int[] tab=l.tab(1);screen.mouseClicked(tab[0]+1,tab[1]+1,0);
        require(CatalogWorkspace.binder()&&CatalogWorkspace.count(screen)==90,"binder tab must show occupied card types");
        f=l.filter(true);screen.mouseClicked(f[0]+1,f[1]+1,0);
        choice=CardFilterPanel.choices(Minecraft.getInstance().font,DeckEditorControls.popup(screen.width,screen.height),model).stream().filter(c->"race".equals(c.group())&&c.index()==dragon).findFirst().orElseThrow();
        screen.mouseClicked(choice.chip().x()+1,choice.chip().y()+1,0);
        require(CatalogWorkspace.count(screen)==45,"binder race filter must exclude traps");
        for(int id:CatalogWorkspace.cells(screen))require(id%4==0,"filtered cells must retain original sparse indices");
        search.setValue(PASSCODE);require(CatalogWorkspace.count(screen)==45,"binder search composes with race");
        model.reset();search.setValue("");screen.keyPressed(256,0,0);
        screen.hover(-20,-20);shot(screen,graphics,"binder-layout");
        screen.mouseClicked(f[0]+1,f[1]+1,0);shot(screen,graphics,"binder-filters");screen.keyPressed(256,0,0);
        screen.keyPressed(GLFW.GLFW_KEY_END,0,0);
        require(access.mdpro$scroll()==l.maxScroll(90),"binder End must reach last page");
        int[] track=l.track();screen.mouseClicked(track[0]+1,track[1]+1,0);screen.mouseDragged(track[0]+1,track[1]+track[3],0,0,40);screen.mouseReleased(track[0]+1,track[1]+track[3],0);
        require(access.mdpro$scroll()==l.maxScroll(90),"binder scrollbar must reach bottom");
        // Leave the catalog probe hover as expected by the existing visual suite.
        tab=l.tab(0);screen.mouseClicked(tab[0]+1,tab[1]+1,0);search.setValue(PASSCODE);access.mdpro$scroll(0);
        cell=l.cell(indexOf(access.mdpro$results(),CARD));screen.hover(cell[0]+cell[2]/2,cell[1]+cell[3]/2);
        Files.writeString(FMLPaths.GAMEDIR.get().resolve("../generated/catalog-layout.txt").normalize(),"PASS: real catalog/binder compact toolbar, adaptive grid at 6 sizes, modal input, race + search, sparse binder cell indices, wheel/keys/drag. "+String.join("; ",notes));
        LoggerFactory.getLogger("MDProCatalogSmoke").info("Catalog fixture verified: {}",String.join("; ",notes));
    }

    private static void shot(HoverCatalog s,GuiGraphics graphics,String name) throws Exception {
        // Several acceptance frames are drawn synchronously; clear depth as the normal game frame does.
        graphics.flush();com.mojang.blaze3d.systems.RenderSystem.clear(256,Minecraft.ON_OSX);
        s.render(graphics,-20,-20,0);graphics.flush();
        try(var pixels=net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            pixels.writeToFile(FMLPaths.GAMEDIR.get().resolve("../generated/"+name+".png").normalize());
        }
    }

    private static void evidence(HoverCatalog screen) throws Exception {
        CatalogAccess access=(CatalogAccess)(Object)screen;
        CardDefinition probe=com.kiki.yugioh.card.Cards.byId(CARD);
        StringBuilder text=new StringBuilder();
        text.append("搜索框：").append(access.mdpro$search().getValue()).append('\n');
        text.append("结果 ").append(access.mdpro$results().size()).append(" 张，悬浮第 ").append(indexOf(access.mdpro$results(),CARD)+1).append(" 张\n");
        text.append("悬浮提示（tooltipOf 实际返回值）：\n");
        for(Component line:cast(hoverTooltip(screen,probe)))text.append("  ").append(line.getString()).append('\n');
        text.append("滚动标签：").append(Component.translatable("yugioh.gui.list_scroll",1,822).getString()).append('\n');
        text.append("Shift 展开后的收录卡包：").append(String.join(" / ",CatalogDetails.obtainablePackNames(CARD))).append('\n');
        Files.writeString(result(),text.toString());
        LoggerFactory.getLogger("MDProCatalogSmoke").info("Catalog tooltip evidence written to generated/catalog-tooltip.txt");
    }

    @SuppressWarnings("unchecked")
    private static List<Component> hoverTooltip(CardCatalogScreen screen,CardDefinition card) throws Exception {
        Method tooltip=CardCatalogScreen.class.getDeclaredMethod("tooltipOf",CardDefinition.class);
        tooltip.setAccessible(true);
        return (List<Component>)tooltip.invoke(screen,card);
    }

    private static void key(CatalogAccess catalog,int keyCode,boolean editing) {
        EditBox search=catalog.mdpro$search();
        if(search!=null)search.setFocused(editing);
        ((Screen)catalog).keyPressed(keyCode,0,0);
    }

    private static void wheel(CatalogAccess catalog,double delta) {
        ((CardCatalogScreen)catalog).mouseScrolled(0,0,delta);
    }

    private static boolean found(CatalogAccess catalog,String card) {return indexOf(catalog.mdpro$results(),card)>=0;}

    private static int indexOf(List<CardDefinition> cards,String id) {
        for(int i=0;i<cards.size();i++)if(cards.get(i).getId().equals(id))return i;
        return -1;
    }

    /** Highest pixel the drawn filter chips reach; every chip row is 14 tall with 2 of padding. */
    private static int chipExtent(List<Object> chips) throws Exception {
        require(!chips.isEmpty(),"no filter chips were built");
        int extent=0;
        for(Object chip:chips) {
            Method y=chip.getClass().getDeclaredMethod("y");
            y.setAccessible(true);
            extent=Math.max(extent,((Number)y.invoke(chip)).intValue()+12);
        }
        return extent;
    }

    @SuppressWarnings("unchecked")
    private static List<Component> cast(Object value) {return (List<Component>)value;}

    /** Joined tooltip text without line breaks: the rules wrapper decides where lines end. */
    private static String flat(List<Component> lines) {
        StringBuilder text=new StringBuilder();
        for(Component line:lines)text.append(line.getString());
        return text.toString().replaceAll("\\s+","");
    }

    private static String flat(String text) {return text.replaceAll("\\s+","");}

    private static String probe(String text) {return flat(text.substring(0,Math.min(12,text.length())));}

    private static int occurrences(String text,String needle) {
        if(needle.isEmpty())return 0;
        int count=0;
        for(int at=text.indexOf(needle);at>=0;at=text.indexOf(needle,at+needle.length()))count++;
        return count;
    }

    private static void require(boolean condition,String message) {
        if(!condition)throw new IllegalStateException(message);
    }

    private static Path result() {return FMLPaths.GAMEDIR.get().resolve("../generated/catalog-tooltip.txt").normalize();}

    /** The original catalog, rendered with a fixed pointer so the hover tooltip is deterministic. */
    private static final class HoverCatalog extends CardCatalogScreen {
        private int hoverX,hoverY;
        HoverCatalog() {super(new TitleScreen());}
        void hover(int x,int y) {hoverX=x;hoverY=y;}
        @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
            super.render(graphics,hoverX,hoverY,partialTick);
        }
    }
    /** Reads one of the catalog screen's grid constants, so the binder tab is compared against it. */
    private static int staticInt(String name) {
        try {
            var field=CardCatalogScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException missing) {
            throw new IllegalStateException("catalog grid constant "+name+" is not readable",missing);
        }
    }}
