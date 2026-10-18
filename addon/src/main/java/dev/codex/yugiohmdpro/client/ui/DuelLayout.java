package dev.codex.yugiohmdpro.client.ui;

import com.kiki.yugioh.network.CardView;

/**
 * Board geometry and hit testing for the MDPro duel screen.
 *
 * <p>Everything here is in the screen's virtual board space (the space the original
 * {@code DuelScreen} draws into before its own {@code scale} is applied), so callers must divide
 * raw mouse coordinates by the screen scale before asking.</p>
 *
 * <p>Row arithmetic is the original board's: {@code top+14} opponent spell row, {@code top+61}
 * opponent monster row, {@code top+126} own monster row, {@code top+173} own spell row, seven
 * columns of 33px starting at {@code left}.</p>
 */
public final class DuelLayout {
    public static final int BOARD_W=228;
    public static final int COL_PITCH=33;
    public static final int CELL_W=30;
    public static final int CELL_H=44;
    public static final int ROW_PITCH=47;
    public static final int CENTER_STRIP=18;
    public static final int HAND_W=26;
    public static final int HAND_PITCH=28;
    public static final int BOARD_H=14+ROW_PITCH*2+CENTER_STRIP+ROW_PITCH*2+14;
    public static final int HAND_H=Math.round(HAND_W*86.0f/59.0f);
    /**
     * Width reserved to the right of the board for the phase bar, prompt and log panel. Wide enough
     * that a log line like "回合 1：Duel Bot" fits without being cut in half.
     */
    public static final int SIDE_W=112;
    /** MR3 keeps two separate pendulum zones, drawn outside the 228px board on both sides. */
    public static final int PENDULUM_W=30;
    public static final int PENDULUM_OUT=36;
    /** Gap between the board and the interaction column; it also clears the right pendulum zone. */
    public static final int SIDE_GAP=PENDULUM_OUT+8;

    public static final int DECK=1,HAND=2,MZONE=4,SZONE=8,GRAVE=16,REMOVED=32,EXTRA=64;
    /** Both monster and spell/trap rows; never appears in a core request but useful for filtering. */
    public static final int ON_FIELD=MZONE|SZONE;

    private final int left,top,boardH;

    public DuelLayout(int left,int top,int boardH) { this.left=left; this.top=top; this.boardH=boardH; }

    public int left() { return left; }
    public int top() { return top; }
    public int boardH() { return boardH; }
    public int right() { return left+BOARD_W; }
    public int bottom() { return top+boardH; }
    public int sideX() { return left+BOARD_W+SIDE_GAP; }
    public int handTop() { return top+boardH+8; }
    public int barTop() { return handTop()+HAND_H+6; }

    /** x of a monster/spell column for the given owner, mirrored for the opponent. */
    public int columnX(int column) { return left+column*COL_PITCH; }
    public int monsterRow(boolean own) { return top+(own?126:61); }
    public int spellRow(boolean own) { return top+(own?173:14); }

    public int handX(int index,int count) {
        int total=count*HAND_PITCH-2;
        return left+114-total/2+index*HAND_PITCH;
    }
    public Rect handSlot(int index,int count) { return new Rect(handX(index,count),handTop()-4,HAND_W,HAND_H+4); }

    public int pendulumX(boolean own,int sequence) {
        boolean leftSide=(sequence==6)==own;
        return left+(leftSide?-PENDULUM_OUT:BOARD_W+PENDULUM_OUT-PENDULUM_W);
    }
    public int pendulumY(boolean own) { return monsterRow(own); }
    public Rect pendulumSlot(boolean own,int sequence) { return new Rect(pendulumX(own,sequence),pendulumY(own),PENDULUM_W,CELL_H); }

    public Rect removedSlot(boolean own) {
        int x=left+(own?BOARD_W+PENDULUM_OUT-PENDULUM_W:-PENDULUM_OUT);
        return new Rect(x,pendulumY(own)+48,PENDULUM_W,16);
    }

    /**
     * Board rectangle for one card slot, or null when that location is only reachable through the
     * card list (deck, extra deck and graveyard piles are single zones, so their sequence is
     * ignored).
     */
    public Rect zone(int viewerSide,int player,int location,int sequence) {
        boolean own=player==viewerSide;
        if(location==MZONE) {
            if(sequence<0 || sequence>4) return null;
            int column=own?sequence+1:5-sequence;
            return new Rect(columnX(column),monsterRow(own),CELL_W,CELL_H);
        }
        if(location==SZONE) {
            if(sequence>=0 && sequence<5) {
                int column=own?sequence+1:5-sequence;
                return new Rect(columnX(column),spellRow(own),CELL_W,CELL_H);
            }
            if(sequence==5) return new Rect(columnX(own?0:6),monsterRow(own),CELL_W,CELL_H);
            if(sequence==6 || sequence==7) return pendulumSlot(own,sequence);
            return null;
        }
        if(location==GRAVE) return new Rect(columnX(own?6:0),monsterRow(own),CELL_W,CELL_H);
        if(location==EXTRA) return new Rect(columnX(own?0:6),spellRow(own),CELL_W,CELL_H);
        if(location==DECK)  return new Rect(columnX(own?6:0),spellRow(own),CELL_W,CELL_H);
        if(location==REMOVED) return null;
        if(location==HAND) return own?handSlot(sequence,handCount):null;
        return null;
    }
    /** Set by the screen each frame so hand hit testing knows the current hand size. */
    private int handCount;
    public void handCount(int value) { handCount=value; }
    public int handCount() { return handCount; }

    public record Rect(int x,int y,int w,int h) {
        public boolean contains(double mx,double my) { return mx>=x && mx<x+w && my>=y && my<y+h; }
        public int cx() { return x+w/2; }
        public int cy() { return y+h/2; }
        public Rect inset(int n) { return new Rect(x+n,y+n,w-2*n,h-2*n); }
    }

    /** A card slot the player can point at, resolved from board state. */
    public record Hit(int player,int location,int sequence,Rect rect,CardView card,boolean reveal) {}

    public static String locationName(int location) {
        return switch(location) {
            case DECK->"卡组"; case HAND->"手牌"; case MZONE->"怪兽区"; case SZONE->"魔陷区";
            case GRAVE->"墓地"; case REMOVED->"除外区"; case EXTRA->"额外卡组"; default->"区域 "+location;
        };
    }
    public static String positionName(int position) {
        return switch(position) {
            case 1->"表侧攻击"; case 2->"里侧攻击"; case 4->"表侧守备"; case 8->"里侧守备"; default->"未知";
        };
    }
    public static String phaseName(int phase) {
        return switch(phase) {
            case 1->"抽卡阶段"; case 2->"准备阶段"; case 4->"主要阶段 1"; case 8,16,32,64,128->"战斗阶段";
            case 256->"主要阶段 2"; case 512->"结束阶段"; default->"阶段 "+phase;
        };
    }
    /** Short label for the phase bar. */
    public static String phaseShort(int phase) {
        return switch(phase) {
            case 1->"抽卡"; case 2->"准备"; case 4->"主1"; case 8,16,32,64,128->"战斗";
            case 256->"主2"; case 512->"结束"; default->"阶段";
        };
    }
}
