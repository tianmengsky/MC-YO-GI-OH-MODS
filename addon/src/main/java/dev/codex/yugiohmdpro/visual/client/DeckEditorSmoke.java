package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.item.CardItem;
import com.kiki.yugioh.registry.ModItems;
import dev.codex.yugiohfix.client.BinderView;
import dev.codex.yugiohmdpro.client.BinderListActions;
import dev.codex.yugiohmdpro.client.DeckBinderLayout;
import dev.codex.yugiohmdpro.client.DeckEditorLayout;
import dev.codex.yugiohmdpro.client.DeckRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.LoggerFactory;

/**
 * Development-only fixture for the rebuilt deck editor.
 *
 * <p>It renders the layout with a synthetic 90+36 slot list, so the arrangement can be inspected and
 * measured without a live deck box menu, and writes both a screenshot and the geometry evidence.</p>
 */
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon",value=Dist.CLIENT)
public final class DeckEditorSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("yugioh.mdpro.deckSmoke");
    private static final String[] SAMPLE={
        "blue_eyes_white_dragon","dark_magician","prohibition","celtic_guardian","monster_reborn",
        "dark_hole","honest","call_of_the_haunted","red_eyes_black_dragon","black_luster_soldier",
        "mystical_space_typhoon","book_of_moon","solemn_judgment","bottomless_trap_hole","mirror_force"};
    private static boolean started;
    private static int frames;

    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        if(!ENABLED||started)return;
        Minecraft minecraft=Minecraft.getInstance();
        if(minecraft.getOverlay()!=null)return;
        // Wait for the real window size: the layout is measured and drawn in the same frame.
        if(minecraft.getWindow().getGuiScaledWidth()<320||minecraft.getWindow().getGuiScaledHeight()<200)return;
        started=true;
        minecraft.options.guiScale().set(Integer.getInteger("yugioh.mdpro.deckGuiScale",2));
        minecraft.resizeDisplay();
        LoggerFactory.getLogger("MDProDeckEditorSmoke").info("Deck editor preview at {}x{}",
                minecraft.getWindow().getGuiScaledWidth(),minecraft.getWindow().getGuiScaledHeight());
        minecraft.setScreen(new Preview());
    }

    /** Synthetic menu slots: 60 main, 15 extra, 15 side, 36 inventory. */
    static List<Slot> slots() {
        SimpleContainer deck=new SimpleContainer(DeckEditorLayout.MAIN_SLOTS+DeckEditorLayout.EXTRA_SLOTS+DeckEditorLayout.SIDE_SLOTS);
        SimpleContainer inventory=new SimpleContainer(DeckEditorLayout.INVENTORY_SLOTS);
        List<Slot> slots=new ArrayList<>();
        for(int index=0;index<deck.getContainerSize();index++)slots.add(new Slot(deck,index,0,0));
        for(int index=0;index<inventory.getContainerSize();index++)slots.add(new Slot(inventory,index,0,0));
        int[] fills={38,9,4,0};
        int[] sizes={DeckEditorLayout.MAIN_SLOTS,DeckEditorLayout.EXTRA_SLOTS,DeckEditorLayout.SIDE_SLOTS};
        int[] froms={0,DeckEditorLayout.MAIN_SLOTS,DeckEditorLayout.MAIN_SLOTS+DeckEditorLayout.EXTRA_SLOTS};
        for(int section=0;section<3;section++) {
            for(int i=0;i<fills[section];i++) {
                String id=SAMPLE[(section*5+i)%SAMPLE.length];
                var item=ModItems.cardItem(id);
                if(item==null)continue;
                CardRarity rarity=CardRarity.values()[(i+section)%CardRarity.values().length];
                deck.setItem(froms[section]+i,CardItem.withRarity(new ItemStack(item),rarity));
            }
        }
        for(int i=0;i<12;i++) {
            var item=ModItems.cardItem(SAMPLE[i%SAMPLE.length]);
            if(item!=null)inventory.setItem(i,new ItemStack(item));
        }
        return slots;
    }

    private static void capture() throws Exception {
        try(var pixels=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            pixels.writeToFile(FMLPaths.GAMEDIR.get().resolve("../generated/deck-editor-layout.png").normalize());
        }
        LoggerFactory.getLogger("MDProDeckEditorSmoke").info("RENDERED: deck-editor-layout.png");
    }

    private static void evidence() throws Exception {
        int w=Minecraft.getInstance().getWindow().getGuiScaledWidth(),h=Minecraft.getInstance().getWindow().getGuiScaledHeight();
        var layout=DeckEditorLayout.of(w,h);
        Files.writeString(result(),"窗口 "+w+"x"+h+" 主卡图="+java.util.Arrays.toString(layout.cellSize())
                +" 卡组面板="+java.util.Arrays.toString(layout.deckPanel())+" 列表="+java.util.Arrays.toString(layout.listPanel())
                +" 分页="+layout.pages()+"\n"+rules()+"\n");
    }

    private static int count(List<Slot> slots,int from,int size) {
        int total=0;
        for(int index=from;index<from+size;index++)if(!slots.get(index).getItem().isEmpty())total++;
        return total;
    }

    /**
     * Checks the deck advisor against the same limits the reviewed menu enforces: main deck size,
     * extra and side limits, and the copy limit shared by all three sections.
     */
    private static String rules() {
        var legal=rules(slots(true,true));
        require(legal.legal(),"a deck inside every limit must be legal: "+legal.warnings());
        require(legal.main()>=legal.minMain()&&legal.main()<=legal.maxMain(),"main deck count out of range");
        var undersized=rules(slots(false,true));
        require(!undersized.legal()&&undersized.main()<undersized.minMain(),"an under-sized main deck must warn");
        require(undersized.warnings().stream().anyMatch(w->w.text().contains("还差")),"missing 还差 warning: "+undersized.warnings());
        var over=rules(extraOverflow());
        require(over.warnings().stream().anyMatch(w->w.text().contains("额外卡组超出")),
                "an over-full extra deck must warn: "+over.warnings());
        var tooMany=rules(copies(4));
        require(tooMany.warnings().stream().anyMatch(w->w.text().contains("同名超限")),
                "four copies must warn: "+tooMany.warnings());
        return "规则校验：合规卡组 legal="+legal.legal()+" 主卡组="+legal.main()+"/"+legal.maxMain()
                +" 缺卡警告="+undersized.warnings().get(0).text()+" 超量警告="+over.warnings().get(0).text()
                +" 同名警告="+tooMany.warnings().get(0).text();
    }

    private static DeckRules.Verdict rules(List<Slot> slots) {return DeckRules.evaluate(slots);}

    /** Synthetic deck with a controllable main deck size, so the limit warnings can be exercised. */
    private static List<Slot> slots(boolean completeMain,boolean fillExtras) {return slots(completeMain,fillExtras,0);}

    /**
     * Distinct cards for the rule checks: one copy per slot keeps every card inside the shared copy
     * limit, so the size rules can be tested without tripping the copy rule by accident.
     */
    private static List<Slot> slots(boolean completeMain,boolean fillExtras,int extraMainCards) {
        SimpleContainer deck=new SimpleContainer(DeckEditorLayout.MAIN_SLOTS+DeckEditorLayout.EXTRA_SLOTS+DeckEditorLayout.SIDE_SLOTS);
        SimpleContainer inventory=new SimpleContainer(DeckEditorLayout.INVENTORY_SLOTS);
        List<Slot> slots=new ArrayList<>();
        for(int index=0;index<deck.getContainerSize();index++)slots.add(new Slot(deck,index,0,0));
        for(int index=0;index<inventory.getContainerSize();index++)slots.add(new Slot(inventory,index,0,0));
        int minMain=com.kiki.yugioh.item.DeckContents.minMainCards();
        int main=Math.min(DeckEditorLayout.MAIN_SLOTS,(completeMain?minMain+2:Math.max(0,minMain-5))+extraMainCards);
        for(int i=0;i<main;i++)deck.setItem(i,distinctCard(i));
        if(fillExtras) {
            for(int i=0;i<com.kiki.yugioh.item.DeckContents.maxExtraCards();i++)deck.setItem(DeckEditorLayout.MAIN_SLOTS+i,distinctCard(100+i));
            for(int i=0;i<com.kiki.yugioh.item.DeckContents.maxSideCards();i++)deck.setItem(DeckEditorLayout.MAIN_SLOTS+DeckEditorLayout.EXTRA_SLOTS+i,distinctCard(200+i));
        }
        return slots;
    }

    /** Deck holding the given number of copies of one card, to exercise the shared copy limit. */
    private static List<Slot> copies(int amount) {
        List<Slot> slots=slots();
        SimpleContainer deck=(SimpleContainer)slots.get(0).container;
        for(int i=0;i<amount;i++)deck.setItem(i,distinctCard(0));
        return slots;
    }

    /**
     * Extra deck stacked three cards per slot: the limit counts cards rather than occupied slots, so
     * this is the case that has to be reported.
     */
    private static List<Slot> extraOverflow() {
        List<Slot> slots=slots(true,false);
        SimpleContainer deck=(SimpleContainer)slots.get(0).container;
        for(int i=0;i<DeckEditorLayout.EXTRA_SLOTS;i++)stack(deck,DeckEditorLayout.MAIN_SLOTS+i,distinctCard(100+i),3);
        return slots;
    }

    private static void stack(SimpleContainer deck,int slot,ItemStack stack,int count) {
        if(stack.isEmpty())return;
        ItemStack placed=stack.copy();
        placed.setCount(count);
        deck.setItem(slot,placed);
    }

    private static final List<net.minecraft.world.item.Item> POOL=new ArrayList<>();

    private static List<net.minecraft.world.item.Item> pool() {
        List<net.minecraft.world.item.Item> items=new ArrayList<>();
        for(net.minecraft.world.item.Item item:net.minecraftforge.registries.ForgeRegistries.ITEMS) {
            if(item instanceof CardItem&&items.size()<400)items.add(item);
        }
        return items;
    }

    private static ItemStack distinctCard(int index) {
        if(POOL.isEmpty())POOL.addAll(pool());
        return POOL.isEmpty()?ItemStack.EMPTY:new ItemStack(POOL.get(index%POOL.size()));
    }

    private static void require(boolean condition,String message) {
        if(!condition)throw new IllegalStateException(message);
    }

    private static Path result() {return FMLPaths.GAMEDIR.get().resolve("../generated/deck-editor-layout.txt").normalize();}

    /** Draws the rebuilt editor alone, then captures that very frame once it is on screen. */
    private static final class Preview extends Screen {
        private int drawn;
        private TestScreen editor;
        Preview() {super(Component.literal("组卡界面布局预览"));}
        @Override protected void init() {
            try {
                // Only the player is a stub; the production DeckBoxMenu constructor, slots, NBT
                // handler, DeckBoxScreen, container render loop and all shipped mixins really run.
                var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                var unsafe=(sun.misc.Unsafe)field.get(null);
                FixturePlayer owner=(FixturePlayer)unsafe.allocateInstance(FixturePlayer.class);
                owner.box=new ItemStack(ModItems.DECK_BOX.get());
                var inventory=new net.minecraft.world.entity.player.Inventory(owner);
                var menu=new com.kiki.yugioh.menu.DeckBoxMenu(1,inventory,net.minecraft.world.InteractionHand.MAIN_HAND);
                List<Slot> sample=slots();
                for(int i=0;i<sample.size();i++)menu.slots.get(i).set(sample.get(i).getItem().copy());
                menu.slots.get(90).set(new ItemStack(net.minecraft.world.item.Items.EMERALD,64));
                menu.slots.get(91).set(new ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
                menu.slots.get(0).getItem().setCount(3);
                editor=new TestScreen(menu,inventory);
                // Prevent the fixture from sending OPEN without a server connection.
                var opened=net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("fix$pageOpened");
                opened.setAccessible(true);opened.setBoolean(editor,true);
                Screen previous=minecraft.screen;
                minecraft.screen=editor;
                try {editor.init(minecraft,width,height);}finally {minecraft.screen=previous;}
                var id=java.util.UUID.randomUUID();
                dev.codex.yugiohfix.client.BinderClient.begin(id);
                var cells=new ArrayList<dev.codex.yugiohfix.binder.CardBinder.Cell>();
                for(int i=0;i<170;i++)cells.add(new dev.codex.yugiohfix.binder.CardBinder.Cell(i,sample.get(i%38).getItem().copy()));
                dev.codex.yugiohfix.client.BinderClient.receive(new dev.codex.yugiohfix.binder.BinderNetwork.Snapshot(id,java.util.UUID.randomUUID(),180,cells,""));
            }catch(Exception error) {throw new IllegalStateException("Cannot initialise the real deck editor fixture",error);}
        }
        @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
            Screen previous=minecraft.screen;
            minecraft.screen=editor;
            try {
                if(drawn==4)DeckEditorLayout.filtersOpen=true;
                if(drawn==5) {DeckEditorLayout.filtersOpen=false;DeckEditorLayout.select(1);DeckEditorLayout.applyIfChanged(editor);}
                if(drawn==6) {DeckEditorLayout.select(3);DeckEditorLayout.applyIfChanged(editor);}
                if(drawn==7) {DeckEditorLayout.select(0);DeckEditorLayout.applyIfChanged(editor);}
                int mx=-20,my=-20;
                if(drawn==7) {int[] r=DeckEditorLayout.of(width,height).slotBounds(0);mx=r[0]+r[2]/2;my=r[1]+r[3]/2;}
                editor.render(graphics,mx,my,partialTick);
                graphics.flush();
                drawn++;
                if(drawn<4)return;
                if(drawn==4) {capture();evidence();return;}
                String name=drawn==5?"filters":drawn==6?"extra":drawn==7?"inventory":"hover";
                try(var pixels=Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                    pixels.writeToFile(FMLPaths.GAMEDIR.get().resolve("../generated/deck-editor-"+name+".png").normalize());
                }
                if(drawn<8)return;
                interactions();
            }catch(Throwable error) {
                drawn=4;
                try {Files.writeString(result(),"FAIL: deck editor fixture: "+error);}catch(Exception ignored) {}
                LoggerFactory.getLogger("MDProDeckEditorSmoke").error("Deck editor fixture failed",error);
            }finally {minecraft.screen=previous;}
            if(drawn<4)return;
            Minecraft.getInstance().stop();
        }
        private void interactions() throws Exception {
            int checks=0;
            for(int[] size:new int[][]{{320,240},{427,246},{512,288},{638,364},{640,360},{854,480},{960,540},{1280,720},{1920,1080}}) {
                editor.init(minecraft,size[0],size[1]);
                boolean[] reached=new boolean[126];
                for(int tab=0;tab<4;tab++) {
                    var before=DeckEditorLayout.of(size[0],size[1]);int[] t=before.tabBounds(tab);
                    editor.mouseClicked(t[0]+2,t[1]+2,0);editor.mouseReleased(t[0]+2,t[1]+2,0);
                    require(DeckEditorLayout.tab()==tab,"real tab click must select section "+tab);
                    int pages=DeckEditorLayout.of(size[0],size[1]).pages();
                    for(int page=0;page<pages;page++) {
                        var layout=DeckEditorLayout.of(size[0],size[1]);
                        require(layout.fits(),"visible cells must fit at "+java.util.Arrays.toString(size)+" tab "+tab);
                        require(tab==3||layout.cellSize()[0]>=24,"cards must not shrink below 24 GUI pixels");
                        for(int i=0;i<126;i++) {
                            int[] r=layout.slotBounds(i);
                            if(!layout.visible(i)) {require(!layout.contains(i,20,80),"hidden slot must not intercept pointer");continue;}
                            reached[i]=true;
                            for(double[] corner:new double[][]{{r[0]+.25,r[1]+.25},{r[0]+r[2]-.25,r[1]+.25},{r[0]+.25,r[1]+r[3]-.25},{r[0]+r[2]-.25,r[1]+r[3]-.25}}) {
                                editor.clicked=-1;editor.mouseClicked(corner[0],corner[1],0);editor.mouseReleased(corner[0],corner[1],0);
                                require(editor.clicked==i,"real click selected "+editor.clicked+" instead of "+i+" tab "+tab+" page "+page+" at "+java.util.Arrays.toString(size));checks++;
                            }
                        }
                        if(page+1<pages) {int[] next=layout.next();editor.mouseClicked(next[0]+2,next[1]+2,0);editor.mouseReleased(next[0]+2,next[1]+2,0);
                            require(DeckEditorLayout.page()==page+1,"next page click must advance");}
                    }
                }
                for(int i=0;i<126;i++)require(reached[i],"menu slot "+i+" must remain accessible");
                var panels=DeckBinderLayout.panels(size[0]);
                require(panels.filterWidth()==0,"filters must occupy no permanent column");
                var popup=dev.codex.yugiohmdpro.client.DeckEditorControls.popup(size[0],size[1]);
                var filter=DeckBinderLayout.filterChip(font,panels,size[1]);
                editor.mouseClicked(filter.x()+1,filter.y()+1,0);
                require(DeckEditorLayout.filtersOpen,"filter button must open overlay");
                var chips=dev.codex.yugiohmdpro.client.CardFilterPanel.choices(font,popup,dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE);
                int options=0;
                for(var placed:chips) {
                    var chip=placed.chip();
                    require(chip.x()>=popup[0]&&chip.x()+chip.w()<=popup[0]+popup[2]&&chip.y()>=popup[1]+24&&chip.y()+12<=popup[1]+popup[3],"filter must fit in overlay");
                    if(placed.group()==null)continue;
                    editor.mouseClicked(chip.x()+1,chip.y()+1,0);
                    require(dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE.selected(placed.group())==placed.index(),"real filter click must select its option");
                    dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE.reset();options++;
                }
                require(options==51,"all 51 filter choices must remain available");
                editor.clicked=-1;editor.mouseClicked(9,9,0);
                require(editor.clicked==-1&&!DeckEditorLayout.filtersOpen,"click outside overlay must close without clicking a slot");
                var cell=DeckBinderLayout.listCell(size[0],size[1]);int rows=DeckBinderLayout.stripRows(size[1]);
                require(rows==3,"list must show exactly 3 rows at every supported size");
                var cells=dev.codex.yugiohfix.client.BinderClient.visibleCells();
                for(int i=0;i<9;i++) {
                    int x=DeckBinderLayout.gridX(panels.left())+i%3*cell[2],y=DeckBinderLayout.gridY(DeckBinderLayout.stripTop(size[1]),DeckBinderLayout.HEADER)+i/3*cell[3];
                    for(double[] corner:new double[][]{{x+.25,y+.25},{x+cell[0]-.25,y+cell[1]-.25}})
                        require(DeckBinderLayout.cellAt(cells,panels.left(),DeckBinderLayout.stripTop(size[1]),DeckBinderLayout.HEADER,3,0,corner[0],corner[1],3)==cells.get(i),"all nine list cards must match hit geometry");
                }
                require(panels.left()+cell[2]*2+cell[0]<=panels.left()+panels.width()-7,"list cards must fit before scrollbar");
                require(DeckBinderLayout.stripTop(size[1])+DeckBinderLayout.HEADER+rows*cell[3]<=size[1]-26,"list rows must leave room for footer");
            }
            editor.init(minecraft,width,height);DeckEditorLayout.select(0);DeckEditorLayout.applyIfChanged(editor);
            var p=DeckBinderLayout.panels(width);var popup=dev.codex.yugiohmdpro.client.DeckEditorControls.popup(width,height);
            var filter=DeckBinderLayout.filterChip(font,p,height);editor.mouseClicked(filter.x()+1,filter.y()+1,0);
            var rarity=dev.codex.yugiohmdpro.client.CardFilterPanel.choices(font,popup,dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE).stream().filter(c->"rarity".equals(c.group())).findFirst().orElseThrow();
            editor.mouseClicked(rarity.chip().x()+1,rarity.chip().y()+1,0);
            require(dev.codex.yugiohfix.client.BinderFilter.active(),"real filter click must activate filter");
            dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE.reset();
            int dragon=dev.codex.yugiohmdpro.client.CardFilterPanel.races().indexOf(com.kiki.yugioh.card.MonsterType.DRAGON);
            var race=dev.codex.yugiohmdpro.client.CardFilterPanel.choices(font,popup,dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE).stream().filter(c->"race".equals(c.group())&&c.index()==dragon).findFirst().orElseThrow();
            editor.mouseClicked(race.chip().x()+1,race.chip().y()+1,0);
            require(dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE.raceActive(),"race click must activate filter");
            var filtered=dev.codex.yugiohfix.client.BinderClient.visibleCells();
            require(!filtered.isEmpty(),"dragon filter should retain seeded dragons");
            for(int cell:filtered)require(CardItem.definitionOf(dev.codex.yugiohfix.client.BinderClient.stackAt(cell)).getMonsterType()==com.kiki.yugioh.card.MonsterType.DRAGON,"race filter must reject other monsters and spells");
            editor.mouseClicked(race.chip().x()+1,race.chip().y()+1,0);
            require(!dev.codex.yugiohmdpro.client.DeckFilterModel.INSTANCE.raceActive(),"repeat race click must clear selection");
            editor.mouseClicked(race.chip().x()+1,race.chip().y()+1,0);
            editor.keyPressed(256,0,0);require(!DeckEditorLayout.filtersOpen,"Escape must close filter without closing editor");
            var input=BinderListActions.search(editor);
            var field=DeckBinderLayout.listSearchField(p,height);
            editor.mouseClicked(field.x()+2,field.y()+2,0);editor.charTyped('青',0);
            require(input.getValue().equals("青")&&dev.codex.yugiohfix.client.BinderClient.needle().equals("青"),"real search input must receive typed Chinese text");
            input.setValue("89631139");
            dev.codex.yugiohfix.client.BinderClient.setStripScroll(9);
            var clear=DeckBinderLayout.listClearChip(font,p,height);editor.mouseClicked(clear.x()+1,clear.y()+1,0);
            require(!BinderListActions.filtersActive()&&input.getValue().isEmpty()
                    &&dev.codex.yugiohfix.client.BinderClient.needle().isEmpty()&&dev.codex.yugiohfix.client.BinderClient.stripScroll()==0,"clear must reset actual input, filter and scroll");
            editor.mouseScrolled(p.left()+10,height/2, -1);
            require(dev.codex.yugiohfix.client.BinderClient.stripScroll()==1,"real wheel must scroll binder");
            editor.mouseClicked(p.left()+p.width()-4,height/2,0);editor.mouseDragged(p.left()+p.width()-4,height-40,0,0,30);editor.mouseReleased(p.left()+p.width()-4,height-40,0);
            require(dev.codex.yugiohfix.client.BinderClient.stripScroll()>1,"real scrollbar drag must scroll binder");
            editor.getMenu().setCarried(new ItemStack(net.minecraft.world.item.Items.EMERALD,7));
            int[] tab=DeckEditorLayout.of(width,height).tabBounds(3);
            editor.clicked=-1;editor.mouseClicked(tab[0]+2,tab[1]+2,0);
            int[] target=DeckEditorLayout.of(width,height).slotBounds(90);
            editor.mouseDragged(target[0]+3,target[1]+3,0,20,20);editor.mouseReleased(target[0]+3,target[1]+3,0);
            require(editor.clicked==-1&&editor.getMenu().getCarried().getCount()==7,"toolbar drag/release must retain carried stack without a container transaction");
            editor.getMenu().setCarried(ItemStack.EMPTY);
            editor.init(minecraft,427,246);DeckEditorLayout.select(0);DeckEditorLayout.turn(1,427,246);DeckEditorLayout.applyIfChanged(editor);
            editor.init(minecraft,1280,720);
            require(DeckEditorLayout.page()<DeckEditorLayout.of(1280,720).pages(),"resize must clamp the selected page");
            Files.writeString(result(),"PASS: real DeckBoxScreen + DeckBoxMenu; "+checks+" corner clicks across 9 sizes; every slot accessible via tabs/pages; 51 filter options including all 24 races; modal input; clear; wheel and scrollbar; "+Files.readString(result()));
        }
        @Override public boolean isPauseScreen() {return false;}
    }

    private static final class TestScreen extends com.kiki.yugioh.client.screen.DeckBoxScreen {
        int clicked=-1;
        TestScreen(com.kiki.yugioh.menu.DeckBoxMenu menu,net.minecraft.world.entity.player.Inventory inventory) {super(menu,inventory,Component.literal("组卡"));}
        @Override protected void slotClicked(Slot slot,int index,int button,net.minecraft.world.inventory.ClickType type) {clicked=index;}
    }

    private static final class FixturePlayer extends net.minecraft.world.entity.player.Player {
        ItemStack box;
        private FixturePlayer() {super(null,net.minecraft.core.BlockPos.ZERO,0,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"GuiFixture"));}
        @Override public ItemStack getItemInHand(net.minecraft.world.InteractionHand hand) {return box;}
        @Override public boolean isSpectator() {return false;}
        @Override public boolean isCreative() {return false;}
    }
}
