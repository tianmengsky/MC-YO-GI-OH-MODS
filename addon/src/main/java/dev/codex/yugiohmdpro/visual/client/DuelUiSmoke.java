package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.duel.*;
import com.kiki.yugioh.network.*;
import dev.codex.yugiohmdpro.client.NativeDuelScreen;
import dev.codex.yugiohmdpro.client.ui.DuelLayout;
import dev.codex.yugiohmdpro.duel.NativeBoard;
import dev.codex.yugiohmdpro.mixin.DuelScreenAccess;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;
import java.util.*;

/**
 * Development-only fixture for the MDPro3-style duel interaction layer, enabled solely by Gradle
 * {@code -PuiSmoke}. It pushes synthetic engine requests into the real {@link NativeDuelScreen},
 * clicks through the real hit tests, records every engine answer instead of sending it, and captures
 * a screenshot of each interaction state.
 *
 * <p>Each engine request is answered at most once, so the fixture re-issues a request with a new
 * revision before exercising the next control.</p>
 */
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon",value=Dist.CLIENT)
public final class DuelUiSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("yugioh.mdpro.uiSmoke");

    /** True while the fixture is driving, so diagnostics only run in the harness. */
    public static boolean enabled() {return ENABLED;}
    private static final List<String> ANSWERS=new ArrayList<>();
    private static final List<String> FAILURES=new ArrayList<>();
    private static boolean done;
    private static int step,frames;
    private static UUID duelId;
    private static NativeDuelScreen screen;

    private static final CardView BLUE=new CardView(true,"blue_eyes_white_dragon",1,CardPosition.ATTACK,3000,2500,false,false,false,CardRarity.COMMON);
    private static final CardView DARK=new CardView(true,"dark_magician",2,CardPosition.ATTACK,2500,2100,false,false,false,CardRarity.COMMON);
    private static final CardView RED=new CardView(true,"red_eyes_black_dragon",3,CardPosition.ATTACK,2400,2000,false,false,false,CardRarity.COMMON);

    // ------------------------------------------------------------------ fixtures

    private static List<CardView> spells() {return List.of(CardView.EMPTY,CardView.EMPTY,CardView.EMPTY,CardView.EMPTY,CardView.EMPTY);}
    private static List<CardView> row(CardView... cards) {
        List<CardView> result=new ArrayList<>(Arrays.asList(cards));
        while(result.size()<5)result.add(CardView.EMPTY);
        return result;
    }
    private static SideView side(String name,int lp,List<CardView> hand,List<CardView> monsters,List<CardView> grave) {
        return side(name,lp,hand,monsters,grave,List.of());
    }
    private static SideView side(String name,int lp,List<CardView> hand,List<CardView> monsters,List<CardView> grave,
                                 List<CardView> extra) {
        return new SideView(name,lp,30,extra.size(),hand,monsters,spells(),CardView.EMPTY,grave,extra,false);
    }
    private static CompoundTag base(int revision,int kind,Phase phase,String prompt) {
        return base(revision,kind,phase,prompt,List.of());
    }
    private static CompoundTag base(int revision,int kind,Phase phase,String prompt,List<CardView> extra) {
        var self=side("自己",8000,List.of(BLUE,DARK),row(BLUE,DARK),List.of(RED,DARK,BLUE),extra);
        var foe=side("对手",6500,List.of(),row(RED),List.of());
        var packet=new S2CDuelStatePacket(0,0,3,phase,false,-1,Component.empty(),0,self,foe,
                List.of(Component.literal("互动夹具 / 无实际对局")),false,false,-1,Component.empty(),List.of(),0,-1);
        var tag=new CompoundTag();
        var buf=new FriendlyByteBuf(Unpooled.buffer());
        try {packet.encode(buf);byte[] bytes=new byte[buf.readableBytes()];buf.readBytes(bytes);tag.putByteArray("board",bytes);}
        finally {buf.release();}
        if(duelId==null)duelId=UUID.randomUUID();
        tag.putUUID("duel",duelId);
        tag.putInt("viewer",0);
        tag.putInt("revision",revision);
        tag.putInt("kind",kind);
        tag.putBoolean("canAct",true);
        tag.putString("prompt",prompt);
        tag.putInt("phase",phase==Phase.BATTLE?8:4);
        tag.putInt("turn",3);
        tag.putInt("turnPlayer",0);
        tag.putBoolean("surrenderLocked",false);
        tag.put("choices",new ListTag());
        tag.put("chain",new ListTag());
        for(String prefix:List.of("self","foe")) {
            tag.putByteArray(prefix+"pendulum",NativeBoard.encodeCards(List.of(CardView.EMPTY,CardView.EMPTY)));
            tag.putByteArray(prefix+"removed",NativeBoard.encodeCards(List.of()));
        }
        return tag;
    }
    /** Idle special-summon candidates from the extra deck, including equal card records. */
    private static CompoundTag extraIdle(int revision) {
        CardView first=new CardView(true,"blue_eyes_white_dragon",401,CardPosition.ATTACK,3000,2500,false,false,false,CardRarity.COMMON);
        CardView second=new CardView(true,"blue_eyes_white_dragon",402,CardPosition.ATTACK,3000,2500,false,false,false,CardRarity.COMMON);
        CardView third=new CardView(true,"dark_magician",403,CardPosition.ATTACK,2500,2100,false,false,false,CardRarity.COMMON);
        CompoundTag tag=base(revision,11,Phase.MAIN1,"主要阶段：选择行动",List.of(first,second,third));
        choice(tag,"特殊召唤：青眼白龙（额外 1）",89631139,0,1,false,"特殊召唤","",0,0,DuelLayout.EXTRA,0);
        choice(tag,"特殊召唤：青眼白龙（额外 2）",89631139,1,1,false,"特殊召唤","",0,0,DuelLayout.EXTRA,1);
        choice(tag,"特殊召唤：黑魔导（额外 3）",46986414,2,1,false,"特殊召唤","",0,0,DuelLayout.EXTRA,2);
        choice(tag,"结束回合",0,3,7,true,"","",0,-1,0,-1);
        flags(tag,1,1,false,false,false);
        return tag;
    }
    private static void choice(CompoundTag tag,String label,int code,int value,int action,boolean command,
                               String note,String detail,int weight,int player,int location,int sequence) {
        var c=new CompoundTag();
        c.putString("label",label);
        c.putInt("code",code);
        c.putInt("value",value);
        c.putInt("action",action);
        c.putBoolean("command",command);
        c.putString("note",note);
        c.putString("detail",detail);
        c.putInt("weight",weight);
        c.putInt("player",player);
        c.putInt("location",location);
        c.putInt("sequence",sequence);
        ((ListTag)tag.get("choices")).add(c);
    }
    private static void flags(CompoundTag tag,int min,int max,boolean multiple,boolean cancelable,boolean finishable) {
        tag.putBoolean("multiple",multiple);
        tag.putInt("min",min);
        tag.putInt("max",max);
        tag.putInt("target",0);
        tag.putInt("mode",0);
        tag.putInt("mandatory",0);
        tag.putBoolean("cancelable",cancelable);
        tag.putBoolean("finishable",finishable);
        tag.putString("requirement","夹具要求");
        tag.putString("notice","");
    }

    /** Idle main phase: two hand actions, one board position change and one activation, plus commands. */
    private static CompoundTag idle(int revision) {
        CompoundTag tag=base(revision,11,Phase.MAIN1,"主要阶段：选择行动");
        choice(tag,"通常召唤：青眼白龙",89631139,0,0,false,"通常召唤","",0,0,DuelLayout.HAND,0);
        choice(tag,"盖放怪兽：青眼白龙",89631139,3,3,false,"盖放怪兽","",0,0,DuelLayout.HAND,0);
        choice(tag,"变更表示：青眼白龙",89631139,2,2,false,"变更表示","",0,0,DuelLayout.MZONE,0);
        choice(tag,"发动：黑魔导",46986414,5,5,false,"发动","破坏场上 1 张卡",0,0,DuelLayout.MZONE,1);
        choice(tag,"进入战斗阶段",0,6,6,true,"","",0,-1,0,-1);
        choice(tag,"结束回合",0,7,7,true,"","",0,-1,0,-1);
        choice(tag,"整理手牌",0,8,8,true,"","",0,-1,0,-1);
        flags(tag,1,1,false,false,false);
        return tag;
    }
    private static CompoundTag battle(int revision) {
        CompoundTag tag=base(revision,10,Phase.BATTLE,"战斗阶段：选择行动");
        choice(tag,"攻击：青眼白龙",89631139,1,1,false,"攻击","",0,0,DuelLayout.MZONE,0);
        choice(tag,"发动：黑魔导",46986414,0,0,false,"发动","",0,0,DuelLayout.MZONE,1);
        choice(tag,"进入主要阶段 2",0,2,2,true,"","",0,-1,0,-1);
        choice(tag,"结束回合",0,3,3,true,"","",0,-1,0,-1);
        flags(tag,1,1,false,false,false);
        return tag;
    }
    /** Same idle state one turn later, so the turn banner can be asserted. */
    private static CompoundTag nextTurn(int revision) {
        CompoundTag tag=idle(revision);
        tag.putInt("turn",4);
        tag.putInt("turnPlayer",1);
        return tag;
    }
    /** Chain that may be declined: two chainable effects, so the dialog needs a "do not chain" button. */
    private static CompoundTag chain(int revision,boolean forced) {
        CompoundTag tag=base(revision,16,Phase.MAIN1,forced?"选择必须发动的效果":"是否连锁？");
        choice(tag,"连锁：黑魔导 · P1 怪兽区 2 · 破坏 1 张卡",46986414,0,0,false,"连锁","破坏 1 张卡",0,0,DuelLayout.MZONE,1);
        choice(tag,"连锁：真红眼黑龙 · P1 墓地 1 · 特殊召唤",74677422,1,1,false,"连锁","特殊召唤",0,0,DuelLayout.GRAVE,0);
        flags(tag,1,1,false,!forced,false);
        var links=(ListTag)tag.get("chain");
        var link=new CompoundTag();link.putInt("index",1);link.putInt("code",89631139);links.add(link);
        return tag;
    }
    private static CompoundTag yesNo(int revision) {
        CompoundTag tag=base(revision,12,Phase.MAIN1,"是否发动 黑魔导 的效果？");
        choice(tag,"是，发动",46986414,1,1,false,"发动","破坏场上 1 张卡",0,0,DuelLayout.MZONE,1);
        choice(tag,"否",46986414,0,0,false,"否","",0,0,DuelLayout.MZONE,1);
        flags(tag,1,1,false,false,false);
        return tag;
    }
    /** Cards only in the graveyard, so the legal answer must come from the card list. */
    private static CompoundTag gravePick(int revision) {
        CompoundTag tag=base(revision,15,Phase.MAIN1,"选择要加入手牌的卡");
        choice(tag,"",89631139,0,0,false,"","",0,0,DuelLayout.GRAVE,0);
        choice(tag,"",46986414,1,1,false,"","",0,0,DuelLayout.GRAVE,1);
        choice(tag,"",74677422,2,2,false,"","",0,0,DuelLayout.GRAVE,2);
        flags(tag,1,2,true,true,false);
        return tag;
    }
    /** Tribute summon whose cost is a level sum, answered straight from the board. */
    private static CompoundTag tribute(int revision) {
        CompoundTag tag=base(revision,20,Phase.MAIN1,"选择解放的怪兽");
        choice(tag,"",89631139,0,0,false,"","",4,0,DuelLayout.MZONE,0);
        choice(tag,"",46986414,1,1,false,"","",4,0,DuelLayout.MZONE,1);
        flags(tag,5,2,true,true,false);
        return tag;
    }
    /** Placement request: every bit except the viewer's own five monster zones is forbidden. */
    private static CompoundTag place(int revision) {
        CompoundTag tag=base(revision,18,Phase.MAIN1,"请选择放置区域");
        for(int bit=0;bit<32;bit++) {
            if((0xFFFFFFE0&(1<<bit))!=0)continue;
            int pl=0^(bit/16), loc=bit%16<8?DuelLayout.MZONE:DuelLayout.SZONE, seq=bit%8;
            choice(tag,DuelLayout.locationName(loc)+" "+(seq+1),0,pl|(loc<<8)|(seq<<16),-1,false,"","",0,pl,loc,seq);
        }
        flags(tag,1,1,true,false,false);
        return tag;
    }
    /** Counter removal: two cards of capacity 2, two counters to distribute. */
    private static CompoundTag counters(int revision) {
        CompoundTag tag=base(revision,22,Phase.MAIN1,"移除 2 个指示物（点击卡片增加数量）");
        choice(tag,"",89631139,0,0,false,"","",2,0,DuelLayout.MZONE,0);
        choice(tag,"",46986414,1,1,false,"","",2,0,DuelLayout.MZONE,1);
        flags(tag,0,63,true,true,false);
        tag.putInt("target",2);
        return tag;
    }
    /** Card-name declaration already past its search step: the rows are engine search results. */
    private static CompoundTag declare(int revision) {
        CompoundTag tag=base(revision,142,Phase.MAIN1,"宣言卡名：输入中文卡名或卡号搜索");
        choice(tag,"青眼白龙 · 89631139",89631139,89631139,-1,false,"","",0,-1,0,-1);
        choice(tag,"黑魔导 · 46986414",46986414,46986414,-1,false,"","",0,-1,0,-1);
        choice(tag,"真红眼黑龙 · 74677422",74677422,74677422,-1,false,"","",0,-1,0,-1);
        flags(tag,1,1,false,true,false);
        return tag;
    }
    /** Position selection; the core itself auto-answers a zero or single-bit mask, so a live request
     * always carries at least two bits (see the frozen playerop.cpp select_position). */
    private static CompoundTag position(int revision,int positions) {
        CompoundTag tag=base(revision,19,Phase.MAIN1,"选择表示形式：青眼白龙");
        String[] labels={"表侧攻击表示","里侧攻击表示","表侧守备表示","里侧守备表示"};
        for(int i=0;i<4;i++) if((positions&(1<<i))!=0)
            choice(tag,labels[i],89631139,1<<i,-1,false,"","",0,-1,0,-1);
        flags(tag,1,1,false,false,false);
        return tag;
    }

    // ------------------------------------------------------------------ driver

    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        if(!ENABLED || done)return;
        try {
            if(step>0 && ++frames<6)return;
            frames=0;
            run();
        } catch(Throwable error) {
            finish("FAIL: "+error,error);
        }
    }

    /**
     * Drives the state machine while no screen is open. After ESC the screen is gone, so the render
     * event stops firing; the client tick keeps the remaining steps running.
     */
    @SubscribeEvent public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if(!ENABLED || done || event.phase!=net.minecraftforge.event.TickEvent.Phase.END)return;
        if(Minecraft.getInstance().screen!=null)return;
        try {
            if(++frames<6)return;
            frames=0;
            run();
        } catch(Throwable error) {
            finish("FAIL: "+error,error);
        }
    }

    private static void run() throws Exception {
        if(Minecraft.getInstance().getOverlay()!=null)return;
        switch(step) {
            case 0 -> {
                NativeDuelScreen.testAnswers=ANSWERS::add;
                NativeDuelScreen.receive(idle(1));
            }
            case 1 -> {
                screen=requireScreen();
                check(screen.fixture$kind()==11,"idle fixture must parse as kind 11");
                check(screen.fixture$menuRows()<0,"no card menu before a click");
                check(screen.fixture$cell(0,DuelLayout.MZONE,1)!=null,"own monster zone 2 must be drawn");
                clickCell(0,DuelLayout.MZONE,1);
            }
            case 2 -> {
                check(screen.fixture$menuRows()==1,"the activation card must offer exactly one action, got "+screen.fixture$menuRows());
                capture("ui-card-menu.png");
                click(screen.fixture$overlayTargets().get(0));
            }
            case 3 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[3]"),"clicking the 发动 row must answer choice 3, got "+ANSWERS);
                check(screen.fixture$menuRows()<0,"the menu must close after answering");
                ANSWERS.clear();
                NativeDuelScreen.receive(idle(2));
            }
            case 4 -> clickControl("战斗");
            case 5 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[4]"),"the battle-phase button must answer the 进入战斗阶段 entry, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(idle(3));
            }
            case 6 -> clickControl("结束");
            case 7 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[5]"),"the end-turn button must answer the 结束回合 entry, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(idle(4));
            }
            case 8 -> clickCell(0,DuelLayout.HAND,0);
            case 9 -> {
                check(screen.fixture$menuRows()==2,"a hand card with summon and set must offer two actions, got "+screen.fixture$menuRows());
                capture("ui-hand-menu.png");
                click(screen.fixture$overlayTargets().get(0));
            }
            case 10 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[0]"),"the first hand action must answer choice 0, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(chain(5,false));
            }
            case 11 -> {
                check(screen.fixture$dialogOpen(),"a chain request must open the chain dialog");
                check(screen.fixture$dialogOptions()==2,"two chainable effects must produce two rows, got "+screen.fixture$dialogOptions());
                check(screen.fixture$dialogButtons()==1,"a declinable chain must offer 不连锁, got "+screen.fixture$dialogButtons());
                capture("ui-chain.png");
                click(screen.fixture$overlayTargets().get(0));
            }
            case 12 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[0]"),"the first chain row must answer choice 0, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(chain(6,true));
            }
            case 13 -> {
                check(screen.fixture$dialogOpen(),"a forced chain must still open the dialog");
                check(screen.fixture$dialogButtons()==0,"a forced chain must not offer 不连锁, got "+screen.fixture$dialogButtons());
                NativeDuelScreen.receive(yesNo(7));
            }
            case 14 -> {
                check(screen.fixture$dialogOpen(),"a yes/no request must open a dialog");
                check(screen.fixture$dialogButtons()==2,"yes/no must offer two buttons, got "+screen.fixture$dialogButtons());
                capture("ui-yesno.png");
                click(screen.fixture$overlayTargets().get(0));
            }
            case 15 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[0]"),"the 发动 button must answer the yes entry, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(gravePick(8));
            }
            case 16 -> {
                check(screen.fixture$browserOpen(),"graveyard-only candidates must open the card list");
                check(screen.fixture$browserPick(),"the card list must be in pick mode");
                check(screen.fixture$browserRows()==3,"all three graveyard candidates must be listed, got "+screen.fixture$browserRows());
                List<int[]> targets=screen.fixture$overlayTargets();
                check(targets.size()>=3,"the pick list must expose three clickable cells, got "+targets.size());
                capture("ui-pick.png");
                click(targets.get(0));
                click(targets.get(1));
            }
            case 17 -> {
                check(screen.fixture$selectedCount()==2,"two clicks must select two cards, got "+screen.fixture$selectedCount());
                capture("ui-pick-selected.png");
                clickControl("确定");
            }
            case 18 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[0, 1]"),"confirm must answer the picked choices in click order, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(tribute(9));
            }
            case 19 -> {
                check(!screen.fixture$browserOpen(),"tribute candidates on the board must not open the list");
                clickCell(0,DuelLayout.MZONE,0);
            }
            case 20 -> {
                check(screen.fixture$selectedCount()==1,"one tribute click must select one material, got "+screen.fixture$selectedCount());
                clickCell(0,DuelLayout.MZONE,1);
            }
            case 21 -> {
                check(screen.fixture$selectedCount()==2,"two tribute clicks must select two materials, got "+screen.fixture$selectedCount());
                check(ANSWERS.isEmpty(),"a level-sum request must wait for the confirm button");
                clickControl("确定");
            }
            case 22 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[0, 1]"),"tribute confirm must answer both materials, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(place(10));
            }
            case 23 -> {
                check(screen.fixture$placeCandidates()==5,"only the five free monster zones may be candidates, got "+screen.fixture$placeCandidates());
                capture("ui-place.png");
                clickCell(0,DuelLayout.MZONE,2);
            }
            case 24 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[2]"),"placing in monster zone 3 must answer choice 2, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(counters(11));
            }
            case 25 -> {
                clickCell(0,DuelLayout.MZONE,0);
                clickCell(0,DuelLayout.MZONE,0);
            }
            case 26 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[0, 0]"),"two counters on one card must answer that card twice, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(battle(12));
            }
            case 27 -> {
                check(screen.fixture$kind()==10,"the battle fixture must parse as kind 10");
                clickControl("主2");
            }
            case 28 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[2]"),"the main-phase-2 button must answer the 进入主要阶段 2 entry, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(declare(13));
            }
            case 29 -> {
                check(screen.fixture$browserOpen(),"a card-name declaration must use the card list");
                check(screen.fixture$browserPick(),"the declaration list must be in pick mode");
                check(screen.fixture$browserRows()==3,"all three search results must be listed, got "+screen.fixture$browserRows());
                check(screen.fixture$control("搜索")!=null,"the declaration list must offer a server search");
                capture("ui-declare.png");
                click(screen.fixture$overlayTargets().get(1));
            }
            case 30 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[1]|46986414"),
                        "declaring a card must answer its index and its code, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(position(14,0x5));
            }
            case 31 -> {
                check(screen.fixture$dialogOpen(),"a position request must open a dialog");
                check(screen.fixture$dialogOptions()==2,
                        "attack|defence must offer two positions, got "+screen.fixture$dialogOptions());
                capture("ui-position.png");
                click(screen.fixture$overlayTargets().get(1));
            }
            case 32 -> {
                check(ANSWERS.size()==1 && ANSWERS.get(0).equals("[1]"),
                        "the second position row must answer choice 1, got "+ANSWERS);
                ANSWERS.clear();
                NativeDuelScreen.receive(position(15,0xD));
            }
            case 33 -> {
                check(screen.fixture$dialogOptions()==3,
                        "attack|defence|set must offer three positions, got "+screen.fixture$dialogOptions());
                capture("ui-position3.png");
                NativeDuelScreen.receive(idle(16));
            }
            case 34 -> {
                org.slf4j.LoggerFactory.getLogger("MDProUiSmoke").info("layout {}",screen.fixture$layout());
                capture("ui-board.png");
                check(screen.fixture$control("整理手牌")!=null,"the tidy-hand command must have a button");
                check(screen.fixture$control("投降")!=null,"surrender must stay available");
                check(screen.fixture$control("主1")==null,"the current phase must not be clickable");
                // The interaction layer must not have displaced the original board visuals.
                check(DuelScreenHolograms.panelsLastFrame()>=2,
                        "both original monster projections must still render, got "+DuelScreenHolograms.panelsLastFrame());
                check(dev.codex.yugiohfix.client.DuelPuppets.puppetsLastFrame()==2,
                        "both original animated portraits must still render, got "+dev.codex.yugiohfix.client.DuelPuppets.puppetsLastFrame());
                // The duel disk is the off-hand item model attached to each portrait; the upper-body crop
                // must be lifted before it is drawn or the disk disappears.
                check(dev.codex.yugiohmdpro.client.PuppetClip.attachmentCount()>=2,
                        "the duel disk must be drawn outside the portrait crop, attachments="
                                +dev.codex.yugiohmdpro.client.PuppetClip.attachmentCount());
                check(dev.codex.yugiohmdpro.client.PuppetClip.diskCount()>=2,"Both portraits must receive an actual DuelDiskItem stack, including the AI fallback");
                // In-world standing holograms must always use the 2D artwork: a card that ships a GeckoLib
                // model (this one does) must be answered with "no model" so the mesh path is never taken.
                check(dev.codex.yugiohfix.client.Hologram3DPack.of("7_colored_fish")==null,
                        "3D hologram models must be disabled in favour of the 2D artwork");
                // The detail panel is pinned to the top-left corner, and the native Minecraft tooltip must
                // be anchored beside the same card rather than at the pointer.
                screen.fixture$hover(0,DuelLayout.MZONE,1);
                check(screen.fixture$tooltipReady(),
                        "the native card tooltip must have content for a face-up board card");
                check(screen.fixture$tooltipAnchor()!=null,"the tooltip must be anchored to a card rectangle");
                // The card back must resolve to this addon's replaced artwork, not the old placeholder.
                check("yugioh_mdpro_addon".equals(com.kiki.yugioh.client.texture.CardArtTextures.back().getNamespace()),
                        "the card back must come from this addon, got "
                                +com.kiki.yugioh.client.texture.CardArtTextures.back());
            }
            case 35 -> {
                capture("ui-detail.png");
                // The box must sit beside the card, never straddling it, and never inside the right column.
                int[] anchor=screen.fixture$tooltipAnchor();
                int[] box=screen.fixture$tooltipBox();
                float tooltipScale=((DuelScreenAccess)(Object)screen).mdpro$scale();
                check(box!=null,"the info box must have been drawn for the pinned hover");
                if(box!=null && anchor!=null) {
                    int cardLeft=Math.round(anchor[0]*tooltipScale);
                    int cardRight=Math.round((anchor[0]+anchor[2])*tooltipScale);
                    int cardTop=Math.round(anchor[1]*tooltipScale);
                    int cardBottom=Math.round((anchor[1]+anchor[3])*tooltipScale);
                    boolean clear=box[0]+box[2]<=cardLeft+1 || box[0]>=cardRight-1
                            || box[1]+box[3]<=cardTop+1 || box[1]>=cardBottom-1;
                    check(clear,"the info box must not cover the card it describes, box="
                            +java.util.Arrays.toString(box)+" card="+cardLeft+","+cardTop+".."+cardRight+","+cardBottom);
                }
                // And it must disappear as soon as the pointer leaves the card.
                screen.fixture$clearHover();
            }
            case 36 -> {
                check(!screen.fixture$tooltipReady(),
                        "the info box must not stay on screen after the pointer leaves the card");
                capture("ui-tooltip-gone.png");
                // Hovering a hand card must show its information before any action has been picked.
                int[] centre=screen.fixture$handSlotCentre(0);
                String hovered=screen.fixture$hoverAt(centre[0],centre[1]);
                check(!hovered.isEmpty(),
                        "hovering a hand card must show its information, got nothing at "+centre[0]+","+centre[1]);
                // ESC with nothing open leaves the duel interface but keeps the duel running.
                screen.keyPressed(256,0,0);
            }
            case 37 -> {
                // Inside a world ESC leaves the screen null (the world keeps rendering); this fixture has no
                // world, so Minecraft substitutes the title screen. Either way the duel UI must be gone.
                check(!(Minecraft.getInstance().screen instanceof NativeDuelScreen),
                        "ESC must leave the duel interface, got "+Minecraft.getInstance().screen);
                capture("ui-exited.png");
                // Using the duel table again sends an open request, which must bring the interface back.
                CompoundTag reopen=idle(17);
                reopen.putBoolean("open",true);
                NativeDuelScreen.receive(reopen);
            }
            case 38 -> {
                check(Minecraft.getInstance().screen instanceof NativeDuelScreen,
                        "an open request must reopen the duel interface, got "+Minecraft.getInstance().screen);
                screen=requireScreen();
                capture("ui-reopened.png");
                // A pile the player opens by hand (extra deck, grave, banished) must support clicking a
                // card to act on it, and must show that card's information.
                screen.fixture$openPile(screen.fixture$viewerSide(),DuelLayout.HAND);
            }
            case 39 -> {
                check(screen.fixture$browserOpen(),"the pile list must open");
                check(!screen.fixture$browserPick(),"a hand-opened pile is not a selection request");
                check(screen.fixture$browserRows()>0,"the pile list must show cards, got "
                        +screen.fixture$browserRows());
                // Hovering a card in the open list must print its information, exactly like the board.
                screen.fixture$hoverBrowserSlot(0);
                check(screen.fixture$tooltipReady(),
                        "hovering a card in the open pile list must show its information");
                capture("ui-pile.png");
                List<int[]> targets=screen.fixture$overlayTargets();
                check(!targets.isEmpty(),"the pile list must expose clickable cards");
                int[] slot=targets.get(0);
                click(slot);
            }
            case 40 -> {
                check(!screen.fixture$detailName().isEmpty(),
                        "clicking a card in a pile list must show its information");
                // Either the single action was taken, or the action menu opened because several exist.
                boolean acted=!ANSWERS.isEmpty();
                boolean chose=screen.fixture$menuRows()>=1;
                check(acted || chose,
                        "clicking a card in a pile list must act or offer the action menu (answers="
                                +ANSWERS.size()+", menuRows="+screen.fixture$menuRows()+")");
                capture("ui-pile-action.png");
                ANSWERS.clear();
                CompoundTag tag=gravePick(100);tag.putInt("kind",25);tag.putInt("min",3);tag.putInt("max",3);
                NativeDuelScreen.receive(tag);
            }
            case 41 -> click(screen.fixture$overlayTargets().get(1));
            case 42 -> click(screen.fixture$overlayTargets().get(0));
            case 43 -> click(screen.fixture$overlayTargets().get(2));
            case 44 -> {
                check(ANSWERS.equals(List.of("[1, 0, 2]")),"Sort must preserve click order: "+ANSWERS);
                ANSWERS.clear();var tag=base(101,23,Phase.MAIN1,"选择合计 2 的素材");flags(tag,2,2,true,false,false);tag.putInt("target",2);
                choice(tag,"可按 1 或 3",89631139,0,-1,false,"","",1|(3<<16),0,DuelLayout.MZONE,0);
                choice(tag,"等级 1",46986414,1,-1,false,"","",1,0,DuelLayout.MZONE,1);
                NativeDuelScreen.receive(tag);
            }
            case 45 -> clickCell(0,DuelLayout.MZONE,0);
            case 46 -> {
                check(ANSWERS.isEmpty() && screen.fixture$selectedCount()==1,"An unreachable intermediate material sum must stay selectable");
                clickCell(0,DuelLayout.MZONE,1);
            }
            case 47 -> {
                check(ANSWERS.equals(List.of("[0, 1]")),"Exact material sum must answer actual indices: "+ANSWERS);ANSWERS.clear();
                var tag=base(102,23,Phase.MAIN1,"含必选素材");flags(tag,1,1,true,false,false);tag.putInt("target",5);tag.putInt("mandatory",1);
                choice(tag,"必选 2",89631139,0,-1,false,"","",2,0,DuelLayout.MZONE,0);
                choice(tag,"可选 3",46986414,0,-1,false,"","",3,0,DuelLayout.MZONE,1);
                NativeDuelScreen.receive(tag);
            }
            case 48 -> clickCell(0,DuelLayout.MZONE,1);
            case 49 -> {
                check(ANSWERS.equals(List.of("[1]")),"Mandatory material must be counted without resubmitting its index: "+ANSWERS);ANSWERS.clear();
                var tag=base(103,26,Phase.MAIN1,"选择或取消素材");flags(tag,1,2,false,true,true);
                choice(tag,"选择",89631139,0,-1,false,"选择","",0,0,DuelLayout.MZONE,0);
                choice(tag,"取消选择",46986414,1,-1,false,"取消选择","",0,0,DuelLayout.MZONE,1);
                NativeDuelScreen.receive(tag);
            }
            case 50 -> clickCell(0,DuelLayout.MZONE,1);
            case 51 -> {
                check(ANSWERS.equals(List.of("[1]")),"Unselect request must immediately send the clicked operation: "+ANSWERS+" kind="+screen.fixture$kind()+" selected="+screen.fixture$selectedCount()+" browser="+screen.fixture$browserOpen());ANSWERS.clear();
                var tag=base(104,140,Phase.MAIN1,"宣言两个种族");flags(tag,2,2,true,false,false);
                for(int i=0;i<4;i++)choice(tag,new String[]{"战士","魔法师","天使","恶魔"}[i],0,1<<i,-1,true,"","",0,-1,0,-1);
                NativeDuelScreen.receive(tag);
            }
            case 52 -> click(screen.fixture$overlayTargets().get(0));
            case 53 -> {
                check(ANSWERS.isEmpty() && screen.fixture$selectedCount()==1,"Race declaration must retain multiple selections");capture("ui-race-multiple.png");
                click(screen.fixture$overlayTargets().get(2));
            }
            case 54 -> {
                check(ANSWERS.equals(List.of("[0, 2]")),"Race grid must submit both exact choices: "+ANSWERS);ANSWERS.clear();
                var tag=base(105,14,Phase.MAIN1,"长效果列表");flags(tag,1,1,false,false,false);
                for(int i=0;i<12;i++)choice(tag,"效果选项 "+i,0,i,-1,true,"","",0,-1,0,-1);
                NativeDuelScreen.receive(tag);
            }
            case 55 -> {check(screen.fixture$dialogOptions()==8,"Long effect list must show a bounded page");screen.mouseScrolled(0,0,-1);}
            case 56 -> {check(screen.fixture$dialogOptions()==4,"Remaining effect choices must be reachable on the next page");capture("ui-options-page2.png");click(screen.fixture$overlayTargets().get(0));}
            case 57 -> {
                check(ANSWERS.equals(List.of("[8]")),"Second page must send original engine index 8: "+ANSWERS);ANSWERS.clear();
                var tag=base(106,143,Phase.MAIN1,"宣言数字");flags(tag,1,1,false,false,false);
                for(int i=0;i<12;i++)choice(tag,Integer.toString(i+1),0,i,-1,true,"","",0,-1,0,-1);
                NativeDuelScreen.receive(tag);
            }
            case 58 -> click(screen.fixture$overlayTargets().get(3));
            case 59 -> {
                check(ANSWERS.equals(List.of("[3]")),"Number declaration must return option index, not displayed number: "+ANSWERS);ANSWERS.clear();
                var tag=place(107);tag.putInt("kind",24);tag.putInt("min",2);tag.putInt("max",2);NativeDuelScreen.receive(tag);
            }
            case 60 -> clickCell(0,DuelLayout.MZONE,2);
            case 61 -> clickCell(0,DuelLayout.MZONE,3);
            case 62 -> {
                check(ANSWERS.equals(List.of("[2, 3]")),"Disable-field must support selecting multiple zones: "+ANSWERS);ANSWERS.clear();
                var tag=place(108);tag.putBoolean("cancelable",true);NativeDuelScreen.receive(tag);
            }
            case 63 -> clickControl("取消");
            case 64 -> {
                check(ANSWERS.equals(List.of("[]|cancel")),"Optional placement must expose its cancellation: "+ANSWERS);ANSWERS.clear();
                var tag=base(109,141,Phase.MAIN1,"宣言两个属性");flags(tag,2,2,true,false,false);
                for(int i=0;i<3;i++)choice(tag,new String[]{"地","水","炎"}[i],0,1<<i,-1,true,"","",0,-1,0,-1);
                NativeDuelScreen.receive(tag);
            }
            case 65 -> click(screen.fixture$overlayTargets().get(0));
            case 66 -> click(screen.fixture$overlayTargets().get(1));
            case 67 -> {
                check(ANSWERS.equals(List.of("[0, 1]")),"Attribute multiple selection must submit both: "+ANSWERS);ANSWERS.clear();
                NativeDuelScreen.receive(yesNo(110));
            }
            case 68 -> click(screen.fixture$overlayTargets().get(0));
            case 69 -> {NativeDuelScreen.receive(yesNo(110));click(screen.fixture$overlayTargets().get(0));}
            case 70 -> {
                check(ANSWERS.size()==1,"Resent snapshot must retain duplicate-click protection");
                NativeDuelScreen.receive(yesNo(111));NativeDuelScreen.receive(yesNo(110));
            }
            case 71 -> click(screen.fixture$overlayTargets().get(0));
            case 72 -> {
                check(ANSWERS.size()==2,"New revision must unlock controls despite a later stale snapshot");
                var tag=new CompoundTag();tag.putUUID("duel",duelId);tag.putBoolean("finished",true);tag.putBoolean("cancelled",true);tag.putString("message","Fixture cancellation");NativeDuelScreen.receive(tag);
            }
            case 73 -> {
                check(!(Minecraft.getInstance().screen instanceof NativeDuelScreen),"Cancellation must leave the duel GUI");
                ANSWERS.clear();NativeDuelScreen.receive(chain(112,false));screen=requireScreen();
            }
            case 74 -> {
                // Click the actual negative button, rather than testing only affirmative rows.
                var targets=screen.fixture$overlayTargets();click(targets.get(targets.size()-1));
                click(targets.get(0));
            }
            case 75 -> {
                check(ANSWERS.equals(List.of("[]|cancel")),"No-chain click must only decline, including a following duplicate click: "+ANSWERS);
                ANSWERS.clear();NativeDuelScreen.receive(chain(113,true));
            }
            case 76 -> {
                screen.keyPressed(256,0,0);
                check(ANSWERS.isEmpty() && screen.fixture$dialogOpen(),"A mandatory chain cannot be declined");
                NativeDuelScreen.receive(yesNo(114));
            }
            case 77 -> click(screen.fixture$overlayTargets().get(1));
            case 78 -> {
                check(ANSWERS.equals(List.of("[]|cancel")),"Not-activate button must send a negative answer, never option zero: "+ANSWERS);
                ANSWERS.clear();NativeDuelScreen.receive(yesNo(115));screen.keyPressed(256,0,0);
            }
            case 79 -> {
                check(ANSWERS.equals(List.of("[]|cancel")),"ESC must decline an effect yes/no prompt");
                ANSWERS.clear();NativeDuelScreen.receive(yesNo(116));screen.mouseClicked(0,0,1);
            }
            case 80 -> {
                check(ANSWERS.equals(List.of("[]|cancel")),"Right-click must decline an effect yes/no prompt");
                NativeDuelScreen.receive(idle(117));
                screen.resize(Minecraft.getInstance(),screen.width,190);
                String id=dev.codex.yugiohmdpro.engine.DuelCatalog.legacyId(70479321);
                screen.fixture$hoverCard(new CardView(true,id,999,CardPosition.ATTACK,1600,900,false,false,false,CardRarity.COMMON));
            }
            case 81 -> {
                String text=screen.fixture$tooltipText();
                // Catalog text may be the reviewed Chinese translation or the original English
                // fallback. Both must retain the monster-effect section and the ATK increase.
                boolean monsterHeader=text.contains("【怪兽效果】") || text.contains("[ Monster Effect ]");
                boolean paragraph=text.contains("③") || text.contains("Once per turn");
                boolean gain=text.contains("上升600") || text.contains("gains 600 ATK");
                check(monsterHeader && paragraph && gain,"Pendulum tooltip must retain every monster-effect paragraph: "+text);
                int[] box=screen.fixture$tooltipBox();
                check(box!=null && box[1]>=0 && box[1]+box[3]<=screen.height,"Long rules tooltip must fit the screen");
                screen.mouseScrolled(0,0,-1);
            }
            case 82 -> {
                check(screen.fixture$tooltipScroll()>0,"Long Pendulum effect text must be scrollable");
                capture("ui-pendulum-scrolled.png");
                var window=Minecraft.getInstance().getWindow();
                screen.resize(Minecraft.getInstance(),window.getGuiScaledWidth(),window.getGuiScaledHeight());
            }
            case 83 -> capture("ui-pendulum-full-effects.png");
            case 84 -> {
                check(Minecraft.getInstance().getResourceManager().listResources("3d",p->p.getNamespace().equals("yugioh_interaction_fix")).isEmpty(),"No old monster mesh resource may remain");
                // Extra-deck idle actions must open the complete pile, even though only one card is
                // rendered in the board zone. The second copy deliberately has the same card code.
                ANSWERS.clear();
                NativeDuelScreen.receive(extraIdle(118));
            }
            case 85 -> {
                check(screen.fixture$browserOpen(),"extra-deck idle candidates must open the pile list");
                check(!screen.fixture$browserPick(),"extra-deck idle list is an action browser, not a pick request");
                check(screen.fixture$browserRows()==3,"all extra-deck candidates must be listed, got "+screen.fixture$browserRows());
                // Every pile must stay anonymous: only opening the list reveals its contents.
                for(int location:new int[]{DuelLayout.EXTRA,DuelLayout.GRAVE,DuelLayout.DECK}) {
                    int[] pile=screen.fixture$cell(screen.fixture$viewerSide(),location,0);
                    check(pile!=null,"the pile cell "+DuelLayout.locationName(location)+" must be drawn");
                    if(pile==null)continue;
                    String leaked=screen.fixture$hoverAt(pile[0]+pile[2]/2,pile[1]+pile[3]/2);
                    check(leaked.isEmpty(),"hovering the "+DuelLayout.locationName(location)
                            +" pile must not name a card, got: "+leaked);
                }
                capture("ui-extra-idle.png");
                List<int[]> targets=screen.fixture$overlayTargets();
                check(targets.size()>=3,"extra-deck list must expose every candidate, got "+targets.size());
                click(targets.get(1));
            }
            case 86 -> {
                check(ANSWERS.equals(List.of("[1]")),"clicking the second duplicate extra-deck copy must answer index 1, got "+ANSWERS);
                ANSWERS.clear();
                // A phase change now has its own presentation beat on the server; the client must show
                // it as a banner, not only as a line in the side panel. The hover is dropped here so the
                // screenshot below shows the banner rather than the tooltip that was on top of it.
                screen.fixture$clearHover();
                NativeDuelScreen.receive(battle(200));
            }
            case 87 -> {
                check(screen.fixture$banner().contains("战斗阶段"),
                        "a phase change must raise the phase banner, got: "+screen.fixture$banner());
                int[] box=screen.fixture$bannerBox();
                check(box!=null,"the phase banner must have been drawn");
                check(box[1]+box[3]<=44,"the phase banner must sit in the empty strip at the top of the screen, got y="
                        +box[1]+" h="+box[3]);
                int[] topCell=screen.fixture$cell(1,DuelLayout.MZONE,0);
                if(topCell!=null) check(box[1]+box[3]<=topCell[1],
                        "the phase banner must not cover the opponent's field: banner ends at "+(box[1]+box[3])
                                +", top row starts at "+topCell[1]);
                capture("ui-phase-banner.png");
                NativeDuelScreen.receive(nextTurn(201));            }
            case 88 -> {
                check(screen.fixture$banner().contains("第 4 回合"),
                        "a turn change must raise the turn banner, got: "+screen.fixture$banner());
                int[] box=screen.fixture$bannerBox();
                check(box!=null && box[1]+box[3]<=44,"the turn banner must also stay in the top strip");
                capture("ui-turn-banner.png");
                finish("PASS: 89 steps including extra-deck duplicate-sequence selection, negative chain/effect answers,"
                        +" the phase/turn banners above the board and full scrollable Pendulum rules",null);
            }
            default -> finish("PASS",null);
        }
        if(!done)step++;
    }

    private static NativeDuelScreen requireScreen() {
        if(Minecraft.getInstance().screen instanceof NativeDuelScreen current)return current;
        throw new IllegalStateException("the duel fixture screen is not open");
    }
    private static void clickCell(int player,int location,int sequence) {
        int[] rect=screen.fixture$cell(player,location,sequence);
        if(rect==null)throw new IllegalStateException("cell "+player+"/"+location+"/"+sequence+" is not drawn");
        click(new int[]{rect[0]+rect[2]/2,rect[1]+rect[3]/2});
    }
    private static void clickControl(String label) {
        int[] point=screen.fixture$control(label);
        if(point==null)throw new IllegalStateException("no enabled control labelled "+label);
        click(point);
    }
    private static void click(int[] point) {
        float scale=((DuelScreenAccess)(Object)screen).mdpro$scale();
        screen.mouseClicked(point[0]*scale,point[1]*scale,0);
    }
    private static void check(boolean condition,String message) {
        if(!condition)FAILURES.add(message);
    }
    private static void capture(String name) throws Exception {
        try(var pixels=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            pixels.writeToFile(FMLPaths.GAMEDIR.get().resolve("../generated/"+name).normalize());
        }
    }
    private static void finish(String result,Throwable error) {
        done=true;
        StringBuilder text=new StringBuilder(result);
        if(!FAILURES.isEmpty()) {
            text.setLength(0);text.append("FAIL: ").append(FAILURES.size()).append(" interaction assertion(s) failed\n");
            for(String failure:FAILURES)text.append(" - ").append(failure).append('\n');
        }
        try {Files.writeString(FMLPaths.GAMEDIR.get().resolve("../generated/ui-smoke-result.txt").normalize(),text.toString());}
        catch(Exception ignored) {}
        if(error!=null)org.slf4j.LoggerFactory.getLogger("MDProUiSmoke").error("UI smoke failed",error);
        else org.slf4j.LoggerFactory.getLogger("MDProUiSmoke").info("UI smoke finished: {}",text);
        Minecraft.getInstance().stop();
    }
}
