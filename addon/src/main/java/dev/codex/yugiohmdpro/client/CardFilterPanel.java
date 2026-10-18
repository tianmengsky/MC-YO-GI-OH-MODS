package dev.codex.yugiohmdpro.client;

import com.kiki.yugioh.card.*;
import dev.codex.yugiohfix.client.BinderView;
import java.util.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** One compact, bounded filter grid shared by the deck, catalog and binder. */
public final class CardFilterPanel {
    public interface Model {
        int selected(String group);
        void choose(String group,int index);
        void reset();
        default String detail() {return "";}
    }
    public record Choice(BinderView.Chip chip,String group,int index) {}
    public static List<MonsterType> races() {return Arrays.stream(MonsterType.values()).filter(t->t!=MonsterType.NONE).toList();}
    public static List<Attribute> attributes() {return Arrays.stream(Attribute.values()).filter(a->a!=Attribute.NONE).toList();}
    public static List<Choice> choices(Font font,int[] r,Model model) {
        var out=new ArrayList<Choice>();int x=r[0]+6,y=r[1]+30,w=r[2]-12,left=w*3/5-2,right=w-left-4;
        group(out,font,model,"rarity","罕贵度",x,y,left,3,Arrays.stream(CardRarity.values()).map(v->text(v.translationKey())).toList());
        group(out,font,model,"type","卡片类型",x+left+4,y,right,3,Arrays.stream(CardType.values()).map(v->text(v.translationKey())).toList());
        if(!model.detail().isEmpty())out.add(new Choice(new BinderView.Chip(x+left+4,y+26,right,model.detail(),false),"detail",0));
        y+=43;
        group(out,font,model,"attribute","属性",x,y,w,7,attributes().stream().map(v->text(v.translationKey())).toList());y+=30;
        group(out,font,model,"level","等级 / 阶级",x,y,w,12,java.util.stream.IntStream.rangeClosed(1,12).mapToObj(Integer::toString).toList());y+=30;
        group(out,font,model,"race","种族",x,y,w,5,races().stream().map(v->text(v.translationKey())).toList());
        return List.copyOf(out);
    }
    private static String text(String key) {return net.minecraft.network.chat.Component.translatable(key).getString();}
    private static void group(List<Choice> out,Font font,Model model,String key,String label,int x,int y,int w,int cols,List<String> values) {
        out.add(new Choice(new BinderView.Chip(x,y,w,label,true),null,-1));int cell=(w-(cols-1)*2)/cols;
        for(int i=0;i<values.size();i++)out.add(new Choice(new BinderView.Chip(x+i%cols*(cell+2),y+13+i/cols*13,cell,
                font.plainSubstrByWidth(values.get(i),cell-4),model.selected(key)==i),key,i));
    }
    public static int[] reset(int[] r) {return new int[]{r[0]+r[2]-80,r[1]+6,34,14};}
    public static void draw(GuiGraphics g,Font font,int[] r,Model model,int mx,int my) {
        DeckEditorLayout.button(g,font,reset(r),"重置",true);
        for(var c:choices(font,r,model))BinderView.drawChip(g,font,c.chip(),mx,my);
    }
    public static boolean click(Font font,int[] r,Model model,double x,double y) {
        if(DeckEditorLayout.inside(reset(r),x,y)) {model.reset();return true;}
        for(var c:choices(font,r,model))if(c.group()!=null&&c.chip().contains(x,y)) {model.choose(c.group(),c.index());return true;}
        return false;
    }
}
