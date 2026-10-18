package dev.codex.yugiohmdpro.client;

import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.item.CardItem;
import com.kiki.yugioh.menu.DeckBoxMenu;
import dev.codex.yugiohfix.client.BinderView;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Responsive deck workspace. Tabs and pages only change slot visibility, never menu order or NBT. */
public final class DeckEditorLayout {
    public static final int MAIN_SLOTS=60,EXTRA_SLOTS=15,SIDE_SLOTS=15,INVENTORY_SLOTS=36;
    public static final int MAIN_COLS=10,EXTRA_COLS=5,SIDE_COLS=5,INVENTORY_COLS=9,INVENTORY_ROWS=4;
    public static final int INVENTORY_ROWS_MAIN=3,HOTBAR_ROWS=1,HOTBAR_GAP=5;
    public static final int INVENTORY_CELL_MIN=18,INVENTORY_CELL_MAX=40,SECTION_TITLE=16,MIN_CELL_W=24;
    public static final int LIST_PAD=6,LIST_FILTER_W=0,LIST_HEADER=60,LIST_FOOTER=16,PITCH_GAP=3,STATUS_HEIGHT=18;
    private static final int[] FROM={0,60,75,90},SIZE={60,15,15,36};
    private static final String[] LABEL={"主卡组","额外","副卡组","背包"};
    private static int activeTab,page;
    private static Object owner;
    public static boolean filtersOpen;
    public static void open(Object screen) {
        if(owner==screen)return;
        owner=screen;activeTab=0;page=0;filtersOpen=false;invalidate();
    }
    public static int tab() {return activeTab;}
    public static int page() {return page;}
    public static void select(int tab) {activeTab=Math.max(0,Math.min(3,tab));page=0;invalidate();}
    public static void turn(int delta,int width,int height) {
        int max=of(width,height).pages()-1;
        page=Math.max(0,Math.min(max,page+delta));invalidate();
    }
    private final int screenWidth,screenHeight,deckX=6,deckY=6,deckW,deckH;
    private final int listX,listY=6,listW,listH,cellW,cellH,invCell,columns,rows,capacity;
    private final int[] origin;
    public DeckEditorLayout(int width,int height) {
        screenWidth=width;screenHeight=height;
        listW=DeckBinderLayout.listWidth(width);listX=DeckBinderLayout.listLeft(width);listH=height-12;
        deckW=listX-12;deckH=height-12;
        int availableW=deckW-16,availableH=deckH-94;
        columns=activeTab==0?Math.max(5,Math.min(10,(deckW-3)/(MIN_CELL_W+PITCH_GAP))):activeTab==3?9:5;
        invCell=Math.max(18,Math.min(40,Math.min((availableW-8*PITCH_GAP)/9,(availableH-HOTBAR_GAP)/4-PITCH_GAP)));
        cellW=activeTab==3?invCell:Math.max(24,Math.min(56,Math.min((availableW-(columns-1)*PITCH_GAP)/columns,
                activeTab==0?56:(int)((availableH/3-PITCH_GAP)*176f/257f))));
        cellH=activeTab==3?invCell:Math.round(cellW*257f/176f);
        rows=activeTab==3?4:Math.max(1,availableH/(cellH+PITCH_GAP));
        capacity=columns*rows;
        page=Math.min(page,(SIZE[activeTab]-1)/capacity);
        int gridW=columns*(cellW+PITCH_GAP)-PITCH_GAP;
        origin=new int[]{deckX+(deckW-gridW)/2,deckY+62};
    }
    public int pages() {return (SIZE[activeTab]+capacity-1)/capacity;}
    public boolean visible(int index) {
        return index>=FROM[activeTab]+page*capacity&&index<Math.min(FROM[activeTab]+SIZE[activeTab],FROM[activeTab]+(page+1)*capacity);
    }
    public int[] slot(int index) {
        if(!visible(index))return new int[]{-10000-index*20,-10000};
        int local=index-FROM[activeTab]-page*capacity;
        int y=origin[1]+local/columns*(cellH+PITCH_GAP);
        if(activeTab==3&&local>=27)y+=HOTBAR_GAP;
        return new int[]{origin[0]+local%columns*(cellW+PITCH_GAP),y};
    }
    public int[] slotBounds(int index) {int[] at=slot(index);return new int[]{at[0],at[1],cellW,cellH};}
    public boolean contains(int index,double x,double y) {return visible(index)&&inside(slotBounds(index),x,y);}
    public static boolean inside(int[] r,double x,double y) {return x>=r[0]&&x<r[0]+r[2]&&y>=r[1]&&y<r[1]+r[3];}
    public int[] tabBounds(int tab) {int w=(deckW-12)/4;return new int[]{deckX+6+tab*w,deckY+23,w-2,19};}
    public int[] previous() {return new int[]{deckX+deckW-45,deckY+45,18,14};}
    public int[] next() {return new int[]{deckX+deckW-24,deckY+45,18,14};}
    public int[] deckPanel() {return new int[]{deckX,deckY,deckW,deckH};}
    public int[] listPanel() {return new int[]{listX,listY,listW,listH};}
    public int[] cellSize() {return new int[]{cellW,cellH};}
    public int inventoryCell() {return invCell;}
    public int[] listGrid() {var p=DeckBinderLayout.panels(screenWidth);return new int[]{p.left(),DeckBinderLayout.stripTop(screenHeight)+DeckBinderLayout.HEADER,p.width(),listRows()*DeckBinderLayout.listCell(screenWidth,screenHeight)[3]};}
    public int listRows() {return DeckBinderLayout.stripRows(screenHeight);}
    public int listCols() {return DeckBinderLayout.LIST_COLS;}
    public int listCardsPerPage() {return listRows()*listCols();}
    public int listScrollbarX() {return listX+listW-6;}
    public int[] section(String which) {return new int[]{origin[0],origin[1],deckW-16,rows*(cellH+PITCH_GAP)};}
    public int[] inventorySection() {return new int[]{origin[0],origin[1],9*(invCell+PITCH_GAP)-PITCH_GAP,3*(invCell+PITCH_GAP)};}
    public int[] hotbarSection() {return new int[]{origin[0],origin[1]+3*(invCell+PITCH_GAP)+HOTBAR_GAP,9*(invCell+PITCH_GAP)-PITCH_GAP,invCell};}
    public int sectionsHeight() {return rows*(cellH+PITCH_GAP);}
    public boolean fits() {
        for(int i=0;i<126;i++)if(visible(i)) {
            int[] r=slotBounds(i);
            if(r[0]<deckX||r[1]<deckY+60||r[0]+r[2]>deckX+deckW||r[1]+r[3]>deckY+deckH-32)return false;
        }
        return true;
    }
    public boolean apply(List<Slot> slots) {
        var x=field("x","f_40220_");var y=field("y","f_40221_");if(x==null||y==null)return false;
        try {for(int i=0;i<slots.size();i++) {int[] at=slot(i);x.setInt(slots.get(i),at[0]+1);y.setInt(slots.get(i),at[1]+1);}return true;}
        catch(ReflectiveOperationException e) {return false;}
    }
    public static void invalidate() {cached=null;applied=null;appliedScreen=null;}
    public static boolean applyIfChanged(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        DeckEditorLayout layout=of(screen.width,screen.height);
        if(layout==applied&&screen==appliedScreen)return false;
        ScreenFields.absoluteOrigin(screen);if(!layout.apply(screen.getMenu().slots))return false;
        applied=layout;appliedScreen=screen;return true;
    }

    private static java.lang.reflect.Field field(String... names) {
        for(String name:names) {
            try {
                var found=Slot.class.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            }catch(NoSuchFieldException missing) {
                // try the next spelling
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ drawing

    private static DeckEditorLayout cached;
    private static int cachedWidth=-1,cachedHeight=-1;
    private static DeckEditorLayout applied;
    private static net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> appliedScreen;

    /** One layout per screen size, shared by the slot placement and both render hooks. */
    public static DeckEditorLayout of(int screenWidth,int screenHeight) {
        if(cached==null||cachedWidth!=screenWidth||cachedHeight!=screenHeight) {
            cached=new DeckEditorLayout(screenWidth,screenHeight);
            cachedWidth=screenWidth; cachedHeight=screenHeight;
        }
        return cached;
    }

    public void drawBackground(GuiGraphics g,Font font) {
        panel(g,deckX,deckY,deckW,deckH);panel(g,listX,listY,listW,listH);
        g.drawString(font,"卡组编辑",deckX+8,deckY+7,0xFFF1D391,false);
        String hint="悬停查看卡片详情";
        if(deckW>280)g.drawString(font,hint,deckX+deckW-font.width(hint)-8,deckY+8,0xFF8C9EB0,false);
        drawDeckCells(g);
    }
    public void drawForeground(GuiGraphics g,Font font,List<Slot> slots,int hoveredSlot,boolean carrying) {
        for(int i=0;i<4;i++) {
            int[] r=tabBounds(i);boolean selected=i==activeTab;
            g.fill(r[0],r[1],r[0]+r[2],r[1]+r[3],selected?0xFF314B65:0xFF1B2A3B);
            if(selected)g.fill(r[0],r[1]+r[3]-2,r[0]+r[2],r[1]+r[3],0xFFF1C66A);
            String label=(r[2]<55?(i==0?"主卡":i==2?"副卡":LABEL[i]):LABEL[i])+(i<3?" "+deckCount(slots,FROM[i],SIZE[i]):"");
            g.drawString(font,font.plainSubstrByWidth(label,r[2]-4),r[0]+3,r[1]+5,selected?0xFFFFDF95:0xFFB7C9DB,false);
        }
        String subtitle=activeTab==3?"物品栏 · 快捷栏":"容量 "+deckCount(slots,FROM[activeTab],SIZE[activeTab])+" / "+SIZE[activeTab];
        if(pages()>1)subtitle+="  ·  "+(page+1)+" / "+pages()+" 页";
        g.drawString(font,subtitle,deckX+8,deckY+48,0xFFB7C9DB,false);
        if(pages()>1) {button(g,font,previous(),"‹",page>0);button(g,font,next(),"›",page+1<pages());}
        if(activeTab<3) {
            int[] types=new int[3];
            for(int i=FROM[activeTab];i<FROM[activeTab]+SIZE[activeTab]&&i<slots.size();i++) {
                ItemStack stack=slots.get(i).getItem();var definition=CardItem.definitionOf(stack);
                if(definition!=null)types[definition.getType().ordinal()]+=stack.getCount();
            }
            g.drawString(font,"怪兽 "+types[0]+"   魔法 "+types[1]+"   陷阱 "+types[2],deckX+8,deckY+deckH-29,0xFF9EB1C3,false);
        }
        drawLegality(g,font,DeckRules.evaluate(slots));
        if(hoveredSlot>=0&&visible(hoveredSlot)) {
            g.pose().pushPose();g.pose().translate(0,0,300);
            int[] r=slotBounds(hoveredSlot);renderOutline(g,r[0],r[1],r[2],r[3],0xFFFFD477);g.pose().popPose();
        }
    }
    public static void button(GuiGraphics g,Font font,int[] r,String text,boolean active) {
        g.fill(r[0],r[1],r[0]+r[2],r[1]+r[3],active?0xFF314B65:0xFF1B2A3B);
        g.drawString(font,text,r[0]+(r[2]-font.width(text))/2,r[1]+3,active?0xFFFFDF95:0xFF708295,false);
    }

    /** Card face scaled into one cell, with the reviewed rarity overlay and stack count. */
    public static void drawCardArt(GuiGraphics graphics,Font font,int x,int y,int width,int height,ItemStack stack) {
        com.kiki.yugioh.card.CardDefinition definition=com.kiki.yugioh.item.CardItem.definitionOf(stack);
        if(definition==null)return;
        net.minecraft.resources.ResourceLocation texture=com.kiki.yugioh.client.texture.CardArtTextures.front(definition);
        int[] size=com.kiki.yugioh.client.texture.CardArtTextures.dimensionsOf(texture);
        float factor=Math.min(width/(float)size[0],height/(float)size[1]);
        int w=Math.max(1,Math.round(size[0]*factor)), h=Math.max(1,Math.round(size[1]*factor));
        graphics.blit(texture,x+(width-w)/2,y+(height-h)/2,w,h,0.0f,0.0f,size[0],size[1],size[0],size[1]);
        CardRarity rarity=CardItem.rarityOf(stack);
        if(rarity!=null)com.kiki.yugioh.client.render.RarityOverlay.render(graphics,x,y,width,height,rarity);
    }

    public void drawSlotItem(GuiGraphics graphics,Font font,Slot slot,ItemStack stack) {
        if(stack.isEmpty()||!visible(slot.index))return;
        int[] r=slotBounds(slot.index);
        if(CardItem.definitionOf(stack)!=null)drawCardArt(graphics,font,r[0]+1,r[1]+1,Math.max(1,r[2]-2),Math.max(1,r[3]-2),stack);
        else {
            float scale=Math.max(1,r[2]-2)/16f;
            graphics.pose().pushPose();
            graphics.pose().translate(r[0]+1,r[1]+1,0);
            graphics.pose().scale(scale,scale,1);
            graphics.renderItem(stack,0,0);
            graphics.pose().popPose();
        }
    }

    public void drawSlotCount(GuiGraphics graphics,Font font,Slot slot,ItemStack stack,String text) {
        if(!visible(slot.index))return;
        int[] r=slotBounds(slot.index);
        if(CardItem.definitionOf(stack)==null) {
            float scale=Math.max(1,r[2]-2)/16f;
            graphics.pose().pushPose();
            graphics.pose().translate(r[0]+1,r[1]+1,0);
            graphics.pose().scale(scale,scale,1);
            graphics.renderItemDecorations(font,stack,0,0,text);
            graphics.pose().popPose();
        } else if(text!=null||stack.getCount()>1) {
            String count=text!=null?text:Integer.toString(stack.getCount());
            float scale=Math.min(1f,Math.min((r[2]-2f)/Math.max(1,font.width(count)),(r[3]-2f)/9f));
            graphics.pose().pushPose();
            graphics.pose().translate(r[0]+r[2]-1-font.width(count)*scale,r[1]+r[3]-9*scale,200);
            graphics.pose().scale(scale,scale,1);
            graphics.drawString(font,count,0,0,0xFFFFFFFF,true);
            graphics.pose().popPose();
        }
    }

    /** Deck panel, section titles with counts and rarity splits, and the inventory block. */
    public void drawDeck(GuiGraphics graphics,Font font,List<Slot> slots) {
        drawBackground(graphics,font);
        drawForeground(graphics,font,slots,-1,false);
    }

    /**
     * Legality line along the bottom of the deck panel: either a green summary or the first warnings,
     * so a refused click is explained instead of silently doing nothing.
     */
    private void drawLegality(GuiGraphics graphics,Font font,DeckRules.Verdict verdict) {
        int y=deckY+deckH-12;
        graphics.fill(deckX+LIST_PAD,y-3,deckX+deckW-LIST_PAD,y+10,0x50000000);
        if(verdict.legal()) {
            graphics.drawString(font,font.plainSubstrByWidth("卡组合法 · 主卡组 "+verdict.main()+" 张 · 同名上限 "+verdict.maxCopies()+" 张",deckW-LIST_PAD*2-4),
                    deckX+LIST_PAD+2,y,0xFF63D2A0,false);
            return;
        }
        String note=verdict.warnings().get(0).text();
        if(verdict.warnings().size()>1)note+=" · 另有 "+(verdict.warnings().size()-1)+" 项";
        graphics.drawString(font,font.plainSubstrByWidth(note,deckW-LIST_PAD*2-4),deckX+LIST_PAD+2,y,0xFFFF7A7A,false);
    }

    private static int deckCount(List<Slot> slots,int from,int size) {
        int total=0;
        for(int index=from;index<from+size&&index<slots.size();index++)total+=slots.get(index).getItem().getCount();
        return total;
    }

    public void drawDeckCells(GuiGraphics g) {
        for(int i=0;i<126;i++)if(visible(i)) {
            int[] r=slotBounds(i);
            g.fill(r[0],r[1],r[0]+r[2],r[1]+r[3],0xFF101A27);
            renderOutline(g,r[0],r[1],r[2],r[3],0xFF3B5066);
        }
    }

    public static void panel(GuiGraphics graphics,int x,int y,int w,int h) {
        graphics.fill(x,y,x+w,y+h,0xF0121C2C);
        renderOutline(graphics,x,y,w,h,0xFF8A8A8A);
    }

    public static void renderOutline(GuiGraphics graphics,int x,int y,int w,int h,int color) {
        graphics.fill(x,y,x+w,y+1,color);
        graphics.fill(x,y+h-1,x+w,y+h,color);
        graphics.fill(x,y,x+1,y+h,color);
        graphics.fill(x+w-1,y,x+w,y+h,color);
    }
}
