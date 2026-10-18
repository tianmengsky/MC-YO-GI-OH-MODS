package dev.codex.yugiohmdpro.client;

/** Full-window catalog/binder grid, with a two-row toolbar and shared drawing/hit geometry. */
public record CatalogLayout(int width,int height) {
    public static final int HEADER=52,FOOTER=18,GAP=4;
    public int[] panel() {return new int[]{6,6,width-12,height-12};}
    public int cols() {return Math.max(7,Math.min(28,(width-32)/34));}
    public int cellW() {return (width-32-(cols()-1)*GAP)/cols();}
    public int cellH() {return Math.round(cellW()*257f/176f);}
    public int pitchX() {return cellW()+GAP;}
    public int pitchY() {return cellH()+GAP;}
    public int rows() {return Math.max(1,(height-12-HEADER-FOOTER)/pitchY());}
    public int[] cell(int offset) {return new int[]{14+offset%cols()*pitchX(),6+HEADER+offset/cols()*pitchY(),cellW(),cellH()};}
    public int hit(double x,double y) {for(int i=0;i<rows()*cols();i++)if(DeckEditorLayout.inside(cell(i),x,y))return i;return -1;}
    public int maxScroll(int size) {return Math.max(0,(size+cols()-1)/cols()-rows());}
    public int[] tab(int index) {return new int[]{14+index*46,12,42,16};}
    public int[] search(boolean binder) {return new int[]{14,33,width-12-(binder?144:84),16};}
    public int[] filter(boolean binder) {int[] s=search(binder);return new int[]{s[0]+s[2]+4,34,32,14};}
    public int[] clear(boolean binder) {int[] b=filter(binder);return new int[]{b[0]+36,34,28,14};}
    public int[] tidy() {int[] b=clear(true);return new int[]{b[0]+32,34,26,14};}
    public int[] collect() {int[] b=tidy();return new int[]{b[0]+30,34,26,14};}
    public int[] track() {return new int[]{width-11,6+HEADER,4,rows()*pitchY()-GAP};}
    public int[] thumb(int size,int scroll) {
        int[] t=track();int max=maxScroll(size),total=(size+cols()-1)/cols();
        int h=Math.min(t[3],Math.max(12,t[3]*rows()/Math.max(1,total)));
        return new int[]{t[0],t[1]+(t[3]-h)*scroll/Math.max(1,max),t[2],h};
    }
}
