package dev.codex.yugiohmdpro.client;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.client.render.RarityOverlay;
import com.kiki.yugioh.client.texture.CardArtTextures;
import com.kiki.yugioh.item.CardItem;
import dev.codex.yugiohfix.client.BinderClient;
import dev.codex.yugiohfix.client.BinderView;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Responsive card-list geometry shared by drawing, pointer input, wheel and scrollbar. */
public final class DeckBinderLayout {
    /** Legacy defaults, kept only for callers that predate the responsive cell size. */
    public static final int HEADER=60,FOOTER=14,MAX_ROWS=18,MAX_COLS=14;
    public static final int MIN_FILTER=96,MAX_FILTER=150,MAX_STRIP=336;
    public static final int LIST_PAD=DeckEditorLayout.LIST_PAD,LIST_FILTER_W=DeckEditorLayout.LIST_FILTER_W;
    private static final int PANEL_PAD=6;
    private static final int COLOR_CELL=0xFF1A1A26;
    private static final int COLOR_HOVER=0x30FFFFFF;
    private static final int COLOR_EDGE=0xFFFF4444;
    private static final int COLOR_PANEL=0xF0121C2C;
    private static final int COLOR_BORDER=0xFF8A8A8A;

    private DeckBinderLayout() {}

    // ------------------------------------------------------------------ geometry

    /** Right-hand card list panel of the MDPro3-style editor: filter column plus card grid. */
    public static BinderView.Panels panels(int screenWidth) {
        int x=listLeft(screenWidth)+6,w=listWidth(screenWidth)-12;
        return new BinderView.Panels(0,0,x,w,x,0);
    }
    public static int listWidth(int screenWidth) {return Math.max(116,Math.min(380,(int)Math.round(screenWidth*.30)));}
    public static int listLeft(int screenWidth) {return screenWidth-listWidth(screenWidth)-6;}
    public static int listFilterWidth(int screenWidth) {return 0;}

    public static int stripWidth(int leftPos) {
        return Math.max(0,Math.min(MAX_STRIP,leftPos-8));
    }

    public static int stripCols(int width) {
        return width<=0?0:LIST_COLS;
    }

    public static int stripRows(int screenHeight) {
        return 3;
    }

    public static int stripHeight(int screenHeight) {
        return Math.max(1,screenHeight-2*LIST_PAD-2*PANEL_PAD);
    }

    public static int gridX(int panelLeft) {return panelLeft;}

    public static int gridY(int panelTop,int headerHeight) {return panelTop+headerHeight;}

    /** Top of the card list column, matching the editor layout's list panel padding. */
    public static int stripTop(int screenHeight) {return LIST_PAD+PANEL_PAD;}

    /** Only the search field and the 清空 button share the top row of the card list column. */
    public static final int FILTER_TOP_OFFSET=0,CLEAR_CHIP_W=28;

    /** Search field across the top of the card list column, as the reference editor shows it. */
    public static BinderView.Field listSearchField(BinderView.Panels panels,int screenHeight) {
        return new BinderView.Field(panels.left(),stripTop(screenHeight)+16,panels.width()-32,16);
    }
    public static BinderView.Chip listClearChip(Font font,BinderView.Panels panels,int screenHeight) {
        return new BinderView.Chip(panels.left()+panels.width()-29,stripTop(screenHeight)+17,29,"清空",false);
    }
    public static BinderView.Chip filterChip(Font font,BinderView.Panels p,int height) {
        return new BinderView.Chip(p.left(),stripTop(height)+37,32,"筛选",false);
    }

    /**
     * Chips that must not appear: the reviewed deck-code buttons are drawn in one call and are simply
     * left out of the rebuilt column, so their space goes to the search field and the card grid. Zero
     * size also means a click there can never trigger them.
     */
    public static BinderView.Chip hiddenChip() {return new BinderView.Chip(0,0,0,"",false);}

    /** "收取" chip at the top-right of the card grid. */
    public static BinderView.Chip listCollectChip(net.minecraft.client.gui.Font font,int left,int top,int width) {
        String label="收取";
        int w=Math.max(14,font.width(label)+8);
        return new BinderView.Chip(left+width-w-2,top+37,w,label,false);
    }

    /** "排序" chip left of the collect chip: it runs the reviewed tidy operation. */
    public static BinderView.Chip listTidyChip(net.minecraft.client.gui.Font font,int left,int top,int width) {
        BinderView.Chip collect=listCollectChip(font,left,top,width);
        String label="排序";
        int w=Math.max(14,font.width(label)+8);
        return new BinderView.Chip(collect.x()-w-2,collect.y(),w,label,false);
    }

    /** Title of the card list column: the reviewed "卡册" wording replaced by the editor's name. */
    public static String listTitle(int visibleCards) {return "卡片列表";}

    /** Count line under the title, so the column states its own 数量. */
    public static String listCount(int visibleCards,int perPage) {return "共 "+visibleCards+" 张 / 每页 "+perPage;}

    /** Three large cards per row, as requested: the cells grow to fill the card list column. */
    public static final int LIST_COLS=3,LIST_GAP=4,LIST_CELL_MAX=64;

    /** Exactly nine cards; both dimensions constrain the card size so the third row always fits. */
    public static int[] listCell(int width,int height) {
        int gridW=panels(width).width()-8;
        int usable=stripHeight(height)-HEADER-FOOTER;
        int w=Math.max(1,Math.min((gridW-LIST_GAP*2)/3,(int)Math.floor((usable/3-LIST_GAP)*176.0/257)));
        int h=Math.max(1,Math.round(w*257f/176f));
        return new int[]{w,h,w+LIST_GAP,h+LIST_GAP};
    }
    public static int[] listCell() {
        var mc=net.minecraft.client.Minecraft.getInstance();
        return mc.screen instanceof com.kiki.yugioh.client.screen.DeckBoxScreen screen?listCell(screen.width,screen.height):
                listCell(mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());
    }

    public static int maxScroll(int count,int visibleRows,int cols) {
        int rows=(Math.max(1,count)+Math.max(1,cols)-1)/Math.max(1,cols);
        return Math.max(0,rows-visibleRows);
    }

    public static int cellAt(List<Integer> cells,int panelLeft,int panelTop,int headerHeight,int visibleRows,int scroll,double mouseX,double mouseY,int cols) {
        int[] cell=listCell();
        for(int i=0;i<visibleRows*cols;i++) {
            int position=scroll*cols+i;
            if(position>=cells.size())return -1;
            int cx=gridX(panelLeft)+i%cols*cell[2], cy=gridY(panelTop,headerHeight)+i/cols*cell[3];
            if(mouseX<cx||mouseX>=cx+cell[0]||mouseY<cy||mouseY>=cy+cell[1])continue;
            return cells.get(position);
        }
        return -1;
    }

    public static BinderView.Scrollbar scrollbar(int panelLeft,int panelTop,int headerHeight,int visibleRows,int scroll,int capacity,int cols,int width) {
        int[] cell=listCell();
        int track=gridY(panelTop,headerHeight);
        int height=visibleRows*cell[3]-2;
        int maxScroll=maxScroll(capacity,visibleRows,cols);
        int x=panelLeft+width-4-1;
        int totalRows=(capacity+cols-1)/cols;
        int thumb=Math.min(height,Math.max(12,height*visibleRows/Math.max(1,totalRows)));
        int travel=Math.max(0,height-thumb);
        int top=track+travel*Math.max(0,Math.min(maxScroll,scroll))/Math.max(1,maxScroll);
        return new BinderView.Scrollbar(x,track,height,thumb,travel,maxScroll,top);
    }

    // ------------------------------------------------------------------ drawing

    public static ItemStack drawPage(GuiGraphics graphics,Font font,List<Integer> cells,int panelLeft,int panelTop,int headerHeight,int visibleRows,int scroll,int cols,int mouseX,int mouseY,int hoveredCell) {
        ItemStack hovered=ItemStack.EMPTY;
        int[] cell=listCell();
        for(int i=0;i<visibleRows*cols;i++) {
            int position=scroll*cols+i;
            if(position>=cells.size())continue;
            int c=cells.get(position);
            int cx=gridX(panelLeft)+i%cols*cell[2], cy=gridY(panelTop,headerHeight)+i/cols*cell[3];
            graphics.fill(cx,cy,cx+cell[0],cy+cell[1],COLOR_CELL);
            ItemStack stack=BinderClient.stackAt(c);
            if(!stack.isEmpty())drawStackArt(graphics,font,cx,cy,stack);
            if(c!=hoveredCell)continue;
            graphics.fill(cx,cy,cx+cell[0],cy+cell[1],COLOR_HOVER);
            outline(graphics,cx,cy,cell[0],cell[1],COLOR_EDGE);
            hovered=stack;
        }
        return hovered.isEmpty()?null:hovered;
    }

    private static void drawStackArt(GuiGraphics graphics,Font font,int x,int y,ItemStack stack) {
        CardDefinition definition=CardItem.definitionOf(stack);
        if(definition==null)return;
        ResourceLocation texture=CardArtTextures.front(definition);
        int[] size=CardArtTextures.dimensionsOf(texture);
        int[] cell=listCell();
        float factor=Math.min(cell[0]/(float)size[0],cell[1]/(float)size[1]);
        int w=Math.max(1,Math.round(size[0]*factor)), h=Math.max(1,Math.round(size[1]*factor));
        graphics.blit(texture,x+(cell[0]-w)/2,y+(cell[1]-h)/2,w,h,0.0f,0.0f,size[0],size[1],size[0],size[1]);
        CardRarity rarity=CardItem.rarityOf(stack);
        if(rarity!=null)RarityOverlay.render(graphics,x,y,cell[0],cell[1],rarity);
        if(stack.getCount()>1)graphics.renderItemDecorations(font,stack,x+cell[0]-16,y+cell[1]-16);
    }

    /** Panel plate, title and border for the strip; the height comes from {@link #stripHeight}. */
    public static void drawStrip(GuiGraphics graphics,Font font,int left,int top,int width,int screenHeight) {
        int height=stripHeight(screenHeight);
        graphics.fill(left,top,left+width,top+height,COLOR_PANEL);
        outline(graphics,left,top,width,height,COLOR_BORDER);
        graphics.drawString(font,BinderView.text("gui.yugioh_interaction_fix.binder.tab_binder"),left+8,top+6,0xFFFFCC33,false);
    }

    public static void outline(GuiGraphics graphics,int x,int y,int w,int h,int color) {
        graphics.fill(x,y,x+w,y+1,color);
        graphics.fill(x,y+h-1,x+w,y+h,color);
        graphics.fill(x,y,x+1,y+h,color);
        graphics.fill(x+w-1,y,x+w,y+h,color);
    }
}
