package dev.codex.yugiohmdpro.client.ui;

import com.kiki.yugioh.network.CardView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import java.util.*;

/** Anchored card menu and centred dialogs, modelled on MDPro3's duel popups. */
public final class DuelWidgets {
    private DuelWidgets() {}

    /** One selectable line: an engine choice index plus how it should read. */
    public record Item(int choice,String badge,String label,String detail,boolean enabled,int tint) {}

    /**
     * Menu anchored next to the card the player clicked. MDPro3 opens this list on the card itself;
     * a card with a single available action is executed straight away by the screen, so the menu
     * only ever carries two or more entries.
     */
    public static final class PopupMenu {
        private final String title;
        private final List<Item> items;
        private final CardView preview;
        private int x,y,w,h,rowH=14,headH=16,previewH;

        public PopupMenu(String title,List<Item> items,CardView preview) { this.title=title; this.items=items; this.preview=preview; }
        public List<Item> items() { return items; }
        public int x() { return x; }
        public int y() { return y; }
        public int w() { return w; }
        public int h() { return h; }

        /** Places the menu beside {@code anchor}, flipping sides and clamping to the UI bounds. */
        public void layout(Font font,DuelLayout.Rect anchor,int boundX,int boundY,int boundW,int boundH) {
            previewH=preview!=null && preview.present()?46:0;
            boolean twoLine=items.stream().anyMatch(item->!item.detail.isEmpty());
            rowH=twoLine?22:14;
            int textW=font.width(title)+16;
            for(Item item:items) {
                textW=Math.max(textW,font.width(item.label)+22);
                if(!item.detail.isEmpty()) textW=Math.max(textW,Math.min(190,font.width(item.detail)+22));
            }
            w=Math.max(88,Math.min(200,textW+6));
            h=headH+previewH+items.size()*rowH+4;
            x=anchor.x()+anchor.w()+2;
            if(x+w>boundX+boundW) x=anchor.x()-w-2;
            x=Math.max(boundX+1,Math.min(x,boundX+boundW-w-1));
            y=anchor.y();
            if(y+h>boundY+boundH) y=boundY+boundH-h;
            y=Math.max(boundY+1,y);
        }
        public boolean contains(double mx,double my) { return DuelUi.inside(mx,my,x,y,w,h); }

        /** Top edge of one row, for hit testing and for the development fixture. */
        public int rowTop(int index) { return y+headH+previewH+index*rowH; }
        public int rowHeight() { return rowH; }
        public int choiceOf(int index) { return items.get(index).choice(); }

        public void render(GuiGraphics g,Font font,DuelUi.CardPainter painter,double mx,double my) {
            DuelUi.plate(g,x,y,w,h,0xF4132036,DuelUi.ACCENT);
            g.fill(x+1,y+1,x+w-1,y+headH,DuelUi.PANEL_HEAD);
            DuelUi.label(g,font,x+5,y+4,w-10,title,DuelUi.ACCENT);
            int cy=y+headH;
            if(previewH>0) {
                int cw=24,ch=34;
                painter.draw(g,x+5,cy+2,cw,ch,preview);
                DuelUi.outline(g,x+5,cy+2,cw,ch,DuelUi.PANEL_EDGE);
                DuelUi.label(g,font,x+cw+11,cy+4,w-(cw+16),ChoiceModel.describeCard(preview),DuelUi.TEXT);
                if(preview!=null && preview.isKnown() && preview.definition()!=null) {
                    String stats=preview.definition().isMonster()?preview.atk()+"/"+preview.def()
                            :preview.definition().isSpell()?"魔法":"陷阱";
                    DuelUi.label(g,font,x+cw+11,cy+15,w-(cw+16),stats,DuelUi.DIM);
                }
                cy+=previewH;
            }
            for(int i=0;i<items.size();i++) {
                Item item=items.get(i);
                int ry=cy+i*rowH;
                boolean hover=item.enabled() && DuelUi.inside(mx,my,x+2,ry,w-4,rowH);
                DuelUi.row(g,x+2,ry,w-4,rowH,hover,false);
                DuelUi.badge(g,font,x+5,ry+(rowH-11)/2,item.badge(),item.enabled()?item.tint():DuelUi.BTN_DISABLED,item.enabled()?0xFF101820:0xFF6C7A8C);
                DuelUi.label(g,font,x+20,ry+(rowH==22?3:(rowH-8)/2+1),w-24,item.label(),item.enabled()?DuelUi.TEXT:0xFF6C7A8C);
                if(rowH==22 && !item.detail().isEmpty()) DuelUi.label(g,font,x+20,ry+12,w-24,item.detail(),DuelUi.DIM);
            }
        }
        /** Choice index under the pointer, -1 when the pointer is on no selectable row. */
        public int choiceAt(double mx,double my) {
            int cy=y+headH+previewH;
            if(mx<x+2 || mx>=x+w-2) return -1;
            for(int i=0;i<items.size();i++) {
                int ry=cy+i*rowH;
                if(my>=ry && my<ry+rowH) return items.get(i).enabled()?items.get(i).choice():-1;
            }
            return -1;
        }
    }

    /** Result of clicking inside a dialog: either an engine choice or a button action. */
    public record Hit(Integer choice,Runnable action) {}

    /** A laid-out dialog button. */
    public record Button(int x,int y,int w,int h,String label,boolean enabled,Runnable action,boolean primary) {
        public boolean contains(double mx,double my) { return DuelUi.inside(mx,my,x,y,w,h); }
    }

    private record ButtonSpec(String label,boolean enabled,Runnable action,boolean accent) {}

    /**
     * Centred dialog carrying either a list of engine options or a small set of buttons
     * (yes/no, position, rock-paper-scissors).
     */
    public static final class Dialog {
        private final String title,body;
        private final List<Item> options=new ArrayList<>();
        private final List<ButtonSpec> specs=new ArrayList<>();
        private final List<Button> buttons=new ArrayList<>();
        private int x,y,w,h,optionTop,optionRow=16,buttonTop;
        private boolean listMode,grid,showPreview;
        private int offset,capacity=8,centerX,centerY,maxW,maxH;
        private Font layoutFont;
        private java.util.function.IntPredicate selected=i->false;
        public Dialog selected(java.util.function.IntPredicate predicate) {selected=predicate;return this;}
        private int visible() {return Math.min(capacity,options.size()-offset);}
        public void scroll(int direction) {
            int next=Math.max(0,Math.min((options.size()-1)/capacity,offset/capacity+direction))*capacity;
            if(next!=offset) {offset=next;layout(layoutFont,centerX,centerY,maxW,maxH);}
        }

        public Dialog(String title,String body) { this.title=title; this.body=body==null?"":body; }
        public Dialog option(int choice,String badge,String label,boolean enabled,int tint) {
            options.add(new Item(choice,badge,label,"",enabled,tint)); return this;
        }
        public Dialog option(int choice,String badge,String label,String detail,boolean enabled,int tint) {
            options.add(new Item(choice,badge,label,detail,enabled,tint)); return this;
        }
        public Dialog button(String label,boolean enabled,boolean accent,Runnable action) {
            specs.add(new ButtonSpec(label,enabled,action,accent)); return this;
        }
        /** Rows instead of a button grid; used when entries carry effect text. */
        public Dialog rows() { listMode=true; return this; }
        /** Compact grid instead of rows; used for race/attribute declaration. */
        public Dialog grid() { grid=true; return this; }
        public Dialog preview() { showPreview=true; return this; }
        public int x() { return x; }
        public int y() { return y; }
        public int w() { return w; }
        public int h() { return h; }
        public int optionCount() { return visible(); }
        public int buttonCount() { return specs.size(); }
        public int optionRowAt(int index) { return optionTop+(listMode?index*optionRow:(index/Math.min(4,visible()))*16); }
        public int optionRowHeight() { return optionRow; }
        public int optionChoice(int index) { return options.get(offset+index).choice(); }
        public int[] optionPoint(int index) {
            if(listMode)return new int[]{x+10,optionRowAt(index)+optionRow/2,optionChoice(index)};
            int cols=Math.min(4,Math.max(1,visible())),cw=(w-12-(cols-1)*4)/cols;
            return new int[]{x+6+(index%cols)*(cw+4)+cw/2,optionRowAt(index)+8,optionChoice(index)};
        }
        public List<Button> buttons() { return buttons; }
        public String title() { return title; }

        public void layout(Font font,int centerX,int centerY,int maxW,int maxH) {
            this.layoutFont=font;this.centerX=centerX;this.centerY=centerY;this.maxW=maxW;this.maxH=maxH;
            capacity=listMode?8:24;
            boolean twoLine=options.stream().anyMatch(item->!item.detail().isEmpty());
            optionRow=twoLine?22:16;
            int textW=font.width(title)+26;
            for(Item item:options) {
                textW=Math.max(textW,font.width(item.label())+46);
                if(!item.detail().isEmpty()) textW=Math.max(textW,Math.min(210,font.width(item.detail())+30));
            }
            for(String line:DuelUi.wrap(font,body,200,4)) textW=Math.max(textW,font.width(line)+26);
            for(ButtonSpec spec:specs) textW=Math.max(textW,spec.label().length()*12+3*(specs.size()-1)+26);
            w=Math.max(132,Math.min(maxW,textW));
            List<String> bodyLines=DuelUi.wrap(font,body,w-16,4);
            int bodyH=bodyLines.isEmpty()?0:bodyLines.size()*10+4;
            int contentH=0;
            if(showPreview) contentH+=50;
            // Every option occupies space whether it is drawn as rows or as the default button grid;
            // leaving one of the two out made the dialog shorter than its own content and the render
            // loop broke before drawing a single option.
            if(listMode)contentH+=visible()*optionRow;
            else contentH+=((visible()+3)/4)*16;
            var shownSpecs=new ArrayList<>(specs);
            if(options.size()>capacity) {
                shownSpecs.add(new ButtonSpec("上一页",offset>0,()->scroll(-1),false));
                shownSpecs.add(new ButtonSpec("下一页",offset+capacity<options.size(),()->scroll(1),false));
            }
            buttonTop=16+bodyH+contentH+4;
            // Only reserve a button row when the dialog actually has buttons.
            h=buttonTop+(shownSpecs.isEmpty()?4:22);
            if(h>maxH) h=maxH;
            x=Math.max(2,centerX-w/2);
            y=Math.max(2,centerY-h/2);
            optionTop=y+16+bodyH+(showPreview?50:0);
            buttons.clear();
            int count=Math.max(1,shownSpecs.size());
            int bw=Math.min(96,Math.max(48,(w-12-(count-1)*4)/count));
            int total=count*bw+(count-1)*4;
            int bx=x+(w-total)/2;
            for(ButtonSpec spec:shownSpecs) {
                buttons.add(new Button(bx,y+buttonTop,bw,16,spec.label(),spec.enabled(),spec.action(),spec.accent()));
                bx+=bw+4;
            }
        }

        public void render(GuiGraphics g,Font font,DuelUi.CardPainter painter,CardView preview,double mx,double my) {
            g.fill(x-2,y-2,x+w+2,y+h+2,0x70000000);
            DuelUi.plate(g,x,y,w,h,0xF5142338,DuelUi.ACCENT);
            g.fill(x+1,y+1,x+w-1,y+15,DuelUi.PANEL_HEAD);
            DuelUi.label(g,font,x+6,y+4,w-12,title+(options.size()>capacity?" "+(offset/capacity+1)+"/"+((options.size()+capacity-1)/capacity):""),DuelUi.ACCENT);
            int cy=y+16;
            for(String line:DuelUi.wrap(font,body,w-16,4)) { DuelUi.label(g,font,x+6,cy,w-12,line,DuelUi.DIM); cy+=10; }
            if(!body.isEmpty()) cy+=4;
            if(showPreview && preview!=null && preview.present()) {
                int cw=32,ch=46;
                painter.draw(g,x+6,cy,cw,ch,preview);
                DuelUi.outline(g,x+6,cy,cw,ch,DuelUi.PANEL_EDGE);
                DuelUi.label(g,font,x+cw+12,cy+2,w-(cw+18),ChoiceModel.describeCard(preview),DuelUi.TEXT);
                if(preview.isKnown() && preview.definition()!=null) {
                    String stats=preview.definition().isMonster()?preview.atk()+"/"+preview.def()
                            :preview.definition().isSpell()?"魔法":"陷阱";
                    DuelUi.label(g,font,x+cw+12,cy+13,w-(cw+18),stats,DuelUi.DIM);
                }
                cy+=50;
            }
            if(listMode) {
                for(int i=0;i<visible();i++) {
                    Item item=options.get(offset+i);
                    int ry=cy+i*optionRow;
                    if(ry+optionRow>y+buttonTop) break;
                    boolean hover=item.enabled() && DuelUi.inside(mx,my,x+4,ry,w-8,optionRow);
                    DuelUi.row(g,x+4,ry,w-8,optionRow,hover,selected.test(item.choice()));
                    DuelUi.badge(g,font,x+7,ry+(optionRow-11)/2,item.badge(),item.enabled()?item.tint():DuelUi.BTN_DISABLED,item.enabled()?0xFF101820:0xFF6C7A8C);
                    DuelUi.label(g,font,x+22,ry+(optionRow==22?3:(optionRow-8)/2+1),w-26,item.label(),item.enabled()?DuelUi.TEXT:0xFF6C7A8C);
                    if(optionRow==22 && !item.detail().isEmpty()) DuelUi.label(g,font,x+22,ry+12,w-26,item.detail(),DuelUi.DIM);
                }
            } else if(!options.isEmpty()) {
                int cols=Math.min(4,Math.max(1,visible()));
                int cw=(w-12-(cols-1)*4)/cols;
                for(int i=0;i<visible();i++) {
                    Item item=options.get(offset+i);
                    int rx=x+6+(i%cols)*(cw+4),ry=cy+(i/cols)*16;
                    if(ry+16>y+buttonTop) break;
                    boolean hover=item.enabled() && DuelUi.inside(mx,my,rx,ry,cw,16);
                    if(selected.test(item.choice()))DuelUi.accentButton(g,font,rx,ry,cw,16,"+ "+item.label(),item.enabled(),hover);
                    else DuelUi.button(g,font,rx,ry,cw,16,item.label(),item.enabled(),hover);
                }
            }
            for(Button button:buttons) {
                boolean hover=DuelUi.inside(mx,my,button.x(),button.y(),button.w(),button.h());
                if(button.primary()) DuelUi.accentButton(g,font,button.x(),button.y(),button.w(),button.h(),button.label(),button.enabled(),hover);
                else DuelUi.button(g,font,button.x(),button.y(),button.w(),button.h(),button.label(),button.enabled(),hover);
            }
        }

        /**
         * Choice index of the option row under the pointer, or -1. Used to preview a row's card in the
         * detail panel while the player is still deciding, which is what makes chain lists readable.
         */
        public int hoverIndex(double mx,double my) {
            if(listMode) {
                for(int i=0;i<visible();i++) {
                    Item item=options.get(offset+i);
                    int ry=optionTop+i*optionRow;
                    if(item.enabled() && DuelUi.inside(mx,my,x+4,ry,w-8,optionRow)) return item.choice();
                }
            } else if(!options.isEmpty()) {
                int cols=Math.min(4,Math.max(1,visible()));
                int cw=(w-12-(cols-1)*4)/cols;
                for(int i=0;i<visible();i++) {
                    Item item=options.get(offset+i);
                    int rx=x+6+(i%cols)*(cw+4),ry=optionTop+(i/cols)*16;
                    if(item.enabled() && DuelUi.inside(mx,my,rx,ry,cw,16)) return item.choice();
                }
            }
            return -1;
        }

        public Hit hit(double mx,double my) {
            for(Button button:buttons)
                if(button.enabled() && button.contains(mx,my)) return new Hit(null,button.action());
            if(listMode) {
                for(int i=0;i<visible();i++) {
                    Item item=options.get(offset+i);
                    int ry=optionTop+i*optionRow;
                    if(item.enabled() && DuelUi.inside(mx,my,x+4,ry,w-8,optionRow)) return new Hit(item.choice(),null);
                }
            } else if(!options.isEmpty()) {
                int cols=Math.min(4,Math.max(1,visible()));
                int cw=(w-12-(cols-1)*4)/cols;
                for(int i=0;i<visible();i++) {
                    Item item=options.get(offset+i);
                    int rx=x+6+(i%cols)*(cw+4),ry=optionTop+(i/cols)*16;
                    if(item.enabled() && DuelUi.inside(mx,my,rx,ry,cw,16)) return new Hit(item.choice(),null);
                }
            }
            return null;
        }
        public boolean contains(double mx,double my) { return DuelUi.inside(mx,my,x,y,w,h); }
    }
}
