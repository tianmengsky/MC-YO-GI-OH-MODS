package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.item.CardItem;
import com.kiki.yugioh.registry.ModItems;
import dev.codex.yugiohfix.binder.BinderNetwork;
import dev.codex.yugiohfix.binder.CardBinder;
import dev.codex.yugiohfix.client.BinderClient;
import dev.codex.yugiohfix.client.BinderView;
import dev.codex.yugiohmdpro.client.DeckBinderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance fixture for the deck-box binder column.
 *
 * <p>It measures the real {@code BinderView} helpers (through the layout mixin) against the reviewed
 * 30x44/32x46 geometry, checks that every drawn slot still answers its own hit test, that the
 * scrollbar keeps the same numbers, and that the binder search now answers passcodes, CIDs, Japanese
 * and English names on a seeded binder.</p>
 */
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon",value=Dist.CLIENT)
public final class BinderLayoutSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("yugioh.mdpro.visualSmoke");
    private static final int[][] WINDOWS={{640,360},{854,480},{960,540},{1280,720}};
    private static final String[] CARDS={"blue_eyes_white_dragon","mdpro_c00027551","dark_magician"};
    private static boolean checked;

    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        if(!ENABLED||checked)return;
        Minecraft minecraft=Minecraft.getInstance();
        if(minecraft.getWindow().getGuiScaledWidth()<320||minecraft.getWindow().getGuiScaledHeight()<200)return;
        checked=true;
        try {
            List<String> lines=new ArrayList<>();
            for(int[] window:WINDOWS)lines.add(pages(window[0],window[1]));
            lines.addAll(hitTests());
            lines.add(panel(window(854,480)));
            String search=search();
            lines.add(search);
            StringBuilder text=new StringBuilder("组卡界面卡册列：固定 3×3，自适应卡片尺寸\n");
            for(String line:lines)text.append("  ").append(line).append('\n');
            Files.writeString(result(),text.toString());
            LoggerFactory.getLogger("MDProBinderSmoke").info("Binder layout verified: {}",String.join("; ",lines));
        }catch(Throwable error) {
            try {Files.writeString(FMLPaths.GAMEDIR.get().resolve("../generated/visual-smoke-result.txt").normalize(),"FAIL: binder fixture: "+error);}catch(Exception ignored) {}
            LoggerFactory.getLogger("MDProBinderSmoke").error("Binder layout fixture failed",error);
            minecraft.stop();
        }
    }

    /** Cards per page now versus the reviewed geometry, for one window size. */
    private static String pages(int screenWidth,int screenHeight) {
        BinderView.Panels panels=DeckBinderLayout.panels(screenWidth);
        int cols=DeckBinderLayout.stripCols(panels.width());
        int rows=DeckBinderLayout.stripRows(screenHeight);
        int now=cols*rows;
        // Reviewed geometry: 30x44 cells on a 32/46 pitch, a 212-pixel strip and six columns.
        int oldWidth=Math.max(0,Math.min(212,Math.max(0,(screenWidth-286)/2-4)+4-8));
        int oldCols=oldWidth<=0?0:Math.max(0,Math.min(6,(oldWidth-8-4-1)/32));
        int oldRows=Math.max(1,Math.min(8,(screenHeight-64)/46));
        int before=oldCols*oldRows;
        // The rebuilt list deliberately shows three large cards per row instead of many small ones, so the
        // reviewed cards-per-page count is reported rather than used as a floor.
        require(cols==DeckBinderLayout.LIST_COLS,"the list must keep three cards per row: "+cols);
        require(now==9,"the list must show a usable page: "+now);
        require(DeckBinderLayout.stripHeight(screenHeight)<=screenHeight-8,"strip must fit the window");
        require(DeckBinderLayout.stripTop(screenHeight)>=4,"strip must stay on screen");
        return "窗口 "+screenWidth+"x"+screenHeight+" 列表网格="+panels.width()+"x"+DeckBinderLayout.stripHeight(screenHeight)
                +" 列x行="+cols+"x"+rows+" 每页="+now+"（修复前 "+before+"）";
    }

    /** Every drawn slot must answer its own centre through the real hit test. */
    private static List<String> hitTests() {
        List<String> lines=new ArrayList<>();
        for(int[] window:new int[][]{{Minecraft.getInstance().getWindow().getGuiScaledWidth(),Minecraft.getInstance().getWindow().getGuiScaledHeight()}}) {
            BinderView.Panels panels=DeckBinderLayout.panels(window[0]);
            int cols=DeckBinderLayout.stripCols(panels.width()), rows=DeckBinderLayout.stripRows(window[1]);
            int header=DeckBinderLayout.HEADER, top=DeckBinderLayout.stripTop(window[1]);
            List<Integer> cells=new ArrayList<>();
            for(int i=0;i<500;i++)cells.add(i);
            for(int slot:new int[]{0,1,cols-1,cols,rows*cols-1}) {
                if(slot<0||slot>=rows*cols)continue;
                int[] cell=DeckBinderLayout.listCell();
            int cx=DeckBinderLayout.gridX(panels.left())+(slot%cols)*cell[2];
                int cy=DeckBinderLayout.gridY(top,header)+(slot/cols)*cell[3];
                int hit=DeckBinderLayout.cellAt(cells,panels.left(),top,header,rows,0,cx+cell[0]/2.0,cy+cell[1]/2.0,cols);
                require(hit==slot,"slot "+slot+" at "+window[0]+"x"+window[1]+" answered cell "+hit);
            }
            int bar=BinderView.maxScroll(cells.size(),rows,cols);
            BinderView.Scrollbar scrollbar=DeckBinderLayout.scrollbar(panels.left(),top,header,rows,0,cells.size(),cols,panels.width());
            require(scrollbar.height()==rows*DeckBinderLayout.listCell()[3]-2,"scrollbar height must follow the row pitch");
            require(scrollbar.track()==cy0(top,header),"scrollbar must start at the first row");
            lines.add("命中测试 "+window[0]+"x"+window[1]+" 通过，最大滚动="+bar+" 滚动条高="+scrollbar.height());
        }
        return lines;
    }

    private static int cy0(int top,int header) {return DeckBinderLayout.gridY(top,header);}

    /** The filter column, search field and code chips must stay inside the strip. */
    private static String panel(int[] window) {
        var panels=DeckBinderLayout.panels(window[0]);
        var field=DeckBinderLayout.listSearchField(panels,window[1]);
        var clear=DeckBinderLayout.listClearChip(Minecraft.getInstance().font,panels,window[1]);
        require(panels.filterWidth()==0,"no permanent filter column");
        require(field.x()+field.w()<=clear.x(),"search must end before clear");
        require(clear.x()+clear.w()<=panels.left()+panels.width(),"toolbar must fit");
        return "检索工具栏有界、详细筛选按需展开";
    }

    /** Search parity with the catalog, driven through the real binder client state. */
    private static String search() {
        UUID screen=UUID.randomUUID();
        BinderClient.begin(screen);
        List<CardBinder.Cell> cells=new ArrayList<>();
        for(int cell=0;cell<CARDS.length;cell++) {
            var item=ModItems.cardItem(CARDS[cell]);
            require(item!=null,"missing card item "+CARDS[cell]);
            cells.add(new CardBinder.Cell(cell,CardItem.withRarity(new ItemStack(item),CardRarity.COMMON)));
        }
        BinderClient.receive(new BinderNetwork.Snapshot(screen,new UUID(1L,1L),60,List.copyOf(cells),""));
        expect("89631139",0);
        expect("4007",0);
        expect("青眼白龙",0);
        expect("Blue-Eyes",0);
        expect("27551",1);
        expect("7636",1);
        expect("リミット・リバース",1);
        expect("Limit Reverse",1);
        expect("dark_magician",2);
        int all=BinderClient.visibleCells("").size();
        require(all==BinderClient.capacity(),"an empty query must keep the unfiltered list: "+all);
        BinderClient.begin(UUID.randomUUID());
        return "检索对齐图鉴：密码/CID/中文名/英文名/日文名/注册ID 全部命中（空查询保留全部 "+all+" 格）";
    }

    private static void expect(String needle,int cell) {
        List<Integer> found=BinderClient.visibleCells(needle);
        require(found.contains(cell),"binder search '"+needle+"' found "+found+" but must contain "+cell);
    }

    private static int[] window(int width,int height) {return new int[]{width,height};}

    private static void require(boolean condition,String message) {
        if(!condition)throw new IllegalStateException(message);
    }

    private static Path result() {return FMLPaths.GAMEDIR.get().resolve("../generated/binder-layout.txt").normalize();}
}
