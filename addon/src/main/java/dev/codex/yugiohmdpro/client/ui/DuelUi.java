package dev.codex.yugiohmdpro.client.ui;

import com.kiki.yugioh.network.CardView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.*;

/**
 * Drawing primitives and small widgets for the MDPro duel screen.
 *
 * <p>The palette follows MDPro3's duel UI: a dark navy plate with a gold accent, a light blue
 * border, a green "you may pick this" highlight and a purple chain colour.</p>
 */
public final class DuelUi {
    private DuelUi() {}

    public static final int PANEL=0xF0121C2C;
    public static final int PANEL_HEAD=0xFF1B2C44;
    public static final int PANEL_EDGE=0xFF3E5A7A;
    public static final int TEXT=0xFFE8EEF7;
    public static final int DIM=0xFF93A4BA;
    public static final int ACCENT=0xFFE1A64C;
    public static final int GOOD=0xFF63D2A0;
    public static final int DANGER=0xFFE0644C;
    public static final int CHAIN=0xFFB06CE0;
    public static final int SELECT=0xFFFFD34D;
    public static final int CANDIDATE=0xFF57D6A0;
    public static final int BTN=0xFF233A55;
    public static final int BTN_HOVER=0xFF35618F;
    public static final int BTN_DISABLED=0xFF1A2432;
    public static final int BTN_EDGE=0xFF5B7BA6;
    public static final int ROW=0xFF1E3048;
    public static final int ROW_ALT=0xFF22344C;
    public static final int ROW_HOVER=0xFF2F4B6C;

    /** Draws one card face of the given size; supplied by the duel screen so art loading stays there. */
    public interface CardPainter { void draw(GuiGraphics g,int x,int y,int w,int h,CardView card); }

    /** A clickable region in board space, labelled so it can be addressed by name. */
    public record Control(int x,int y,int w,int h,String label,Runnable action,boolean enabled) {
        public boolean contains(double mx,double my) { return mx>=x && mx<x+w && my>=y && my<y+h; }
    }

    public static void panel(GuiGraphics g,int x,int y,int w,int h) {
        g.fill(x,y,x+w,y+h,PANEL);
        outline(g,x,y,w,h,PANEL_EDGE);
    }
    public static void plate(GuiGraphics g,int x,int y,int w,int h,int fill,int edge) {
        g.fill(x,y,x+w,y+h,fill);
        outline(g,x,y,w,h,edge);
    }
    public static void outline(GuiGraphics g,int x,int y,int w,int h,int color) {
        g.fill(x,y,x+w,y+1,color);
        g.fill(x,y+h-1,x+w,y+h,color);
        g.fill(x,y,x+1,y+h,color);
        g.fill(x+w-1,y,x+w,y+h,color);
    }
    /**
     * Border of a rectangle given by two corners, which is the same argument order as
     * {@code GuiGraphics.fill}. Use this whenever the call site already has the far corner, so the
     * two orders cannot be mixed up: passing a corner where a size is expected draws a border that
     * runs off the screen.
     */
    public static void box(GuiGraphics g,int x1,int y1,int x2,int y2,int color) {
        g.fill(x1,y1,x2,y1+1,color);
        g.fill(x1,y2-1,x2,y2,color);
        g.fill(x1,y1,x1+1,y2,color);
        g.fill(x2-1,y1,x2,y2,color);
    }
    public static void row(GuiGraphics g,int x,int y,int w,int h,boolean hover,boolean chosen) {
        g.fill(x,y,x+w,y+h,chosen?0xFF3B5E7E:hover?ROW_HOVER:ROW);
        outline(g,x,y,w,h,chosen?SELECT:PANEL_EDGE);
    }

    /**
     * Sectioned border used to advertise a plan-able slot: a thick outer frame plus an inner line.
     * The board is dark, so candidate frames must stay readable against the card art underneath.
     */
    public static void focus(GuiGraphics g,int x,int y,int w,int h,int color,boolean filled) {
        if(filled) g.fill(x,y,x+w,y+h,(color&0x00FFFFFF)|0x40000000);
        outline(g,x-1,y-1,w+2,h+2,color);
        outline(g,x,y,w,h,(color&0x00FFFFFF)|0x80000000);
    }

    public static void button(GuiGraphics g,net.minecraft.client.gui.Font font,int x,int y,int w,int h,String text,boolean enabled,boolean hover) {
        int fill=!enabled?BTN_DISABLED:hover?BTN_HOVER:BTN;
        g.fill(x,y,x+w,y+h,fill);
        outline(g,x,y,w,h,enabled?BTN_EDGE:0xFF2B3646);
        g.drawCenteredString(font,Component.literal(font.plainSubstrByWidth(text,Math.max(4,w-4))),x+w/2,y+(h-8)/2+1,enabled?TEXT:0xFF6C7A8C);
    }
    public static void accentButton(GuiGraphics g,net.minecraft.client.gui.Font font,int x,int y,int w,int h,String text,boolean enabled,boolean hover) {
        if(!enabled) { button(g,font,x,y,w,h,text,false,false); return; }
        g.fill(x,y,x+w,y+h,hover?0xFFE8BC66:ACCENT);
        outline(g,x,y,w,h,0xFF8A6A24);
        g.drawCenteredString(font,Component.literal(font.plainSubstrByWidth(text,w-4)),x+w/2,y+(h-8)/2+1,0xFF20180A);
    }
    public static void heading(GuiGraphics g,net.minecraft.client.gui.Font font,int x,int y,int w,String text) {
        g.fill(x,y,x+w,y+13,PANEL_HEAD);
        g.drawString(font,font.plainSubstrByWidth(text,w-8),x+4,y+3,ACCENT,false);
    }
    public static void label(GuiGraphics g,net.minecraft.client.gui.Font font,int x,int y,int w,String text,int color) {
        g.drawString(font,font.plainSubstrByWidth(text,w),x,y,color,false);
    }
    /**
     * Greedy wrap. Chinese text breaks per character, which is also its correct typographic break; a
     * line that would end in a short Latin word moves that word down whole, so a log line such as
     * "回合 1：Duel Bot" reads as two clean lines instead of "回合 1：Duel Bo" + "t".
     */
    public static List<String> wrap(net.minecraft.client.gui.Font font,String text,int width,int maxLines) {
        List<String> result=new ArrayList<>();
        if(text==null || text.isEmpty() || width<8 || maxLines<1) return result;
        StringBuilder line=new StringBuilder();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='\n') {
                result.add(line.toString()); line.setLength(0);
                if(result.size()>=maxLines) return result;
                continue;
            }
            if(font.width(line.toString()+c)>width && line.length()>0) {
                String current=line.toString();
                int space=current.lastIndexOf(' ');
                boolean wordTail=space>0 && current.length()-space<=9 && space>=current.length()/3;
                line.setLength(0);
                if(wordTail) {
                    result.add(current.substring(0,space));
                    line.append(current,space+1,current.length());
                } else {
                    result.add(current);
                }
                if(result.size()>=maxLines) return result;
            }
            line.append(c);
        }
        if(line.length()>0 && result.size()<maxLines) result.add(line.toString());
        return result;
    }
    /** Small square badge carrying a single character, used instead of a duel icon set. */
    public static void badge(GuiGraphics g,net.minecraft.client.gui.Font font,int x,int y,String text,int fill,int textColor) {
        g.fill(x,y,x+11,y+11,fill);
        outline(g,x,y,11,11,(fill&0x00FFFFFF)|0xFF000000);
        g.drawCenteredString(font,text,x+5,y+2,textColor);
    }
    public static void meter(GuiGraphics g,int x,int y,int w,int h,float ratio,int fill) {
        g.fill(x,y,x+w,y+h,0xFF0E1622);
        g.fill(x,y,x+Math.round(w*Math.max(0f,Math.min(1f,ratio))),y+h,fill);
        outline(g,x,y,w,h,PANEL_EDGE);
    }
    public static boolean inside(double mx,double my,int x,int y,int w,int h) { return mx>=x && mx<x+w && my>=y && my<y+h; }
    public static int mix(int color,float alpha) { return (color&0x00FFFFFF)|(Math.round(Math.max(0f,Math.min(1f,alpha))*255f)<<24); }
}
