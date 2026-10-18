package dev.codex.yugiohmdpro.client;

import dev.codex.yugiohfix.client.BinderClient;
import dev.codex.yugiohfix.client.BinderFilter;
import dev.codex.yugiohfix.client.BinderView;
import dev.codex.yugiohfix.binder.BinderNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/** Deck-only toolbar and filter overlay. All binder operations retain their original server protocol. */
public final class DeckEditorControls {
    private static boolean dragging;
    private DeckEditorControls() {}
    public static int[] popup(int width,int height) {
        int w=Math.min(360,width-24),h=220;
        return new int[]{(width-w)/2,(height-h)/2,w,h};
    }
    public static int[] close(int width,int height) {int[] p=popup(width,height);return new int[]{p[0]+p[2]-40,p[1]+6,34,14};}
    public static void render(AbstractContainerScreen<?> screen,GuiGraphics g,Font font,int mx,int my,float tick) {
        int w=screen.width,h=screen.height;
        var p=DeckBinderLayout.panels(w);int top=DeckBinderLayout.stripTop(h),rows=DeckBinderLayout.stripRows(h),cols=DeckBinderLayout.LIST_COLS;
        var cells=BinderClient.visibleCells();int max=DeckBinderLayout.maxScroll(cells.size(),rows,cols);
        int scroll=Math.max(0,Math.min(max,BinderClient.stripScroll()));BinderClient.setStripScroll(scroll);
        g.drawString(font,"卡片列表",p.left(),top,0xFFF1D391,false);
        String count=""+cells.size();g.drawString(font,count,p.left()+p.width()-font.width(count),top,0xFF9EB1C3,false);
        var input=BinderListActions.search(screen);
        if(input!=null) {
            var field=DeckBinderLayout.listSearchField(p,h);
            input.setX(field.x());input.setY(field.y());input.setWidth(field.w());input.setHeight(field.h());
            input.setHint(net.minecraft.network.chat.Component.literal("名称 / 密码"));
            input.render(g,mx,my,tick);
        }
        BinderView.drawChip(g,font,DeckBinderLayout.listClearChip(font,p,h),mx,my);
        BinderView.drawChip(g,font,DeckBinderLayout.filterChip(font,p,h),mx,my);
        BinderView.drawChip(g,font,DeckBinderLayout.listTidyChip(font,p.left(),top,p.width()),mx,my);
        BinderView.drawChip(g,font,DeckBinderLayout.listCollectChip(font,p.left(),top,p.width()),mx,my);
        int hovered=DeckBinderLayout.cellAt(cells,p.left(),top,DeckBinderLayout.HEADER,rows,scroll,mx,my,cols);
        ItemStack stack=DeckBinderLayout.drawPage(g,font,cells,p.left(),top,DeckBinderLayout.HEADER,rows,scroll,cols,mx,my,hovered);
        var bar=DeckBinderLayout.scrollbar(p.left(),top,DeckBinderLayout.HEADER,rows,scroll,cells.size(),cols,p.width());
        g.fill(bar.x(),bar.track(),bar.x()+4,bar.track()+bar.height(),0xFF1A2939);
        g.fill(bar.x(),bar.thumbTop(),bar.x()+4,bar.thumbTop()+bar.thumb(),0xFF7A94AD);
        String note=BinderClient.message();
        if(note.isEmpty())note=BinderListActions.filtersActive()?"已筛选 · 滚轮翻页":"点击加入卡组 · 滚轮翻页";
        String shortNote=font.width(note)>p.width()?font.plainSubstrByWidth(note,p.width()-font.width("…"))+"…":note;
        g.drawString(font,shortNote,p.left(),h-23,0xFF9EB1C3,false);
        if(!DeckEditorLayout.filtersOpen&&mx>=p.left()&&mx<p.left()+p.width()&&my>=h-25&&my<h-12)
            g.renderTooltip(font,font.split(net.minecraft.network.chat.Component.literal(note),Math.min(240,w-24)),mx,my);
        if(DeckEditorLayout.filtersOpen) {
            g.pose().pushPose();g.pose().translate(0,0,500);
            g.fill(0,0,w,h,0xA0000000);int[] r=popup(w,h);
            DeckEditorLayout.panel(g,r[0],r[1],r[2],r[3]);
            g.drawString(font,"详细筛选",r[0]+8,r[1]+9,0xFFF1D391,false);
            DeckEditorLayout.button(g,font,close(w,h),"完成",true);
            CardFilterPanel.draw(g,font,r,DeckFilterModel.INSTANCE,mx,my);
            g.pose().popPose();return;
        }
        if(!screen.getMenu().getCarried().isEmpty())return;
        if(stack==null||stack.isEmpty()) {
            var slot=screen.getSlotUnderMouse();
            if(slot!=null&&DeckEditorLayout.of(w,h).visible(slot.index))stack=slot.getItem();
        }
        if(stack==null||stack.isEmpty()||com.kiki.yugioh.item.CardItem.definitionOf(stack)==null)return;
        if(hovered>=0)g.renderTooltip(font,stack,mx,my);
    }

    public static boolean click(AbstractContainerScreen<?> screen,Font font,double x,double y,int button) {
        int w=screen.width,h=screen.height;var p=DeckBinderLayout.panels(w);int top=DeckBinderLayout.stripTop(h);
        var layout=DeckEditorLayout.of(w,h);var input=BinderListActions.search(screen);
        if(DeckEditorLayout.filtersOpen) {
            if(button==0) {
                if(DeckEditorLayout.inside(close(w,h),x,y)||!DeckEditorLayout.inside(popup(w,h),x,y))DeckEditorLayout.filtersOpen=false;
                else CardFilterPanel.click(font,popup(w,h),DeckFilterModel.INSTANCE,x,y);
            }
            return true;
        }
        var field=DeckBinderLayout.listSearchField(p,h);
        if(field.contains(x,y)) {
            if(input!=null) {input.mouseClicked(x,y,button);screen.setFocused(input);}
            return true;
        }
        if(input!=null) {input.setFocused(false);screen.setFocused(null);}
        if(button==0) {
            for(int i=0;i<4;i++)if(DeckEditorLayout.inside(layout.tabBounds(i),x,y)) {
                DeckEditorLayout.select(i);DeckEditorLayout.applyIfChanged(screen);return true;
            }
            if(layout.pages()>1&&(DeckEditorLayout.inside(layout.previous(),x,y)||DeckEditorLayout.inside(layout.next(),x,y))) {
                DeckEditorLayout.turn(DeckEditorLayout.inside(layout.previous(),x,y)?-1:1,w,h);DeckEditorLayout.applyIfChanged(screen);return true;
            }
            if(DeckBinderLayout.listClearChip(font,p,h).contains(x,y)) {BinderListActions.clear(screen);return true;}
            if(DeckBinderLayout.filterChip(font,p,h).contains(x,y)) {DeckEditorLayout.filtersOpen=true;return true;}
        }
        if(!DeckEditorLayout.inside(layout.listPanel(),x,y))return false;
        if(button!=0)return true;
        if(DeckBinderLayout.listCollectChip(font,p.left(),top,p.width()).contains(x,y))BinderClient.request(BinderNetwork.Op.COLLECT,0,ItemStack.EMPTY);
        else if(DeckBinderLayout.listTidyChip(font,p.left(),top,p.width()).contains(x,y))BinderClient.request(BinderNetwork.Op.TIDY,0,ItemStack.EMPTY);
        else if(x>=p.left()+p.width()-7&&y>=top+DeckBinderLayout.HEADER) {dragging=true;drag(screen,x,y);}
        else {
            int cell=DeckBinderLayout.cellAt(BinderClient.visibleCells(),p.left(),top,DeckBinderLayout.HEADER,DeckBinderLayout.stripRows(h),BinderClient.stripScroll(),x,y,DeckBinderLayout.LIST_COLS);
            if(cell>=0) {var stack=BinderClient.stackAt(cell);if(!stack.isEmpty()) {dev.codex.yugiohmdpro.duel.DuelNetwork.sendAddOne(cell,stack);BinderClient.request(BinderNetwork.Op.OPEN,0,ItemStack.EMPTY);}}
        }
        return true;
    }
    public static boolean wheel(AbstractContainerScreen<?> screen,double x,double y,double delta) {
        if(DeckEditorLayout.filtersOpen)return true;
        var layout=DeckEditorLayout.of(screen.width,screen.height);
        if(DeckEditorLayout.inside(layout.listPanel(),x,y)) {
            int max=DeckBinderLayout.maxScroll(BinderClient.visibleCells().size(),DeckBinderLayout.stripRows(screen.height),DeckBinderLayout.LIST_COLS);
            BinderClient.setStripScroll(Math.max(0,Math.min(max,BinderClient.stripScroll()+(delta>0?-1:1))));return true;
        }
        if(DeckEditorLayout.inside(layout.deckPanel(),x,y)) {DeckEditorLayout.turn(delta>0?-1:1,screen.width,screen.height);DeckEditorLayout.applyIfChanged(screen);return true;}
        return false;
    }
    public static boolean drag(AbstractContainerScreen<?> screen,double x,double y) {
        if(DeckEditorLayout.filtersOpen)return true;
        if(!dragging)return false;
        var p=DeckBinderLayout.panels(screen.width);int rows=DeckBinderLayout.stripRows(screen.height);
        var bar=DeckBinderLayout.scrollbar(p.left(),DeckBinderLayout.stripTop(screen.height),DeckBinderLayout.HEADER,rows,BinderClient.stripScroll(),BinderClient.visibleCells().size(),DeckBinderLayout.LIST_COLS,p.width());
        BinderClient.setStripScroll(Math.max(0,Math.min(bar.maxScroll(),(int)Math.round((y-bar.track()-bar.thumb()/2.0)*bar.maxScroll()/Math.max(1,bar.travel())))));return true;
    }
    public static boolean release() {boolean result=dragging;dragging=false;return result;}
}
