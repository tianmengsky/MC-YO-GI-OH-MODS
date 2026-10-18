package dev.codex.yugiohmdpro.client;

import com.kiki.yugioh.card.*;
import com.kiki.yugioh.client.screen.CardCatalogScreen;
import com.kiki.yugioh.item.CardItem;
import dev.codex.yugiohfix.binder.BinderNetwork;
import dev.codex.yugiohfix.client.*;
import dev.codex.yugiohmdpro.mixin.CatalogAccess;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/** Compact catalog and binder presentation; existing filters, tooltips and server operations remain authoritative. */
public final class CatalogWorkspace {
    private static final Map<CardCatalogScreen,Ui> STATES=new WeakHashMap<>();
    private static final class Ui {boolean filters,drag;}
    private static Ui ui(CardCatalogScreen s) {return STATES.computeIfAbsent(s,k->new Ui());}
    public static CatalogLayout layout(CardCatalogScreen s) {return new CatalogLayout(s.width,s.height);}
    public static boolean binder() {return BinderClient.tab()==1;}
    public static boolean filtersOpen(CardCatalogScreen s) {return ui(s).filters;}
    public static List<Integer> cells(CardCatalogScreen screen) {
        var a=(CatalogAccess)(Object)screen;var legacy=(dev.codex.yugiohfix.mixin.CardCatalogAccess)(Object)screen;
        String query=a.mdpro$search()==null?"":a.mdpro$search().getValue().trim().toLowerCase(Locale.ROOT);
        var found=new ArrayList<Integer>();
        for(int i=0;i<BinderClient.capacity();i++) {
            ItemStack stack=BinderClient.stackAt(i);var d=CardItem.definitionOf(stack);
            if(d==null||!legacy.fix$matches(d,query))continue;
            if(BinderClient.rarityFilter()!=null&&CardItem.rarityOf(stack)!=BinderClient.rarityFilter())continue;
            found.add(i);
        }
        return found;
    }
    public static int count(CardCatalogScreen s) {return binder()?cells(s).size():((CatalogAccess)(Object)s).mdpro$results().size();}
    public static void placeSearch(CardCatalogScreen s) {
        var box=((CatalogAccess)(Object)s).mdpro$search();if(box==null)return;
        int[] r=layout(s).search(binder());box.setX(r[0]);box.setY(r[1]);box.setWidth(r[2]);box.setHeight(r[3]);
    }
    public static CardFilterPanel.Model model(CardCatalogScreen s) {
        var a=(CatalogAccess)(Object)s;
        return new CardFilterPanel.Model() {
            public int selected(String group) {
                return switch(group) {
                    case "rarity" -> binder()?(BinderClient.rarityFilter()==null?-1:BinderClient.rarityFilter().ordinal()):a.mdpro$rarity().ordinal();
                    case "type" -> a.mdpro$typeFilter()==null?-1:a.mdpro$typeFilter().ordinal();
                    case "attribute" -> a.mdpro$attribute()==null?-1:CardFilterPanel.attributes().indexOf(a.mdpro$attribute());
                    case "race" -> a.mdpro$race()==null?-1:CardFilterPanel.races().indexOf(a.mdpro$race());
                    case "level" -> a.mdpro$level()-1;
                    default -> -1;
                };
            }
            public void choose(String group,int index) {
                switch(group) {
                    case "rarity" -> {if(binder())BinderClient.toggleRarityFilter(CardRarity.values()[index]);else a.mdpro$rarity(CardRarity.values()[index]);}
                    case "type" -> a.mdpro$setType(a.mdpro$typeFilter()==CardType.values()[index]?null:CardType.values()[index]);
                    case "attribute" -> {var v=CardFilterPanel.attributes().get(index);a.mdpro$attribute(a.mdpro$attribute()==v?null:v);}
                    case "race" -> {var v=CardFilterPanel.races().get(index);a.mdpro$race(a.mdpro$race()==v?null:v);}
                    case "level" -> a.mdpro$level(a.mdpro$level()==index+1?0:index+1);
                    case "detail" -> {
                        if(a.mdpro$typeFilter()==CardType.SPELL)a.mdpro$spell(next(Arrays.stream(SpellKind.values()).filter(v->v!=SpellKind.NONE).toList(),a.mdpro$spell()));
                        else if(a.mdpro$typeFilter()==CardType.TRAP)a.mdpro$trap(next(Arrays.stream(TrapKind.values()).filter(v->v!=TrapKind.NONE).toList(),a.mdpro$trap()));
                        else {var v=next(Arrays.stream(MonsterCategory.values()).filter(c->c!=MonsterCategory.LINK).toList(),a.mdpro$category());a.mdpro$setType(CardType.MONSTER);a.mdpro$category(v);}
                    }
                }
                a.mdpro$scroll(0);a.mdpro$refresh();
            }
            public String detail() {
                String key=a.mdpro$typeFilter()==CardType.SPELL?(a.mdpro$spell()==null?null:a.mdpro$spell().translationKey()):
                        a.mdpro$typeFilter()==CardType.TRAP?(a.mdpro$trap()==null?null:a.mdpro$trap().translationKey()):a.mdpro$category()==null?null:a.mdpro$category().translationKey();
                return "分类: "+(key==null?"全部":net.minecraft.network.chat.Component.translatable(key).getString())+" ›";
            }
            public void reset() {a.mdpro$setType(null);a.mdpro$attribute(null);a.mdpro$race(null);a.mdpro$level(0);a.mdpro$rarity(CardRarity.COMMON);
                if(binder())BinderClient.toggleRarityFilter(null);a.mdpro$scroll(0);a.mdpro$refresh();}
        };
    }
    private static <T> T next(List<T> values,T value) {int i=value==null?-1:values.indexOf(value);return i+1<values.size()?values.get(i+1):null;}
    public static void render(CardCatalogScreen s,GuiGraphics g,Font font,int mx,int my,float tick) {
        var a=(CatalogAccess)(Object)s;var l=layout(s);boolean b=binder();var cells=b?cells(s):List.<Integer>of();
        int n=b?cells.size():a.mdpro$results().size();a.mdpro$scroll(Math.max(0,Math.min(l.maxScroll(n),a.mdpro$scroll())));
        int[] p=l.panel();DeckEditorLayout.panel(g,p[0],p[1],p[2],p[3]);
        DeckEditorLayout.button(g,font,l.tab(0),"图鉴",!b);DeckEditorLayout.button(g,font,l.tab(1),"卡册",b);
        String count=b?"共 "+n+" 种卡片":"共 "+n+" 张卡牌";
        g.drawString(font,count,s.width-font.width(count)-16,15,0xFFB7C9DB,false);
        placeSearch(s);if(a.mdpro$search()!=null)a.mdpro$search().render(g,mx,my,tick);
        DeckEditorLayout.button(g,font,l.filter(b),"筛选",true);DeckEditorLayout.button(g,font,l.clear(b),"清空",true);
        if(b) {DeckEditorLayout.button(g,font,l.tidy(),"排序",true);DeckEditorLayout.button(g,font,l.collect(),"收取",true);}
        int hover=l.hit(mx,my),offset=a.mdpro$scroll()*l.cols();ItemStack hovered=ItemStack.EMPTY;CardDefinition hoveredCard=null;
        for(int i=0;i<l.rows()*l.cols()&&offset+i<n;i++) {
            int[] r=l.cell(i);g.fill(r[0],r[1],r[0]+r[2],r[1]+r[3],0xFF101A27);
            ItemStack stack;
            if(b)stack=BinderClient.stackAt(cells.get(offset+i));
            else {var d=a.mdpro$results().get(offset+i);var item=com.kiki.yugioh.registry.ModItems.cardItem(d.getId());
                stack=item==null?ItemStack.EMPTY:CardItem.withRarity(new ItemStack(item),a.mdpro$rarity());}
            if(!stack.isEmpty())DeckEditorLayout.drawCardArt(g,font,r[0],r[1],r[2],r[3],stack);
            if(b&&stack.getCount()>1)g.renderItemDecorations(font,stack,r[0]+r[2]-16,r[1]+r[3]-16);
            if(i==hover) {DeckEditorLayout.renderOutline(g,r[0],r[1],r[2],r[3],0xFFFFD477);hovered=stack;hoveredCard=b?null:a.mdpro$results().get(offset+i);}
        }
        int[] track=l.track(),thumb=l.thumb(n,a.mdpro$scroll());
        g.fill(track[0],track[1],track[0]+track[2],track[1]+track[3],0xFF1A2939);
        g.fill(thumb[0],thumb[1],thumb[0]+thumb[2],thumb[1]+thumb[3],0xFF7A94AD);
        String note=b&&!BinderClient.message().isEmpty()?BinderClient.message():"滚动位置 "+(a.mdpro$scroll()+1)+" / "+(l.maxScroll(n)+1)+" · 滚轮或 Shift 翻页";
        g.drawString(font,font.plainSubstrByWidth(note,s.width-32),14,s.height-22,0xFF9EB1C3,false);
        if(ui(s).filters) {
            g.pose().pushPose();g.pose().translate(0,0,500);g.fill(0,0,s.width,s.height,0xA0000000);
            int[] r=DeckEditorControls.popup(s.width,s.height);DeckEditorLayout.panel(g,r[0],r[1],r[2],r[3]);
            g.drawString(font,"详细筛选",r[0]+8,r[1]+9,0xFFF1D391,false);
            DeckEditorLayout.button(g,font,DeckEditorControls.close(s.width,s.height),"完成",true);
            CardFilterPanel.draw(g,font,r,model(s),mx,my);g.pose().popPose();return;
        }
        if(hoveredCard!=null)g.renderComponentTooltip(font,a.mdpro$tooltip(hoveredCard),mx,my);
        else if(!hovered.isEmpty())g.renderTooltip(font,hovered,mx,my);
    }
    private static void request(BinderNetwork.Op op,int cell,ItemStack stack) {
        if(Minecraft.getInstance().getConnection()!=null)BinderClient.request(op,cell,stack);
    }
    public static boolean click(CardCatalogScreen s,Font font,double x,double y,int button) {
        var a=(CatalogAccess)(Object)s;var l=layout(s);var state=ui(s);boolean b=binder();
        if(state.filters) {
            if(button==0) {if(DeckEditorLayout.inside(DeckEditorControls.close(s.width,s.height),x,y)||!DeckEditorLayout.inside(DeckEditorControls.popup(s.width,s.height),x,y))state.filters=false;
                else CardFilterPanel.click(font,DeckEditorControls.popup(s.width,s.height),model(s),x,y);}
            return true;
        }
        if(DeckEditorLayout.inside(l.search(b),x,y)) {var box=a.mdpro$search();box.mouseClicked(x,y,button);s.setFocused(box);return true;}
        if(a.mdpro$search()!=null)a.mdpro$search().setFocused(false);s.setFocused(null);
        if(button!=0)return true;
        for(int i=0;i<2;i++)if(DeckEditorLayout.inside(l.tab(i),x,y)) {
            BinderClient.setTab(i);a.mdpro$scroll(0);
            if(i==1&&Minecraft.getInstance().getConnection()!=null) {
                if(BinderClient.screen().equals(BinderNetwork.NONE))BinderClient.open();else request(BinderNetwork.Op.OPEN,0,ItemStack.EMPTY);
            }
            a.mdpro$refresh();placeSearch(s);return true;
        }
        if(DeckEditorLayout.inside(l.filter(b),x,y)) {state.filters=true;return true;}
        if(DeckEditorLayout.inside(l.clear(b),x,y)) {model(s).reset();a.mdpro$search().setValue("");return true;}
        if(b&&DeckEditorLayout.inside(l.tidy(),x,y)) {request(BinderNetwork.Op.TIDY,0,ItemStack.EMPTY);return true;}
        if(b&&DeckEditorLayout.inside(l.collect(),x,y)) {request(BinderNetwork.Op.COLLECT,0,ItemStack.EMPTY);return true;}
        if(DeckEditorLayout.inside(l.track(),x,y)) {state.drag=true;drag(s,y);return true;}
        int index=l.hit(x,y);if(index<0)return true;index+=a.mdpro$scroll()*l.cols();
        if(b) {var list=cells(s);if(index<list.size())request(BinderNetwork.Op.WITHDRAW,list.get(index),BinderClient.stackAt(list.get(index)));}
        else if(index<a.mdpro$results().size())a.mdpro$take(a.mdpro$results().get(index));
        return true;
    }
    public static boolean wheel(CardCatalogScreen s,double delta) {
        if(ui(s).filters)return true;
        var a=(CatalogAccess)(Object)s;var l=layout(s);int step=Screen.hasShiftDown()?l.rows():1;
        a.mdpro$scroll(Math.max(0,Math.min(l.maxScroll(count(s)),a.mdpro$scroll()-(int)Math.signum(delta)*step)));return true;
    }
    public static boolean key(CardCatalogScreen s,int key) {
        var a=(CatalogAccess)(Object)s;var state=ui(s);
        if(state.filters) {if(key==256)state.filters=false;return true;}
        boolean editing=a.mdpro$search()!=null&&a.mdpro$search().isFocused();int rows=layout(s).rows(),max=layout(s).maxScroll(count(s));
        Integer target=switch(key) {case 266->a.mdpro$scroll()-rows;case 267->a.mdpro$scroll()+rows;case 268->editing?null:0;case 269->editing?null:max;default->null;};
        if(target==null)return false;a.mdpro$scroll(Math.max(0,Math.min(max,target)));return true;
    }
    public static boolean drag(CardCatalogScreen s,double y) {
        if(ui(s).filters)return true;if(!ui(s).drag)return false;
        var a=(CatalogAccess)(Object)s;var l=layout(s);int n=count(s),max=l.maxScroll(n);int[] t=l.track(),thumb=l.thumb(n,a.mdpro$scroll());
        a.mdpro$scroll(Math.max(0,Math.min(max,(int)Math.round((y-t[1]-thumb[3]/2.0)*max/Math.max(1,t[3]-thumb[3])))));return true;
    }
    public static boolean release(CardCatalogScreen s) {boolean result=ui(s).drag;ui(s).drag=false;return result||ui(s).filters;}
}
