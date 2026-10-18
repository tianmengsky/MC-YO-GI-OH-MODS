package dev.codex.yugiohmdpro.client;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.card.Cards;
import com.kiki.yugioh.client.screen.DuelScreen;
import com.kiki.yugioh.duel.CardPosition;
import com.kiki.yugioh.network.*;
import dev.codex.yugiohmdpro.client.ui.*;
import dev.codex.yugiohmdpro.client.ui.ChoiceModel.Choice;
import dev.codex.yugiohmdpro.duel.DuelNetwork;
import dev.codex.yugiohmdpro.engine.DuelCatalog;
import dev.codex.yugiohmdpro.engine.MaterialSelection;
import dev.codex.yugiohmdpro.mixin.ClientDuelAccess;
import dev.codex.yugiohmdpro.mixin.DuelScreenAccess;
import dev.codex.yugiohmdpro.visual.client.*;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/**
 * MDPro duel board with MDPro3-style interaction.
 *
 * <p>The board, card art, holograms and life-point plates are still the original mod's; this class
 * replaces only the interaction layer. Following MDPro3, clicking a card never submits an action: it
 * shows the card and opens that card's own action menu, and the second click on a menu row is what
 * answers the engine. Multi-card requests highlight the legal cards and keep a confirm/cancel pair in
 * the bottom bar; placement requests highlight the legal empty zones; chain requests list the
 * chainable effects with a "do not chain" escape.</p>
 *
 * <p>See {@code docs/对决GUI重构设计-仿MDPro3.md} for the MDPro3 evidence behind each rule.</p>
 */
public final class NativeDuelScreen extends DuelScreen {
    private static final int BAR_H=16;
    private static final int SIDE_W=DuelLayout.SIDE_W;
    private static UUID currentDuel;
    private static CompoundTag latest;
    private static int lastAttackEvent;
    private static int lastSound;
    /**
     * Cues that arrived with a beat but have not been played yet. The server now publishes one beat
     * per visible action, but a single beat can still carry several cues; playing them all in one
     * frame was the "whole chain sounds at once" defect.
     */
    private static final java.util.ArrayDeque<dev.codex.yugiohfix.sound.SoundCue> pendingSounds=new java.util.ArrayDeque<>();
    private static long nextSoundAt;
    /** Turn/phase banner: the presentation counterpart of the engine's own NewTurn/NewPhase beat. */
    private String bannerText="";
    private long bannerUntil;
    private int bannerTurn=-1,bannerPhase=-1;
    /** Last drawn banner rectangle, so a fixture can assert it never covers the play area. */
    private int[] bannerBox;

    private CompoundTag nativeState;
    private S2CDuelStatePacket board;
    private ChoiceModel model;
    private final List<DuelUi.Control> controls=new ArrayList<>();
    private final Map<Integer,List<CardView>> pendulum=new HashMap<>();
    private final Map<Integer,List<CardView>> removed=new HashMap<>();
    /** Engine choice indices in click order; repeats encode counter counts. */
    private final List<Integer> selected=new ArrayList<>();
    private DuelWidgets.PopupMenu menu;
    private int menuPlayer,menuLocation,menuSequence,menuUid=-1;
    private DuelWidgets.Dialog dialog;
    private int dialogRevision=-1;
    private Browser browser;
    private CardView hover,zoom;
    /** Rectangle of the hovered card, so the native tooltip can sit beside it instead of at the pointer. */
    private DuelLayout.Rect hoverRect;
    /** Fixture-only pinned hover; always null in normal play. */
    private CardView fixtureHover;
    /** Last drawn card info box in screen coordinates, for the fixture. */
    private int[] lastTooltipBox;
    private String tooltipCard="";
    private int tooltipScroll,tooltipLines,tooltipVisible;
    private List<String> tooltipText=List.of();
    /** Last card shown in the detail panel, so the panel does not flicker while the mouse moves. */
    private CardView detail;
    /** Revision the player dismissed with ESC; a newer revision with a request reopens the screen. */
    private static int dismissedRevision=-1;
    private int zoomPlayer,zoomLocation,zoomSequence;
    private EditBox search;
    private boolean searchForServer,searchActive;
    private int page;
    private boolean sent,confirmSurrender,showLog=true;
    private List<Integer> checkedMaterials;
    private boolean materialValid;
    private String toast="";
    private int toastTicks;
    /** Geometry of the last rendered card grid, so the fixture can click real cells. */
    private int gridX,gridY,gridOffset=20,gridRows=1,gridPerPage=8;
    /** Development-only observation seam: when set, engine answers are recorded instead of sent. */
    public static java.util.function.Consumer<String> testAnswers;

    public NativeDuelScreen(CompoundTag tag,S2CDuelStatePacket packet) {super(packet);accept(tag,packet);}

    private DuelScreenAccess access() {return (DuelScreenAccess)(Object)this;}

    // ------------------------------------------------------------------ server state

    public static void receive(CompoundTag tag) {
        if(tag==null)return;
        Minecraft mc=Minecraft.getInstance();
        if(tag.getBoolean("finished")) {
            if(currentDuel!=null && currentDuel.equals(tag.getUUID("duel"))) {
                clear();
                if(tag.getBoolean("cancelled")) {
                    if(mc.screen instanceof NativeDuelScreen)mc.setScreen(null);
                    mc.gui.setTimes(5,60,10);
                    mc.gui.setTitle(Component.literal("对局取消 · 不计胜负"));
                    mc.gui.setSubtitle(Component.literal(tag.getString("message")));
                }
            }
            return;
        }
        if(!tag.contains("board",Tag.TAG_BYTE_ARRAY))return;
        if(currentDuel!=null && currentDuel.equals(tag.getUUID("duel")) && latest!=null
                && tag.getInt("revision")<latest.getInt("revision"))return;
        var buf=new FriendlyByteBuf(Unpooled.wrappedBuffer(tag.getByteArray("board")));
        S2CDuelStatePacket packet;
        try {packet=S2CDuelStatePacket.decode(buf);}finally {buf.release();}
        boolean fresh=!tag.getUUID("duel").equals(currentDuel);
        if(fresh){HologramActions.clear();lastAttackEvent=0;lastSound=0;pendingSounds.clear();nextSoundAt=0;
            dev.codex.yugiohfix.client.DuelPuppetActions.reset();dev.codex.yugiohfix.client.ClientDuelSounds.reset();dev.codex.yugiohfix.client.ClientLifePoints.reset();}
        currentDuel=tag.getUUID("duel");latest=tag;
        ClientBoardAnchor.accept(currentDuel,tag.getDouble("anchorX"),tag.getDouble("anchorY"),tag.getDouble("anchorZ"),tag.getDouble("anchorYaw"),tag.getInt("viewer")==0);
        if(tag.getInt("attackEvent")>lastAttackEvent) {
            lastAttackEvent=tag.getInt("attackEvent");long now=System.currentTimeMillis();
            HologramActions.attack(tag.getInt("attackerUid"),now);HologramActions.attackDeclared(tag.getInt("targetUid"),now);
        }
        // Populate the original display cache used by the world renderer.
        ClientDuelAccess.mdpro$state(packet);
        dev.codex.yugiohfix.client.ClientLifePoints.onState(packet);
        dev.codex.yugiohfix.client.ClientDuelSounds.onState(packet);
        dev.codex.yugiohfix.client.DuelPuppetActions.onState(packet);
        for(var item:tag.getList("sounds",Tag.TAG_COMPOUND)) {
            var sound=(CompoundTag)item;
            if(sound.getInt("sequence")>lastSound) {
                lastSound=sound.getInt("sequence");
                queueSound(dev.codex.yugiohfix.sound.SoundCue.valueOf(sound.getString("cue")));
            }
        }
        drainSounds();
        if(mc.screen instanceof NativeDuelScreen screen) {screen.accept(tag,packet);return;}
        boolean reopen=tag.getBoolean("open") || fresh || mc.screen instanceof DuelScreen;
        // The player may have left with ESC. Come back as soon as the engine asks them something new,
        // so walking away from a pending request cannot strand them outside the duel UI.
        if(!reopen && mc.screen==null && tag.getBoolean("canAct") && tag.getInt("revision")!=dismissedRevision) reopen=true;
        if(reopen)mc.setScreen(new NativeDuelScreen(tag,packet));
    }
    public static void clear() {currentDuel=null;latest=null;ClientDuelAccess.mdpro$state(null);ClientBoardAnchor.clear();HologramActions.clear();pendingSounds.clear();}

    /**
     * MDPro3's cue scheduling in miniature: cues wait in a queue and leave at most one per beat
     * spacing, instead of every cue a beat carried firing on the same frame.
     */
    private static void queueSound(dev.codex.yugiohfix.sound.SoundCue cue) {
        if(cue==null)return;
        pendingSounds.addLast(cue);
        while(pendingSounds.size()>8)pendingSounds.removeFirst();
    }
    static void drainSounds() {
        if(pendingSounds.isEmpty())return;
        long now=System.currentTimeMillis();
        while(!pendingSounds.isEmpty() && now>=nextSoundAt) {
            dev.codex.yugiohfix.client.ClientSoundPlayer.play(pendingSounds.removeFirst());
            nextSoundAt=now+180;
            now=System.currentTimeMillis();
        }
    }

    /** Shown while the engine's NewTurn/NewPhase beat is holding the queue. */
    private void updateBanner(int turn,int phase) {
        boolean turnChanged=turn!=bannerTurn,phaseChanged=phase!=bannerPhase;
        if(!turnChanged && !phaseChanged)return;
        bannerTurn=turn;bannerPhase=phase;
        if(turnChanged) {
            bannerText="第 "+Math.max(1,turn)+" 回合 · "+DuelLayout.phaseName(phase);
            bannerUntil=System.currentTimeMillis()+900;
        } else {
            bannerText=DuelLayout.phaseName(phase);
            bannerUntil=System.currentTimeMillis()+600;
        }
    }

    private void accept(CompoundTag tag,S2CDuelStatePacket packet) {
        boolean changed=nativeState==null || !nativeState.getUUID("duel").equals(tag.getUUID("duel"))
                || nativeState.getInt("revision")!=tag.getInt("revision");
        nativeState=tag;board=packet;super.onStateUpdated(packet);
        // A server can resend an unchanged snapshot (open/sync, reconnect, or a duplicate
        // network packet).  Keeping sent=true for that revision prevents one physical click
        // from being submitted twice while the first answer is still in flight.
        if(changed) sent=false;
        int viewer=tag.getInt("viewer");
        pendulum.clear();pendulum.put(viewer,decodeCards(tag.getByteArray("selfpendulum")));pendulum.put(viewer^1,decodeCards(tag.getByteArray("foependulum")));
        removed.clear();removed.put(viewer,decodeCards(tag.getByteArray("selfremoved")));removed.put(viewer^1,decodeCards(tag.getByteArray("foeremoved")));
        model=new ChoiceModel(tag,viewer);
        if(!changed)return;
        updateBanner(model.turn,model.phase);
        // A new request invalidates every transient interaction of the previous one.
        selected.clear();checkedMaterials=null;menu=null;dialog=null;browser=null;confirmSurrender=false;dialogRevision=-1;menuUid=-1;
        if(minecraft!=null) {access().mdpro$hand(-1);access().mdpro$monster(-1);access().mdpro$spell(-1);}
        // receive() constructs a screen before Minecraft initializes its font/layout.
        // A first snapshot that is already a chain/effect prompt must wait for init().
        if(font==null)return;
        buildDialog();
        // A card request whose candidates are not all reachable on the board is answered from the list,
        // which mirrors MDPro3's own field-select versus popup split. A card-name declaration is a
        // two-step input-then-pick interaction, so it always uses the list.
        if(model.declareName()) openChoiceList();
        else if(model.cardPicking() && !allChoicesOnBoard()) openChoiceList();
        else if(hasIdlePileActions(viewer,DuelLayout.EXTRA)) openPileList(viewer,DuelLayout.EXTRA);
    }
    private static List<CardView> decodeCards(byte[] data) {
        if(data==null || data.length==0)return List.of();
        var buf=new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {int count=buf.readVarInt();List<CardView> result=new ArrayList<>(count);for(int i=0;i<count;i++)result.add(CardView.read(buf));return result;}
        finally {buf.release();}
    }

    @Override protected void init() {
        super.init();
        // A fixed virtual canvas keeps the board and its interaction column clear of both original
        // player portraits, which reserve 145px on each side of the virtual screen.
        int uiH=DuelLayout.BOARD_H+52+BAR_H+6;
        int uiW=DuelLayout.BOARD_W+DuelLayout.SIDE_GAP+SIDE_W+DuelLayout.PENDULUM_OUT;
        // The divisor grows with the interaction column so widening the side panel does not steal room
        // from the board: the virtual canvas stays ~750 wide whatever the window size.
        float scale=Math.min(1f,Math.min((width-8)/(720f+SIDE_W-86),(height-10)/(uiH+14f)));
        var a=access();a.mdpro$scale(scale);
        int virtualWidth=Math.round(width/scale),virtualHeight=Math.round(height/scale);
        a.mdpro$left((virtualWidth-uiW)/2+DuelLayout.PENDULUM_OUT);
        a.mdpro$top(Math.max(6,(virtualHeight-uiH)/2));
        search=new EditBox(font,0,0,150,BAR_H,Component.literal("输入卡名或卡号"));
        search.setMaxLength(64);
        if(model!=null) {
            buildDialog();
            if(browser==null && (model.declareName() || model.cardPicking() && !allChoicesOnBoard()))openChoiceList();
        }
    }

    private DuelLayout layout() {
        var a=access();
        DuelLayout result=new DuelLayout(a.mdpro$left(),a.mdpro$top(),a.mdpro$boardH());
        result.handCount(board.self().hand().size());
        return result;
    }

    /**
     * ESC leaves the duel interface without leaving the duel: the server keeps playing, the world
     * keeps rendering the board, and the interface returns when the engine next needs an answer (or
     * when the player uses the duel table again, which sends an open request).
     */
    @Override public boolean shouldCloseOnEsc() {return true;}

    @Override public void onClose() {
        dismissedRevision=nativeState==null?-1:nativeState.getInt("revision");
        // Silently: the exit is a keyboard convenience, not something worth a line in the chat box.
        Minecraft.getInstance().setScreen(null);
    }
    private List<CardView> pendulumOf(int player) {return pendulum.getOrDefault(player,List.of());}
    private List<CardView> removedOf(int player) {return removed.getOrDefault(player,List.of());}
    private int viewerSide() {return board.viewerSide();}

    // ------------------------------------------------------------------ answering the engine

    private boolean canAct() {return !sent && model!=null && model.canAct;}
    private void send(int[] indices,boolean cancel,String text,boolean surrender) {
        if(sent)return;sent=true;
        if(testAnswers!=null) {testAnswers.accept(Arrays.toString(indices)+(cancel?"|cancel":"")+(surrender?"|surrender":"")+(text.isEmpty()?"":"|"+text));return;}
        if(model!=null && model.counters() && !cancel && !surrender) {
            int[] counts=new int[model.choices.size()];for(int i:indices)counts[i]++;
            indices=counts;text="counters";
        }
        DuelNetwork.send(new DuelNetwork.Action(nativeState.getUUID("duel"),nativeState.getInt("revision"),indices,cancel,text,surrender));
    }
    public String hint() {return "";}

    private void choose(int index) {
        if(!canAct())return;
        Choice choice=model.byIndex(index);
        if(choice==null)return;
        if(model.declareName()) {send(new int[]{index},false,Integer.toString(choice.code),false);return;}
        if(model.multiple) {toggle(index);return;}
        send(new int[]{index},false,"",false);
    }
    private void confirm() {
        if(!canAct() || !confirmable())return;
        send(selected.stream().mapToInt(Integer::intValue).toArray(),false,"",false);
    }
    private void cancel() {
        if(!canAct() || !model.cancelAllowed())return;
        send(new int[0],true,"",false);
    }
    private void surrender() {
        if(model==null || sent)return;
        if(nativeState.getBoolean("surrenderLocked"))return;
        if(confirmSurrender)send(new int[0],false,"",true);else confirmSurrender=true;
    }
    private int indexOfValue(int value) {
        for(Choice choice:model.choices) if(choice.value==value) return choice.index;
        return -1;
    }
    private int countOf(int index) {
        int count=0;for(int value:selected) if(value==index) count++;return count;
    }
    /** Toggles or increments one entry, then submits when the engine's condition is already satisfied. */
    private void toggle(int index) {
        if(!canAct())return;
        if(index<model.forced())return;                 // mandatory material, already counted by the engine
        Choice choice=model.byIndex(index);if(choice==null)return;
        if(model.counters()) {
            int capacity=Math.max(0,choice.weight);
            if(countOf(index)>=capacity)return;
            selected.add(index);
            if(selected.size()==model.target) {send(selected.stream().mapToInt(Integer::intValue).toArray(),false,"",false);return;}
            if(selected.size()>model.target) selected.remove(selected.size()-1);
            return;
        }
        if(selected.contains(index)) selected.remove(Integer.valueOf(index)); else selected.add(index);
        autoSubmit();
    }
    private void autoSubmit() {
        if(sent || !canAct())return;
        if(model.materials()) {if(exactSum())confirm();return;}
        if(model.sort()) {if(selected.size()==model.max)confirm();return;}
        if(model.unselect())return;                     // select or unselect must stay an explicit choice
        // Only a fixed count is submitted for the player; a range keeps its confirm button.
        if(model.min==model.max && selected.size()==model.min)confirm();
    }
    private boolean confirmable() {
        if(sent || !canAct())return false;
        int count=selected.size();
        return switch(model.kind) {
            case 15,26 -> count>=model.min && count<=model.max;
            case 20 -> count<=model.max && levelSum()>=model.min;
            case 22 -> count==model.target;
            case 23 -> sumReachable();
            case 25 -> count==model.max;
            default -> !selected.isEmpty();
        };
    }
    /** Sum of the primary material values, including the engine's mandatory entries. */
    private int levelSum() {
        int sum=0;
        for(int i=0;i<model.forced();i++) {Choice choice=model.byIndex(i);if(choice!=null)sum+=choice.low();}
        for(int index:selected) {Choice choice=model.byIndex(index);if(choice!=null)sum+=choice.low();}
        return sum;
    }
    private boolean exactSum() {
        if(selected.equals(checkedMaterials))return materialValid;
        checkedMaterials=List.copyOf(selected);materialValid=false;
        if(selected.size()<model.min || selected.size()>model.max)return false;
        int[] weights=new int[model.forced()+selected.size()];
        for(int i=0;i<model.forced();i++)weights[i]=model.byIndex(i).weight;
        for(int i=0;i<selected.size();i++)weights[model.forced()+i]=model.byIndex(selected.get(i)).weight;
        materialValid=MaterialSelection.valid(weights,model.target,model.mode);return materialValid;
    }
    private boolean sumReachable() {return exactSum();}
    private int reachableLow() {
        int sum=0;
        for(int i=0;i<model.forced();i++) {Choice c=model.byIndex(i);if(c!=null)sum+=c.low();}
        for(int index:selected) {Choice c=model.byIndex(index);if(c!=null)sum+=c.low();}
        return sum;
    }
    private int reachableHigh() {
        int sum=0;
        for(int i=0;i<model.forced();i++) {Choice c=model.byIndex(i);if(c!=null)sum+=c.high()!=0?c.high():c.low();}
        for(int index:selected) {Choice c=model.byIndex(index);if(c!=null)sum+=c.high()!=0?c.high():c.low();}
        return sum;
    }

    // ------------------------------------------------------------------ board access

    private SideView side(int player) {return player==viewerSide()?board.self():board.opponent();}
    private static CardView at(List<CardView> list,int index) {return index>=0 && index<list.size()?list.get(index):CardView.EMPTY;}
    private CardView cardAt(int player,int location,int sequence) {
        SideView side=side(player);
        return switch(location) {
            case DuelLayout.MZONE -> at(side.monsters(),sequence);
            case DuelLayout.SZONE -> sequence<5?at(side.spells(),sequence):sequence==5?side.field():at(pendulumOf(player),sequence-6);
            case DuelLayout.HAND -> at(side.hand(),sequence);
            case DuelLayout.GRAVE -> side.graveyard().isEmpty()?CardView.EMPTY:side.graveyard().get(side.graveyard().size()-1);
            case DuelLayout.EXTRA -> at(side.extraDeck(),0);
            default -> CardView.EMPTY;
        };
    }
    /** Builds a display-only card for an engine code, used by dialogs and menus. */
    private CardView cardOf(int code) {
        if(code==0)return CardView.EMPTY;
        String id=DuelCatalog.legacyId(code);
        if(id.isEmpty())return CardView.EMPTY;
        CardDefinition definition=Cards.byId(id);
        if(definition==null)return new CardView(true,id,-1,CardPosition.FACE_UP,0,0,false,false,false,CardRarity.COMMON);
        return new CardView(true,id,-1,CardPosition.FACE_UP,definition.isMonster()?definition.getAtk():0,
                definition.isMonster()?definition.getDef():0,false,false,false,CardRarity.COMMON);
    }
    private record Cell(int player,int location,int sequence) {}    private List<Cell> cells() {
        List<Cell> result=new ArrayList<>(34);
        for(int relative=0;relative<2;relative++) {
            int player=viewerSide()^relative;
            for(int sequence=0;sequence<5;sequence++)result.add(new Cell(player,DuelLayout.MZONE,sequence));
            for(int sequence=0;sequence<5;sequence++)result.add(new Cell(player,DuelLayout.SZONE,sequence));
            result.add(new Cell(player,DuelLayout.SZONE,5));
            result.add(new Cell(player,DuelLayout.SZONE,6));
            result.add(new Cell(player,DuelLayout.SZONE,7));
            result.add(new Cell(player,DuelLayout.GRAVE,0));
            result.add(new Cell(player,DuelLayout.EXTRA,0));
            result.add(new Cell(player,DuelLayout.DECK,0));
        }
        return result;
    }
    private DuelLayout.Rect rectOf(DuelLayout layout,Cell cell) {return layout.zone(viewerSide(),cell.player(),cell.location(),cell.sequence());}
    private DuelUi.CardPainter painter() {return (g,x,y,w,h,card)->access().mdpro$drawCard(g,x,y,w,h,card);}
    /** Kernel code of a display card, or 0 when the card is unknown or has no verified mapping. */
    private static int codeOf(CardView card) {
        if(card==null || !card.isKnown())return 0;
        try {return DuelCatalog.code(card.cardId());} catch(RuntimeException unmapped) {return 0;}
    }
    /** Printed card text, or empty when the card is outside the imported catalog. */
    private static String textOf(CardDefinition definition) {
        if(definition==null)return "";
        try {return DuelCatalog.text(DuelCatalog.code(definition.getId()));} catch(RuntimeException unmapped) {return "";}
    }

    // ------------------------------------------------------------------ deciding what a click means

    private boolean picking() {return model.cardPicking();}
    private boolean allChoicesOnBoard() {
        boolean any=false;
        for(Choice choice:model.choices) {
            if(!choice.isCard())continue;
            any=true;
            if(!onBoard(choice.player,choice.location,choice.sequence))return false;
        }
        return any;
    }
    private boolean onBoard(int player,int location,int sequence) {
        if(location==DuelLayout.MZONE || location==DuelLayout.SZONE)return true;
        return location==DuelLayout.HAND && player==viewerSide();
    }
    private void cellClick(Cell cell) {
        dev.codex.yugiohfix.client.ClientSoundPlayer.play(dev.codex.yugiohfix.sound.SoundCue.CARD_SELECT);
        if(picking()) {toggleAt(cell);return;}
        // An extra-deck summon is represented by SELECT_IDLECMD candidates.  The board cell only
        // shows the top card, so open the complete pile before trying to resolve a cell-local menu.
        if(cell.location()==DuelLayout.EXTRA && hasIdlePileActions(cell.player(),DuelLayout.EXTRA)) {
            openPileList(cell.player(),DuelLayout.EXTRA);return;
        }
        List<Choice> choices=model.at(cell.player(),cell.location(),cell.sequence());
        // A placement request is answered by the zone itself, which is what MDPro3 highlights.
        if(canAct() && model.place() && !choices.isEmpty()) {choose(choices.get(0).index);return;}
        if(canAct() && !choices.isEmpty() && (model.idle() || model.chain())) {openMenu(cell,choices);return;}
        if(cell.location()==DuelLayout.GRAVE || cell.location()==DuelLayout.EXTRA || cell.location()==DuelLayout.DECK || cell.location()==DuelLayout.REMOVED) {
            openPileList(cell.player(),cell.location());return;
        }
        showDetail(cell);
    }
    private void toggleAt(Cell cell) {
        List<Choice> here=model.at(cell.player(),cell.location(),cell.sequence());
        if(here.isEmpty()) {showDetail(cell);return;}
        // SELECT_UNSELECT_CARD is one operation per packet, including when picked on the board.
        if(!model.multiple) {choose(here.get(0).index);return;}
        // Clicking an already chosen card removes it again, which is the same toggle MDPro3 uses.
        for(Choice choice:here) if(countOf(choice.index)>0 && !model.counters()) {toggle(choice.index);return;}
        for(Choice choice:here) if(countOf(choice.index)==0) {toggle(choice.index);return;}
        toggle(here.get(0).index);
    }
    private void showDetail(Cell cell) {
        zoomPlayer=cell.player();zoomLocation=cell.location();zoomSequence=cell.sequence();
        zoom=cardAt(cell.player(),cell.location(),cell.sequence());
    }
    /**
     * Actions the current request allows for one card, matched by card code. The pile lists only hold
     * card views, so the code is what links them back to the choices the engine offered.
     */
    private List<Choice> actionsForCard(CardView card,int player,int location,int sequence) {
        int code=codeOf(card);
        if(code==0 || !canAct())return List.of();
        List<Choice> found=new ArrayList<>();
        for(Choice choice:model.choices)
            if(choice.code==code && choice.isCard() && choice.player==player
                    && choice.location==location && choice.sequence==sequence) found.add(choice);
        // A displayed pile can lose sequence metadata during a visibility transition.  Keep a
        // code-and-zone fallback for that case, but prefer the exact native sequence so duplicate
        // extra-deck copies never activate the first matching copy.
        if(found.isEmpty()) for(Choice choice:model.choices)
            if(choice.code==code && choice.isCard() && choice.player==player && choice.location==location)
                found.add(choice);
        return found;
    }
    private void openMenu(Cell cell,List<Choice> choices) {
        List<Choice> sorted=new ArrayList<>(choices);
        sorted.sort(Comparator.comparingInt(choice->actionRank(choice)));
        List<DuelWidgets.Item> items=new ArrayList<>();
        for(Choice choice:sorted) {
            String label=choice.note.isEmpty()?"选择":choice.note;
            items.add(new DuelWidgets.Item(choice.index,choice.badge(),label,choice.detail,true,actionTint(choice)));
        }
        menuPlayer=cell.player();menuLocation=cell.location();menuSequence=cell.sequence();
        menuUid=cardAt(cell.player(),cell.location(),cell.sequence()).uid();
        menu=new DuelWidgets.PopupMenu(cardNameAt(cell),items,cardAt(cell.player(),cell.location(),cell.sequence()));
        DuelUi.CardPainter painter=painter();
        DuelLayout layout=layout();
        DuelLayout.Rect anchor=rectOf(layout,cell);
        if(anchor==null)anchor=new DuelLayout.Rect(layout.left(),layout.top()+100,30,44);
        menu.layout(font,anchor,layout.left()-40,layout.top()-4,DuelLayout.BOARD_W+DuelLayout.SIDE_GAP+SIDE_W+80,DuelLayout.BOARD_H+80);
        zoom=null;
    }
    private String cardNameAt(Cell cell) {
        CardView view=cardAt(cell.player(),cell.location(),cell.sequence());
        if(view.present() && view.isKnown())return ChoiceModel.describeCard(view);
        List<Choice> here=model.at(cell.player(),cell.location(),cell.sequence());
        return here.isEmpty()?"卡片":here.get(0).name;
    }
    /** MDPro3 sorts a card's buttons by ButtonType; this is the same order for engine verb groups. */
    private int actionRank(Choice choice) {
        if(model.kind==10)return switch(choice.action) {case 0->0;case 1->2;default->6;};
        return switch(choice.action) {case 5->0;case 2->3;case 1->5;case 0->6;case 4->8;case 3->9;default->7;};
    }
    private int actionTint(Choice choice) {
        boolean strong=model.kind==10?choice.action==0:(choice.action==5 || choice.action==1);
        return strong?0xFFB4791E:0xFF25608C;
    }

    // ------------------------------------------------------------------ dialogs

    private void buildDialog() {
        dialog=null;
        if(!model.canAct)return;
        switch(model.kind) {
            case 12,13 -> {
                int code=model.choices.isEmpty()?0:model.choices.get(0).code;
                int yes=indexOfValue(1),no=indexOfValue(0);
                String title=model.kind==12?"是否发动效果？":"请选择";
                if(model.kind==13)title=model.prompt.isEmpty()?"请选择":model.prompt;
                String body=model.choices.isEmpty()?"":model.choices.get(0).name;
                if(model.kind==12 && !model.choices.isEmpty() && !model.choices.get(0).detail.isEmpty())body=model.choices.get(0).detail;
                DuelWidgets.Dialog built=new DuelWidgets.Dialog(title,body);
                if(code!=0)built.preview();
                if(yes>=0)built.button(model.kind==12?"发动":"是",true,true,()->choose(yes));
                if(no>=0)built.button(model.kind==12?"不发动":"否",true,false,this::cancel);
                built.layout(font,layout().left()+114,layout().top()+DuelLayout.BOARD_H/2,236,DuelLayout.BOARD_H);
                dialog=built;dialogRevision=model.kind;
            }
            case 14,143 -> {
                DuelWidgets.Dialog built=new DuelWidgets.Dialog(model.prompt,"");
                for(Choice choice:model.choices) built.option(choice.index,badgeOf(choice.optionText()),
                        choice.optionText(),choice.detail,true,0xFF25608C);
                built.rows().layout(font,layout().left()+114,layout().top()+DuelLayout.BOARD_H/2,236,DuelLayout.BOARD_H);
                dialog=built;dialogRevision=model.kind;
            }
            case 140,141 -> {
                DuelWidgets.Dialog built=new DuelWidgets.Dialog(model.prompt,"请选择 "+model.min+" 项；选满自动确认，再次点击可撤选");
                for(Choice choice:model.choices) built.option(choice.index,badgeOf(choice.optionText()),
                        choice.optionText(),true,0xFF25608C);
                built.selected(selected::contains);
                built.grid().layout(font,layout().left()+114,layout().top()+DuelLayout.BOARD_H/2,236,DuelLayout.BOARD_H);
                dialog=built;dialogRevision=model.kind;
            }
            case 16 -> {
                int code=model.choices.isEmpty()?0:model.choices.get(0).code;
                DuelWidgets.Dialog built=new DuelWidgets.Dialog(model.prompt,chainSummary());
                if(model.choices.size()==1 && code!=0)built.preview();
                for(Choice choice:model.choices)
                    built.option(choice.index,"链",choice.name,choice.detail,true,0xFF6B3F8F);
                built.rows();
                if(model.cancelable)built.button("不连锁",true,false,this::cancel);
                built.layout(font,layout().left()+114,layout().top()+DuelLayout.BOARD_H/2,240,DuelLayout.BOARD_H+40);
                zoom=null;
                dialog=built;dialogRevision=model.kind;
            }
            case 19 -> {
                int code=model.choices.isEmpty()?0:model.choices.get(0).code;
                DuelWidgets.Dialog built=new DuelWidgets.Dialog(model.prompt,"选择表侧或里侧、攻击或守备");
                if(code!=0)built.preview();
                // Rows, not a grid: "表侧攻击表示" never fits inside a quarter-width button.
                for(Choice choice:model.choices) built.option(choice.index,"",
                        choice.optionText(),true,0xFF25608C);
                built.rows().layout(font,layout().left()+114,layout().top()+DuelLayout.BOARD_H/2,236,DuelLayout.BOARD_H);
                dialog=built;dialogRevision=model.kind;
            }
            case 132 -> {
                DuelWidgets.Dialog built=new DuelWidgets.Dialog("猜拳","获胜方决定先攻");
                for(Choice choice:model.choices) built.option(choice.index,"",choice.optionText(),true,0xFF25608C);
                built.layout(font,layout().left()+114,layout().top()+DuelLayout.BOARD_H/2,220,DuelLayout.BOARD_H);
                dialog=built;dialogRevision=model.kind;
            }
            default -> {}
        }
    }
    /** Single-character badge for an option row; the row text carries the full meaning. */
    private static String badgeOf(String text) { return text.isEmpty()?"选":text.substring(0,1); }
    private String chainSummary() {
        if(model.chain.isEmpty())return "还没有已发动的效果";
        StringBuilder text=new StringBuilder("当前连锁 ");
        text.append(model.chain.size()).append(" 张：");
        List<Integer> codes=model.chain;
        for(int i=0;i<codes.size();i++) {
            if(i>0)text.append(" → ");
            text.append(DuelCatalog.name(codes.get(i)));
        }
        return text.toString();
    }
    private CardView dialogCard() {
        if(model==null || model.choices.isEmpty())return CardView.EMPTY;
        for(Choice choice:model.choices) if(choice.code!=0) return cardOf(choice.code);
        return CardView.EMPTY;
    }
    private void cancelDialog() {
        // ESC/right-click must decline effect yes/no as well as optional chain selection.
        // Mandatory chains and choices without a negative answer stay open.
        if(model.yesNo() || model.chain() && model.cancelable)cancel();
    }

    // ------------------------------------------------------------------ card list (browser)

    /** Grid list of cards, used both for browsing a pile and for answering a card request. */
    private final class Browser {
        final boolean pick;
        int player=-1,location=0;
        String title;
        final List<Choice> choices=new ArrayList<>();
        final List<CardView> cards=new ArrayList<>();
        int pageCount;
        Browser(boolean pick) {this.pick=pick;}
    }
    private void openChoiceList() {
        Browser next=new Browser(true);
        next.title=model.prompt.isEmpty()?"选择卡片":model.prompt;
        // A declared card name is not a board position, so every search result is a row.
        for(Choice choice:model.choices) if(choice.isCard() || model.declareName())next.choices.add(choice);
        open(next);
    }
    /** Idle special-summon candidates in a pile need the same list flow as MDPro3. */
    private boolean hasIdlePileActions(int player,int location) {
        if(model==null || !model.idle())return false;
        for(Choice choice:model.choices)
            if(choice.isCard() && choice.player==player && choice.location==location)return true;
        return false;
    }
    private void openPileList(int player,int location) {
        Browser next=new Browser(false);
        next.player=player;next.location=location;
        next.title=(player==viewerSide()?"自己 ":"对方 ")+DuelLayout.locationName(location);
        SideView side=side(player);
        if(location==DuelLayout.GRAVE)next.cards.addAll(side.graveyard());
        else if(location==DuelLayout.EXTRA)next.cards.addAll(side.extraDeck());
        else if(location==DuelLayout.REMOVED)next.cards.addAll(removedOf(player));
        else next.cards.addAll(side.hand());
        if(next.cards.isEmpty()) {toast="这里没有卡片";toastTicks=80;return;}
        open(next);
    }
    private void open(Browser next) {
        browser=next;page=0;
        // The name declaration keeps its query so the player can refine it after a search returns.
        searchForServer=model.declareName();
        searchActive=true;
        menu=null;zoom=null;
        if(search!=null && !searchForServer)search.setValue("");
    }
    private void closeBrowser() {browser=null;searchActive=false;}
    private int browserSequence(CardView card,int shownIndex) {
        if(browser==null)return shownIndex;
        // With no filter the rendered slot is the native pile sequence.  Using List.indexOf here
        // is incorrect for duplicate extra-deck copies because equal CardView records resolve to
        // the first copy; preserve the clicked slot instead.
        if(searchText().isEmpty())return shownIndex;
        // When a text filter hides cards, translate the visible ordinal back to the original pile
        // index without comparing card values (duplicate cards may be equal records).
        int visible=0;
        String query=searchText().toLowerCase(Locale.ROOT);
        for(int i=0;i<browser.cards.size();i++) {
            CardView candidate=browser.cards.get(i);
            String name=ChoiceModel.describeCard(candidate);
            if(!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query))continue;
            if(visible++==shownIndex)return i;
        }
        return shownIndex;
    }

    // ------------------------------------------------------------------ rendering

    /**
     * The duel log no longer sits in the top-left corner: that corner is the card detail panel now, as
     * in MDPro3. The log is drawn inside the right-hand column instead (see {@link #drawLogBlock}).
     */
    public void renderNativeLog(GuiGraphics g) {}

    /**
     * Last few log entries, wrapped and drawn above the log toggle in the right column. Entries wrap
     * rather than being cut off: "回合 1：Duel Bot" has to stay readable.
     */
    private void drawLogBlock(GuiGraphics g,DuelLayout layout,int bottom,int x,int width) {
        if(!showLog || board==null || board.log().isEmpty())return;
        List<net.minecraft.network.chat.Component> log=board.log();
        int inner=width-10;
        List<String> lines=new ArrayList<>();
        for(int i=Math.max(0,log.size()-6);i<log.size();i++)
            lines.addAll(DuelUi.wrap(font,log.get(i).getString(),inner,2));
        while(lines.size()>9)lines.remove(0);
        int height=13+lines.size()*10+2;
        int top=bottom-height;
        DuelUi.plate(g,x+2,top,width-4,height,0xA80E1620,DuelUi.PANEL_EDGE);
        DuelUi.label(g,font,x+5,top+3,inner,"日志",DuelUi.ACCENT);
        for(int i=0;i<lines.size();i++) {
            DuelUi.label(g,font,x+5,top+14+i*10,inner,lines.get(i),0xB4C4D7);
        }
    }

    public void renderActions(GuiGraphics g,int mx,int my) {
        controls.clear();
        if(board==null || model==null)return;
        DuelLayout layout=layout();
        boolean modal=browser!=null || dialog!=null;
        // While the pile list covers the board, only the list decides what is hovered: the cells under
        // the overlay used to answer for it and put an unrelated board card into the tooltip.
        hover=fixtureHover!=null?fixtureHover:(browser==null?hoveredCard(layout,mx,my):null);
        drawHighlights(g,layout);
        drawExtraZones(g,layout,mx,my,modal);
        drawZoneCells(g,layout,mx,my,modal);
        drawHandCells(g,layout,mx,my,modal);
        drawSidePanel(g,layout,mx,my,modal);
        drawBottomBar(g,layout,mx,my,modal);
    }
    /** Card under the pointer, including the viewer's own hand, which is drawn outside the cells list. */
    private CardView hoveredCard(DuelLayout layout,int mx,int my) {
        for(Cell cell:cells()) {
            DuelLayout.Rect rect=rectOf(layout,cell);
            if(rect==null || !rect.contains(mx,my))continue;
            // A pile is a stack, not a card. Hovering one used to name and print the card on top of it
            // (the extra deck printed whichever card happened to be first); every pile now keeps its
            // contents hidden until the player opens the list.
            if(pile(cell.location()))continue;
            CardView view=cardAt(cell.player(),cell.location(),cell.sequence());
            if(view.present()) {hoverRect=rect;return view;}
        }
        // Hand cards are the cards a player inspects most, and they must show their text on hover too,
        // not only once a summon or activation has been picked.
        List<CardView> hand=board.self().hand();
        for(int i=hand.size()-1;i>=0;i--) {
            DuelLayout.Rect rect=layout.handSlot(i,hand.size());
            if(rect.contains(mx,my) && hand.get(i).present()) {hoverRect=rect;return hand.get(i);}
        }
        return null;
    }
    /** Deck, graveyard, banished and extra deck are stacks: a hover must not name the card on top. */
    private static boolean pile(int location) {
        return location==DuelLayout.DECK || location==DuelLayout.GRAVE
                || location==DuelLayout.REMOVED || location==DuelLayout.EXTRA;
    }
    /** Yellow marks "you may activate or special summon this", blue "this card is selectable". */
    private void drawHighlights(GuiGraphics g,DuelLayout layout) {
        if(!canAct())return;
        for(Choice choice:model.choices) {
            if(!choice.isCard())continue;
            DuelLayout.Rect rect=rectOf(layout,new Cell(choice.player,choice.location,choice.sequence));
            if(rect==null)continue;
            boolean chosen=selected.contains(choice.index) || choice.index<model.forced();
            int color;
            if(picking())color=chosen?DuelUi.SELECT:DuelUi.CANDIDATE;
            else if(model.chain())color=DuelUi.CHAIN;
            else if(model.idle())color=isStrong(choice)?0xFFFFD34D:0xFF6FC8FF;
            else color=DuelUi.CANDIDATE;
            if(model.place() && !cardAt(choice.player,choice.location,choice.sequence).present()) {
                DuelUi.focus(g,rect.x(),rect.y(),rect.w(),rect.h(),chosen?DuelUi.SELECT:DuelUi.CANDIDATE,true);
                g.drawCenteredString(font,chosen?Integer.toString(selected.indexOf(choice.index)+1):model.kind==24?"禁":"放",rect.cx(),rect.cy()-4,0xFF0E2418);
                continue;
            }
            DuelUi.focus(g,rect.x(),rect.y(),rect.w(),rect.h(),color,chosen);
            if(picking() && chosen) {
                int order=selected.indexOf(choice.index)+1;
                g.fill(rect.x()+rect.w()-13,rect.y(),rect.x()+rect.w(),rect.y()+11,0xFF000000);
                g.drawString(font,choice.index<model.forced()?"必":Integer.toString(order),rect.x()+rect.w()-11,rect.y()+2,0xFFFFD34D,false);
            }
            if(model.counters() && countOf(choice.index)>0) {
                g.fill(rect.x(),rect.y(),rect.x()+14,rect.y()+11,0xFF000000);
                g.drawString(font,"x"+countOf(choice.index),rect.x()+1,rect.y()+2,0xFF63D2A0,false);
            }
        }
    }
    private boolean isStrong(Choice choice) {
        return model.kind==10?choice.action==0:(choice.action==5 || choice.action==1);
    }
    /** MR3 keeps two independent pendulum zones and a removed pile outside the original board. */
    private void drawExtraZones(GuiGraphics g,DuelLayout layout,int mx,int my,boolean modal) {
        for(int relative=0;relative<2;relative++) {
            int player=viewerSide()^relative;
            List<CardView> cards=pendulumOf(player);
            for(int sequence=6;sequence<=7;sequence++) {
                DuelLayout.Rect rect=layout.pendulumSlot(player==viewerSide(),sequence);
                g.fill(rect.x(),rect.y(),rect.x()+rect.w(),rect.y()+rect.h(),0x882C4E60);
                g.renderOutline(rect.x(),rect.y(),rect.w(),rect.h(),0xFF60A3B9);
                CardView view=at(cards,sequence-6);
                if(view.present())painter().draw(g,rect.x(),rect.y(),rect.w(),rect.h(),view);
                else g.drawCenteredString(font,"P"+(sequence-5),rect.cx(),rect.y()+rect.h()/2-4,0xA0CEDB);
                final int pendulumSequence=sequence;
                if(!modal)controls.add(new DuelUi.Control(rect.x(),rect.y(),rect.w(),rect.h(),"灵摆",()->cellClick(new Cell(player,DuelLayout.SZONE,pendulumSequence)),true));
            }
            DuelLayout.Rect slot=layout.removedSlot(player==viewerSide());
            List<CardView> pile=removedOf(player);
            boolean hover=slot.contains(mx,my);
            DuelUi.button(g,font,slot.x(),slot.y(),slot.w(),slot.h(),"除外 "+pile.size(),!modal,hover);
            if(!modal)controls.add(new DuelUi.Control(slot.x(),slot.y(),slot.w(),slot.h(),"除外",()->openPileList(player,DuelLayout.REMOVED),true));
        }
    }
    /** Every card-area cell is clickable, so pointing at a card is enough to open its menu. */
    private void drawZoneCells(GuiGraphics g,DuelLayout layout,int mx,int my,boolean modal) {
        for(Cell cell:cells()) {
            if(cell.location()==DuelLayout.HAND)continue;
            DuelLayout.Rect rect=rectOf(layout,cell);
            if(rect==null)continue;
            boolean hover=rect.contains(mx,my) && cardAt(cell.player(),cell.location(),cell.sequence()).present();
            if(hover)g.fill(rect.x(),rect.y(),rect.x()+rect.w(),rect.y()+rect.h(),0x30FFFFFF);
            if(!modal)controls.add(new DuelUi.Control(rect.x(),rect.y(),rect.w(),rect.h(),(cell.player()==viewerSide()?"自 ":"对 ")+DuelLayout.locationName(cell.location())+" "+(cell.sequence()+1),()->cellClick(cell),true));
        }
    }
    private void drawHandCells(GuiGraphics g,DuelLayout layout,int mx,int my,boolean modal) {
        List<CardView> hand=board.self().hand();
        for(int i=0;i<hand.size();i++) {
            DuelLayout.Rect rect=layout.handSlot(i,hand.size());
            boolean chosen=false;
            for(Choice choice:model.at(viewerSide(),DuelLayout.HAND,i)) if(selected.contains(choice.index))chosen=true;
            if(chosen)DuelUi.focus(g,rect.x(),rect.y()+4,rect.w(),rect.h()-4,DuelUi.SELECT,false);
            if(rect.contains(mx,my))g.fill(rect.x(),rect.y(),rect.x()+rect.w(),rect.y()+rect.h(),0x30FFFFFF);
            final int handIndex=i;
            if(!modal)controls.add(new DuelUi.Control(rect.x(),rect.y(),rect.w(),rect.h(),"手牌 "+(handIndex+1),()->cellClick(new Cell(viewerSide(),DuelLayout.HAND,handIndex)),true));
        }
    }
    private void drawSidePanel(GuiGraphics g,DuelLayout layout,int mx,int my,boolean modal) {
        int x=layout.sideX(),y=layout.top(),w=SIDE_W;
        int height=layout.barTop()-layout.top();
        DuelUi.plate(g,x,y,w,height,0xF0121C2C,DuelUi.PANEL_EDGE);
        DuelUi.heading(g,font,x,y,w,"第"+Math.max(1,model.turn)+"回合 "+DuelLayout.phaseShort(model.phase));
        y+=16;
        int[] phases={1,2,4,8,256,512};
        for(int phase:phases) {
            Choice command=phaseCommand(phase);
            boolean current=phase==8?model.phase>=8 && model.phase<=128:model.phase==phase;
            boolean enabled=command!=null && canAct() && !modal;
            int fill=current?0xFFE1A64C:enabled?DuelUi.BTN:0xFF16202E;
            boolean hover=enabled && DuelUi.inside(mx,my,x+2,y,w-4,13);
            g.fill(x+2,y,x+w-2,y+13,fill);
            DuelUi.box(g,x+2,y,x+w-2,y+13,current?0xFF8A6A24:enabled?DuelUi.BTN_EDGE:0xFF2B3646);
            // The arrow marks a phase the engine is actually offering right now.
            String label=DuelLayout.phaseShort(phase)+(enabled?" ▶":"");
            g.drawCenteredString(font,label,x+w/2,y+3,current?0xFF20180A:enabled?DuelUi.TEXT:0xFF7A8798);
            if(enabled) {
                final int index=command.index;
                controls.add(new DuelUi.Control(x+2,y,w-4,13,DuelLayout.phaseShort(phase),()->choose(index),true));
            }
            y+=14;
        }
        y+=4;
        String notice=model.notice.isEmpty()?model.prompt:model.notice;
        List<String> lines=DuelUi.wrap(font,notice,w-8,5);
        for(String line:lines) {DuelUi.label(g,font,x+4,y,w-8,line,model.notice.isEmpty()?DuelUi.TEXT:DuelUi.ACCENT);y+=10;}
        if(model.multiple && !model.place()) {
            y+=2;
            String counter=selectionSummary();
            DuelUi.label(g,font,x+4,y,w-8,counter,DuelUi.GOOD);y+=11;
        }
        if(model.chain() && !model.chain.isEmpty()) {
            y+=2;DuelUi.label(g,font,x+4,y,w-8,"连锁链",DuelUi.CHAIN);y+=11;
            for(int i=0;i<model.chain.size() && y<layout.barTop()-28;i++) {
                String name=DuelCatalog.name(model.chain.get(i));
                DuelUi.label(g,font,x+4,y,w-8,"C"+(i+1)+" "+name,DuelUi.CHAIN);y+=10;
            }
        }
        if(toastTicks>0) {DuelUi.label(g,font,x+4,y,w-8,toast,DuelUi.ACCENT);y+=10;}
        int buttonY=layout.barTop()-18;
        boolean locked=nativeState.getBoolean("surrenderLocked");
        boolean surrenderHover=DuelUi.inside(mx,my,x+3,buttonY,w-6,BAR_H-2);
        DuelUi.button(g,font,x+3,buttonY,w-6,BAR_H-2,locked?"禁止投降":confirmSurrender?"确认投降":"投降",!locked && !modal,surrenderHover);
        if(!locked && !modal)controls.add(new DuelUi.Control(x+3,buttonY,w-6,BAR_H-2,"投降",this::surrender,true));
        // The log lives here, above its own toggle, and the card detail keeps the top-left corner.
        drawLogBlock(g,layout,buttonY-42,x,w);
        boolean logHover=DuelUi.inside(mx,my,x+3,buttonY-18,w-6,14);
        DuelUi.button(g,font,x+3,buttonY-18,w-6,14,showLog?"日志 开":"日志 关",!modal,logHover);
        if(!modal)controls.add(new DuelUi.Control(x+3,buttonY-18,w-6,14,"日志",()->showLog=!showLog,true));
    }
    private Choice phaseCommand(int phase) {
        return switch(phase) {
            case 8 -> model.command(6);
            case 256 -> model.command(2);
            case 512 -> model.command(3,7);
            default -> null;
        };
    }
    private String selectionSummary() {
        if(model.counters())return "指示物 "+selected.size()+" / "+model.target;
        if(model.materials())return "等级合计 "+reachableLow()+" / "+model.target;
        if(model.kind==20)return "解放值 "+levelSum()+" / "+model.min+"，最多 "+model.max+" 张";
        return "已选 "+selected.size()+" / "+model.min+"～"+model.max;
    }
    private void drawBottomBar(GuiGraphics g,DuelLayout layout,int mx,int my,boolean modal) {
        int x=layout.left()-6,y=layout.barTop(),w=DuelLayout.BOARD_W+12+SIDE_W+DuelLayout.SIDE_GAP;
        DuelUi.plate(g,x,y,w,BAR_H,0xF0162438,DuelUi.PANEL_EDGE);
        String status;
        if(model.canAct) {
            if(model.place())status=model.kind==24?"请选择要禁用的区域":"请选择放置区域";
            else if(picking())status=selectionSummary();
            else if(model.idle())status="点击卡片选择行动";
            else if(model.chain())status="选择要连锁的效果";
            else status=model.prompt;
        } else status=model.canAct?"":"等待对方操作…";
        DuelUi.label(g,font,x+5,y+4,w-210,status,model.canAct?DuelUi.TEXT:DuelUi.DIM);
        int buttonX=x+w-5;
        // Revealed opponent hand cards get a list of their own, next to the status text.
        long revealed=board.opponent().hand().stream().filter(CardView::isKnown).count();
        if(revealed>0) {
            String label="对方手牌 "+revealed;
            int width=font.width(label)+12;
            int left=x+5+Math.min(w-210,Math.max(90,font.width(status)+8));
            boolean handHover=DuelUi.inside(mx,my,left,y+1,width,BAR_H-2);
            DuelUi.button(g,font,left,y+1,width,BAR_H-2,label,!modal,handHover);
            if(!modal)controls.add(new DuelUi.Control(left,y+1,width,BAR_H-2,"对方手牌",
                    ()->openPileList(viewerSide()^1,DuelLayout.HAND),true));
        }
        // While a dialog or the card list is open it owns the confirm/cancel pair, so the bar keeps
        // only its status line instead of showing dead buttons.
        if(!modal && picking() && confirmable()) {
            String label="确定 ("+selected.size()+")";
            int width=font.width(label)+12;
            buttonX-=width;
            boolean hover=DuelUi.inside(mx,my,buttonX,y+1,width,BAR_H-2);
            DuelUi.accentButton(g,font,buttonX,y+1,width,BAR_H-2,label,true,hover);
            controls.add(new DuelUi.Control(buttonX,y+1,width,BAR_H-2,"确定",this::confirm,true));
        }
        if(!modal && picking() && model.cancelAllowed()) {
            String label=model.finishable?"结束选择":"取消";
            int width=font.width(label)+12;
            buttonX-=width+4;
            boolean hover=DuelUi.inside(mx,my,buttonX,y+1,width,BAR_H-2);
            DuelUi.button(g,font,buttonX,y+1,width,BAR_H-2,label,true,hover);
            controls.add(new DuelUi.Control(buttonX,y+1,width,BAR_H-2,model.finishable?"结束选择":"取消",this::cancel,true));
        }
        if(!modal && model.counters() && !selected.isEmpty()) {
            String label="清零";
            int width=font.width(label)+12;
            buttonX-=width+4;
            boolean hover=DuelUi.inside(mx,my,buttonX,y+1,width,BAR_H-2);
            DuelUi.button(g,font,buttonX,y+1,width,BAR_H-2,label,true,hover);
            controls.add(new DuelUi.Control(buttonX,y+1,width,BAR_H-2,"清零",selected::clear,true));
        }
        if(!modal && model.canAct && model.place() && model.cancelable) {
            String label="取消";
            int width=font.width(label)+12;
            buttonX-=width+4;
            boolean hover=DuelUi.inside(mx,my,buttonX,y+1,width,BAR_H-2);
            DuelUi.button(g,font,buttonX,y+1,width,BAR_H-2,label,true,hover);
            controls.add(new DuelUi.Control(buttonX,y+1,width,BAR_H-2,"取消",this::cancel,true));
        }
        int tidy=-1;
        if(canAct() && model.idle()) {
            Choice command=model.command(8);
            if(command!=null)tidy=command.index;
        }
        if(!modal && tidy>=0) {
            String label="整理手牌";
            int width=font.width(label)+12;
            buttonX-=width+4;
            boolean hover=DuelUi.inside(mx,my,buttonX,y+1,width,BAR_H-2);
            DuelUi.button(g,font,buttonX,y+1,width,BAR_H-2,label,true,hover);
            final int index=tidy;
            controls.add(new DuelUi.Control(buttonX,y+1,width,BAR_H-2,"整理手牌",()->choose(index),true));
        }
    }

    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        super.render(g,mx,my,partial);
        if(model==null)return;
        float scale=access().mdpro$scale();
        int bx=(int)(mx/scale),by=(int)(my/scale);
        g.pose().pushPose();g.pose().scale(scale,scale,1);g.pose().translate(0,0,300);
        DuelLayout layout=layout();
        if(browser!=null)renderBrowser(g,layout,bx,by);
        else if(menu!=null) {
            menu.render(g,font,painter(),bx,by);
            // Hovering a menu row previews that action's card, the way MDPro3 shows the card it is about.
            Choice row=model.byIndex(menu.choiceAt(bx,by));
            if(row!=null && row.code!=0)detail=cardOf(row.code);
            if(menuUid>=0) {
                CardView card=cardAt(menuPlayer,menuLocation,menuSequence);
                DuelLayout.Rect rect=layout.zone(viewerSide(),menuPlayer,menuLocation,menuSequence);
                if(rect!=null && card.present())DuelUi.focus(g,rect.x(),rect.y(),rect.w(),rect.h(),DuelUi.SELECT,false);
            }
        }
        if(dialog!=null) {
            dialog.render(g,font,painter(),dialogCard(),bx,by);
            // Same for a dialog row: hovering a chain link or a search result shows that card's text.
            Choice row=model.byIndex(dialog.hoverIndex(bx,by));
            if(row!=null && row.code!=0)detail=cardOf(row.code);
        }
        drawCardPanel(g,bx,by);
        g.pose().popPose();
        // The native tooltip is drawn after the scaled pose: inside it, Minecraft's tooltip positioner
        // measures against the unscaled window and drops the text, and the box also comes out shrunken.
        drainSounds();
        drawBanner(g);
        renderNativeTooltip(g,mx,my);
    }

    /**
     * Turn and phase banner, pinned to the top edge of the screen.
     *
     * <p>The server gives NewTurn/NewPhase their own presentation beat; without this the only sign of
     * a phase change was a line in the side panel. It sits in the free strip above the board: a
     * banner near the board's own top edge covered the opponent's hand and field, which hides exactly
     * the rows the player needs to read while a phase beat is holding the queue.
     */
    private void drawBanner(GuiGraphics g) {
        if(bannerText.isEmpty() || System.currentTimeMillis()>=bannerUntil) return;
        int w=font.width(bannerText)+28, h=20;
        // Clamped to the top strip so a tall board can never push it down over the play area.
        int x=Math.max(2,(width-w)/2), y=4;
        DuelUi.plate(g,x,y,w,h,0xF2121C2C,DuelUi.ACCENT);
        g.drawString(font,bannerText,x+14,y+6,DuelUi.TEXT,true);
        bannerBox=new int[]{x,y,w,h};
    }

    /**
     * Card detail pinned to the top-left corner, as MDPro3 does. The board's own hover tooltip is
     * suppressed by the mixin, so this panel is the only place card text appears during a duel.
     */
    /**
     * Card artwork, name and stats pinned to the top-left corner, as MDPro3 does. The effect text is
     * left to Minecraft's own item tooltip, which {@link #renderNativeTooltip} anchors to the card.
     */
    private void drawCardPanel(GuiGraphics g,int bx,int by) {
        CardView card=hover!=null && hover.present()?hover:detail;
        if(card==null || !card.present())return;
        detail=card;
        int x=4,y=4,width=152,artW=96,artH=Math.round(artW*86f/59f);
        CardDefinition definition=card.isKnown()?card.definition():null;
        List<String> name=DuelUi.wrap(font,ChoiceModel.describeCard(card),width-10,2);
        int height=5+artH+3+name.size()*10+(definition==null?0:11)+3;
        DuelUi.plate(g,x,y,width,height,0xF2121C2C,DuelUi.ACCENT);
        painter().draw(g,x+(width-artW)/2,y+5,artW,artH,card);
        int textY=y+5+artH+3;
        for(String line:name) {DuelUi.label(g,font,x+5,textY,width-10,line,DuelUi.TEXT);textY+=10;}
        if(definition!=null) {
            // Full panel width: "ATK 2500 / DEF 2100" does not fit beside the art.
            String stats=definition.isMonster()?("ATK "+card.atk()+" / DEF "+card.def()):definition.isSpell()?"魔法":"陷阱";
            DuelUi.label(g,font,x+5,textY,width-10,stats,definition.isMonster()?DuelUi.ACCENT:DuelUi.DIM);
        }
    }

    /**
     * The card's full information, drawn beside that card.
     *
     * <p>The lines are the game's own tooltip lines for the card's item, so this is the same information
     * the original hover tooltip showed (rarity, type, attribute, level, ATK/DEF, password/CID and the
     * complete effect text). It is drawn by hand rather than with {@code GuiGraphics.renderTooltip}
     * because that call, when made from this overlay, painted its box but dropped its text, and it also
     * measures against the unscaled window while the board works in a scaled canvas. Drawing the lines
     * directly keeps the box next to the card, full sized, and identical for board, hand and pile lists.</p>
     */
    public void renderNativeTooltip(GuiGraphics g,int mx,int my) {
        if(model==null)return;
        // Only while the pointer is actually on a card: a sticky box kept covering the board after the
        // mouse had moved away, which is worse than not showing it at all.
        CardView card=hover;
        if(card==null || !card.present() || !card.isKnown())return;
        CardDefinition definition=card.definition();
        if(definition==null)return;
        ItemStack stack=DuelScreenAccess.mdpro$stackFor(definition);
        if(stack.isEmpty())return;
        List<Component> lines=stack.getTooltipLines(Minecraft.getInstance().player,
                net.minecraft.world.item.TooltipFlag.Default.NORMAL);
        if(lines.isEmpty())return;
        List<String> drawn=new ArrayList<>();
        for(Component line:lines)drawn.addAll(DuelUi.wrap(font,line.getString().replace("\r\n","\n").replace('\r','\n'),Math.min(208,width-20),Integer.MAX_VALUE));
        if(!tooltipCard.equals(card.cardId())) {tooltipCard=card.cardId();tooltipScroll=0;}
        tooltipText=List.copyOf(drawn);
        tooltipLines=drawn.size();
        int capacity=Math.max(1,(height-28)/10);
        tooltipVisible=Math.min(capacity,tooltipLines);
        boolean scrolling=tooltipLines>tooltipVisible;
        tooltipScroll=Math.max(0,Math.min(tooltipScroll,tooltipLines-tooltipVisible));
        int textWidth=0;
        for(String line:drawn)textWidth=Math.max(textWidth,font.width(line));
        int boxWidth=textWidth+10,boxHeight=tooltipVisible*10+8+(scrolling?12:0);
        float scale=access().mdpro$scale();
        DuelLayout layout=layout();
        DuelLayout.Rect rect=hoverRect;
        int boxX,boxY;
        if(rect==null) {boxX=mx-3;boxY=my-16;}
        else {
            int screenX=Math.round(rect.x()*scale),screenY=Math.round(rect.y()*scale);
            int screenW=Math.max(1,Math.round(rect.w()*scale));
            int screenH=Math.max(1,Math.round(rect.h()*scale));
            int cardRight=screenX+screenW,cardBottom=screenY+screenH;
            // The interaction column must not end up on top of the text, and the box must never bury the
            // card it describes, so the four placements are scored and the best one wins. All positions
            // here are the drawn box, padding included.
            int columnLeft=Math.round(layout.sideX()*scale)-6;
            int[][] spots={
                {cardRight+4,screenY},
                {screenX-boxWidth-4,screenY},
                {screenX,cardBottom+4},
                {screenX,screenY-boxHeight-4}};
            int bestScore=Integer.MAX_VALUE;boxX=spots[0][0];boxY=spots[0][1];
            for(int i=0;i<spots.length;i++) {
                int rawX=spots[i][0],rawY=spots[i][1];
                int cx=Math.max(4,Math.min(rawX,width-boxWidth-4));
                int cy=Math.max(4,Math.min(rawY,height-boxHeight-4));
                int score=i;
                score+=Math.max(0,4-rawX)+Math.max(0,(rawX+boxWidth)-(width-4));
                score+=Math.max(0,4-rawY)+Math.max(0,(rawY+boxHeight)-(height-4));
                // Overlapping the interaction column is worse than overlapping the board.
                if(cx+boxWidth>columnLeft)score+=60;
                // Covering the card itself is worse still.
                if(cx<cardRight && cx+boxWidth>screenX && cy<cardBottom && cy+boxHeight>screenY)score+=120;
                if(score<bestScore) {bestScore=score;boxX=cx;boxY=cy;}
            }
        }
        boxX=Math.max(4,Math.min(boxX,width-boxWidth-4));
        boxY=Math.max(4,Math.min(boxY,height-boxHeight-4));
        // Lifted and flushed on the spot so the card list and the interaction column cannot paint over it.
        g.pose().pushPose();
        // The detail panel is drawn at board Z=300; keep the complete rules above it.
        g.pose().translate(0,0,1000);
        g.fill(boxX,boxY,boxX+boxWidth,boxY+boxHeight,0xF0100010);
        g.renderOutline(boxX,boxY,boxWidth,boxHeight,0x505000FF);
        int lineY=boxY+4;
        for(int i=tooltipScroll;i<tooltipScroll+tooltipVisible;i++) {
            g.drawString(font,drawn.get(i),boxX+4,lineY,i==0?0xFFFFFF:0xA8A8A8,false);
            lineY+=10;
        }
        if(scrolling)g.drawString(font,"滚轮查看全文 "+(tooltipScroll+1)+"–"+(tooltipScroll+tooltipVisible)+"/"+tooltipLines,boxX+4,lineY+1,0xE1A64C,false);
        g.pose().popPose();
        g.flush();
        lastTooltipBox=new int[]{boxX,boxY,boxWidth,boxHeight};
    }

    private void renderBrowser(GuiGraphics g,DuelLayout layout,int mx,int my) {
        Browser view=browser;
        int x=layout.left()-6,y=layout.top(),w=DuelLayout.BOARD_W+12,h=layout.boardH();
        DuelUi.plate(g,x,y,w,h,0xFA15243A,DuelUi.ACCENT);
        DuelUi.label(g,font,x+6,y+5,w-12,view.pick?view.title:view.title+"（"+view.cards.size()+" 张）",DuelUi.ACCENT);
        int offset=20;
        List<CardView> shown=shownCards();
        if(searchActive && search!=null) {
            search.setX(x+6);search.setY(y+offset);search.render(g,mx,my,0);
            String label=searchForServer?"搜索":"筛选";
            boolean hover=DuelUi.inside(mx,my,x+162,y+offset,44,BAR_H);
            DuelUi.button(g,font,x+162,y+offset,44,BAR_H,label,true,hover);
            controls.add(new DuelUi.Control(x+162,y+offset,44,BAR_H,searchForServer?"搜索":"筛选",this::submitSearch,true));
            offset+=18;
        }
        int rows=Math.max(1,(h-offset-44)/44);
        int columns=8;
        int perPage=rows*columns;
        gridX=x+6;gridY=y+offset+2;gridOffset=offset;gridRows=rows;gridPerPage=perPage;
        int total=shown.size();
        view.pageCount=Math.max(1,(total+perPage-1)/perPage);
        page=Math.max(0,Math.min(page,view.pageCount-1));
        CardView hovered=null;
        int localChoice=-1;
        for(int slot=0;slot<perPage;slot++) {
            int index=page*perPage+slot;
            if(index>=total)break;
            int cx=x+6+(slot%columns)*28,cy=y+offset+2+(slot/columns)*44;
            CardView card=shown.get(index);
            Integer choice=view.pick?view.choices.get(index).index:null;
            boolean chosen=choice!=null && selected.contains(choice);
            g.fill(cx,cy,cx+26,cy+38,chosen?0xFF3B6E8C:0x40000000);
            painter().draw(g,cx,cy,26,38,card);
            DuelUi.outline(g,cx,cy,26,38,chosen?DuelUi.SELECT:view.pick?DuelUi.CANDIDATE:0x60FFFFFF);
            if(!view.pick) {
                int cardCode=codeOf(card);
                if(cardCode!=0) {
                    int sequence=browserSequence(card,index);
                    for(Choice candidate:model.choices) {
                        if(candidate.code!=cardCode || candidate.player!=view.player
                                || candidate.location!=view.location || candidate.sequence!=sequence)continue;
                        if(selected.contains(candidate.index)) {DuelUi.outline(g,cx-1,cy-1,28,40,DuelUi.SELECT);break;}
                        DuelUi.outline(g,cx-1,cy-1,28,40,DuelUi.CANDIDATE);
                    }
                }
            }
            if(chosen) {
                int order=selected.indexOf(choice)+1;
                g.fill(cx+13,cy,cx+26,cy+11,0xFF000000);
                g.drawString(font,Integer.toString(order),cx+15,cy+2,0xFFFFD34D,false);
            }
            if(DuelUi.inside(mx,my,cx,cy,26,38)) {
                g.fill(cx,cy,cx+26,cy+38,0x30FFFFFF);
                hovered=card;localChoice=choice!=null?choice:-1;
                // The pile list draws its own tooltip through the same hook as the board.
                setListHover(card,cx,cy);
            }
        }
        if(hovered!=null) {detail=hovered;zoom=hovered;}
        int bottom=y+h-20;
        boolean prev=page>0,next=page<view.pageCount-1;
        DuelUi.button(g,font,x+5,bottom,46,16,"上一页",prev,DuelUi.inside(mx,my,x+5,bottom,46,16));
        DuelUi.button(g,font,x+54,bottom,46,16,"下一页",next,DuelUi.inside(mx,my,x+54,bottom,46,16));
        controls.add(new DuelUi.Control(x+5,bottom,46,16,"上一页",()->page=Math.max(0,page-1),prev));
        controls.add(new DuelUi.Control(x+54,bottom,46,16,"下一页",()->page++,next));
        if(view.pageCount>1)DuelUi.label(g,font,x+104,bottom+4,70,"第 "+(page+1)+"/"+view.pageCount+" 页",DuelUi.DIM);
        int right=x+w-5;
        if(view.pick && confirmable()) {
            String label="确定 ("+selected.size()+")";
            int width=font.width(label)+12;
            right-=width;
            DuelUi.accentButton(g,font,right,bottom,width,16,label,true,DuelUi.inside(mx,my,right,bottom,width,16));
            controls.add(new DuelUi.Control(right,bottom,width,16,"确定",this::confirm,true));
        }
        if(view.pick && model.cancelAllowed()) {
            String label=model.finishable?"结束选择":"取消";
            int width=font.width(label)+12;
            right-=width+4;
            DuelUi.button(g,font,right,bottom,width,16,label,true,DuelUi.inside(mx,my,right,bottom,width,16));
            controls.add(new DuelUi.Control(right,bottom,width,16,model.finishable?"结束选择":"取消",this::cancel,true));
        }
        String closeLabel=view.pick&&model.cancelAllowed()?"隐藏":"关闭";
        int closeWidth=font.width(closeLabel)+12;
        // Keep stepping left so the action buttons never overlap each other.
        right-=closeWidth+4;
        DuelUi.button(g,font,right,bottom,closeWidth,16,closeLabel,true,DuelUi.inside(mx,my,right,bottom,closeWidth,16));
        controls.add(new DuelUi.Control(right,bottom,closeWidth,16,closeLabel,this::closeBrowser,true));
        if(localChoice>=0) { /* hover feedback already drawn */ }
    }
    /**
     * Records the pile-list card under the pointer.
     *
     * <p>The full information tooltip ({@link #renderNativeTooltip}) and the corner panel
     * ({@link #drawCardPanel}) read different fields, so a list hover must set both. Setting only the
     * panel left the pile lists with no tooltip at all, which is what the board's cards had.</p>
     */
    private void setListHover(CardView card,int cx,int cy) {
        detail=card;zoom=card;hover=card;hoverRect=new DuelLayout.Rect(cx,cy,26,38);
    }
    private List<CardView> shownCards() {
        Browser view=browser;
        List<CardView> result=new ArrayList<>();
        String query=searchForServer?"":searchText();
        if(view.pick) {
            for(Choice choice:view.choices) {
                CardView card=cardOf(choice.code);
                if(card.present())result.add(card);
                else {
                    CardView real=cardAt(choice.player,choice.location,choice.sequence);
                    result.add(real.present()?real:CardView.EMPTY);
                }
            }
        } else {
            for(CardView card:view.cards) {
                if(!query.isEmpty()) {
                    String name=ChoiceModel.describeCard(card);
                    if(!name.toLowerCase().contains(query.toLowerCase()))continue;
                }
                result.add(card);
            }
        }
        return result;
    }
    private String searchText() {return search==null?"":search.getValue();}
    private void submitSearch() {
        String value=searchText();
        if(searchForServer) {
            if(!canAct())return;
            send(new int[0],false,"search:"+value,false);
            return;
        }
        page=0;
    }

    // ------------------------------------------------------------------ input

    @Override public boolean mouseClicked(double sx,double sy,int button) {
        if(model==null)return false;
        float scale=access().mdpro$scale();
        double mx=sx/scale,my=sy/scale;
        if(dialog!=null) {
            if(button==1) {cancelDialog();return true;}
            DuelWidgets.Hit hit=dialog.hit(mx,my);
            if(hit!=null) {
                dev.codex.yugiohfix.client.ClientSoundPlayer.play(dev.codex.yugiohfix.sound.SoundCue.UI_CLICK);
                if(hit.choice()!=null)choose(hit.choice()); else hit.action().run();
            }
            return true;
        }
        if(browser!=null) {
            if(button==1) {closeBrowser();return true;}
            if(searchActive && search!=null && search.mouseClicked(mx,my,button))return true;
            int slot=browserSlotAt(mx,my);
            if(slot>=0) {
                List<CardView> shown=shownCards();
                int index=page*gridPerPage+slot;
                if(index<shown.size()) {
                    if(browser.pick) {
                        dev.codex.yugiohfix.client.ClientSoundPlayer.play(dev.codex.yugiohfix.sound.SoundCue.CARD_SELECT);
                        // choose() is the same path a board click takes, so a declaration still sends its code.
                        choose(browser.choices.get(index).index);
                    } else {
                        // A pile the player opened themselves (extra deck, grave, banished, deck) must be
                        // usable: hovering shows the card, clicking takes its action or offers the menu.
                        CardView card=shown.get(index);
                        detail=card;zoom=card;
                        List<Choice> actions=actionsForCard(card,browser.player,browser.location,
                                browserSequence(card,index));
                        if(actions.size()==1) {
                            dev.codex.yugiohfix.client.ClientSoundPlayer.play(dev.codex.yugiohfix.sound.SoundCue.UI_CONFIRM);
                            choose(actions.get(0).index);
                        } else if(!actions.isEmpty()) {
                            dev.codex.yugiohfix.client.ClientSoundPlayer.play(dev.codex.yugiohfix.sound.SoundCue.UI_CLICK);
                            Choice first=actions.get(0);
                            openMenu(new Cell(first.player,first.location,first.sequence),actions);
                        }
                    }
                    return true;
                }
            }
            for(DuelUi.Control control:List.copyOf(controls)) if(control.enabled() && control.contains(mx,my)) {
                dev.codex.yugiohfix.client.ClientSoundPlayer.play(dev.codex.yugiohfix.sound.SoundCue.UI_CLICK);
                control.action().run();return true;
            }
            return true;
        }
        if(button==1) {
            if(menu!=null) {menu=null;return true;}
            if(model.cancelAllowed()) {cancel();return true;}
            return false;
        }
        if(menu!=null) {
            int choice=menu.choiceAt(mx,my);
            if(choice>=0) {
                dev.codex.yugiohfix.client.ClientSoundPlayer.play(dev.codex.yugiohfix.sound.SoundCue.UI_CLICK);
                menu=null;choose(choice);return true;
            }
            if(menu.contains(mx,my))return true;
            menu=null;
        }
        for(DuelUi.Control control:List.copyOf(controls)) if(control.enabled() && control.contains(mx,my)) {
            dev.codex.yugiohfix.client.ClientSoundPlayer.play(dev.codex.yugiohfix.sound.SoundCue.UI_CLICK);
            control.action().run();return true;
        }
        return true;
    }
    @Override public boolean mouseScrolled(double sx,double sy,double delta) {
        if(dialog==null && hover!=null && hover.isKnown() && hover.cardId().equals(tooltipCard) && tooltipLines>tooltipVisible) {
            tooltipScroll=Math.max(0,Math.min(tooltipLines-tooltipVisible,tooltipScroll-(int)Math.signum(delta)*3));
            return true;
        }
        if(dialog!=null) {dialog.scroll(-(int)Math.signum(delta));return true;}
        if(browser!=null) {
            int step=-(int)Math.signum(delta);
            int perPage=browserPageSize();
            int total=shownCards().size();
            int pages=Math.max(1,(total+perPage-1)/perPage);
            page=Math.max(0,Math.min(pages-1,page+step));
            return true;
        }
        return false;
    }
    private int browserPageSize() {
        DuelLayout layout=layout();
        int offset=searchActive?38:20;
        int rows=Math.max(1,(layout.boardH()-offset-44)/44);
        return rows*8;
    }
    /** Grid slot under the pointer, or -1: the gaps between cells are not clickable. */
    private int browserSlotAt(double mx,double my) {
        int columns=8;
        if(mx<gridX || my<gridY)return -1;
        int column=(int)((mx-gridX)/28),row=(int)((my-gridY)/44);
        if(column<0 || column>=columns || row<0 || row>=gridRows)return -1;
        if(mx-gridX-column*28>=26 || my-gridY-row*44>=38)return -1;
        return row*columns+column;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==256) {
            if(dialog!=null) {cancelDialog();return true;}
            if(browser!=null) {closeBrowser();return true;}
            if(menu!=null) {menu=null;return true;}
            if(model!=null && model.cancelAllowed()) {cancel();return true;}
            // Nothing left to close: leave the duel UI and stand in the world, as the original mod did.
            onClose();
            return true;
        }
        if(dialog==null && browser!=null && searchActive && search!=null && search.keyPressed(key,scan,modifiers))return true;
        if(key==257 || key==32) {
            if(dialog!=null) {
                for(var button:dialog.buttons()) if(button.primary() && button.enabled()) {button.action().run();return true;}
            }
            if(model!=null && model.cardPicking() && confirmable()) {confirm();return true;}
        }
        if(key>=49 && key<=57) {
            int slot=key-49;
            if(dialog!=null && slot<dialog.optionCount()) {
                choose(dialog.optionChoice(slot));return true;
            }
        }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean charTyped(char c,int modifiers) {
        if(dialog==null && browser!=null && searchActive && search!=null)return search.charTyped(c,modifiers);
        return false;
    }

    // ------------------------------------------------------- development fixture seams
    // Read-only views used by DuelUiSmoke, so the fixture can drive the real hit tests instead of
    // recomputing geometry. None of them can influence a live duel.

    public int fixture$viewerSide() {return viewerSide();}
    /** Pins the detail panel to one card, so the fixture can capture it without a real mouse move. */
    public void fixture$showCard(int player,int location,int sequence) {detail=cardAt(player,location,sequence);}
    /** Simulates a hover on a board card, kept across frames so the fixture can capture the tooltip. */
    public void fixture$hover(int player,int location,int sequence) {
        fixtureHover=cardAt(player,location,sequence);
        hoverRect=rectOf(layout(),new Cell(player,location,sequence));
        detail=fixtureHover;
    }
    /** Ends the simulated hover. */
    public void fixture$clearHover() {fixtureHover=null;hover=null;hoverRect=null;}
    /** True when the native tooltip for the pinned card has content to draw. */
    public boolean fixture$tooltipReady() {
        CardView card=hover!=null?hover:fixtureHover;
        if(card==null||!card.present()||!card.isKnown()||card.definition()==null)return false;
        return !DuelScreenAccess.mdpro$stackFor(card.definition()).isEmpty();
    }
    /** Last drawn info box in screen coordinates: {x,y,w,h}, or null. */
    public int[] fixture$tooltipBox() {return lastTooltipBox;}
    public String fixture$tooltipText() {return String.join("",tooltipText);}
    public int fixture$tooltipScroll() {return tooltipScroll;}
    public void fixture$hoverCard(CardView card) {fixtureHover=card;hover=card;hoverRect=null;}
    /** Anchor the native tooltip would use for the pinned card, or null. */
    public int[] fixture$tooltipAnchor() {
        return hoverRect==null?null:new int[]{hoverRect.x(),hoverRect.y(),hoverRect.w(),hoverRect.h()};
    }
    /** What the detail panel would show with the pointer at this virtual position. */
    public String fixture$hoverAt(int vx,int vy) {
        CardView view=hoveredCard(layout(),vx,vy);
        return view==null||!view.present()?"":ChoiceModel.describeCard(view);
    }
    /** Centre of a hand slot in virtual coordinates, for the hover fixture. */
    public int[] fixture$handSlotCentre(int index) {
        DuelLayout layout=layout();
        int count=Math.max(1,board.self().hand().size());
        DuelLayout.Rect rect=layout.handSlot(index,count);
        return new int[]{rect.x()+rect.w()/2,rect.y()+rect.h()/2};
    }
    /** One-line dump of the real layout numbers, so the fixture can reproduce a user's window. */
    public String fixture$layout() {
        float scale=access().mdpro$scale();
        DuelLayout l=layout();
        return "width="+width+" height="+height+" scale="+scale+" left="+l.left()+" top="+l.top()
                +" virtual="+Math.round(width/scale)+"x"+Math.round(height/scale)
                +" sideX="+l.sideX()+" sideRight="+(l.sideX()+SIDE_W)+" barTop="+l.barTop()
                +" handTop="+l.handTop()+" boardBottom="+l.bottom();
    }
    public int fixture$phase() {return model==null?-1:model.phase;}
    public int fixture$kind() {return model==null?-1:model.kind;}
    public int fixture$selectedCount() {return selected.size();}
    public int fixture$menuRows() {return menu==null?-1:menu.items().size();}
    /** Banner text currently on screen, or an empty string. */
    public String fixture$banner() {return System.currentTimeMillis()<bannerUntil?bannerText:"";}
    /** Last drawn banner rectangle {x,y,w,h}, or null when nothing was drawn. */
    public int[] fixture$bannerBox() {return bannerBox==null?null:bannerBox.clone();}
    public boolean fixture$browserOpen() {return browser!=null;}
    /** Opens a pile list the way the board's pile buttons do, for the fixture. */
    public void fixture$openPile(int player,int location) {openPileList(player,location);}
    /** Puts the pointer on one drawn slot of the open pile list, through the real hover path. */
    public void fixture$hoverBrowserSlot(int slot) {
        if(browser==null)return;
        List<CardView> shown=shownCards();
        int index=page*gridPerPage+slot;
        if(slot<0||index<0||index>=shown.size())return;
        setListHover(shown.get(index),gridX+(slot%8)*28,gridY+(slot/8)*44);
    }
    /** Name shown in the detail panel right now, or an empty string. */
    public String fixture$detailName() {return detail==null||!detail.present()?"":ChoiceModel.describeCard(detail);}
    public boolean fixture$browserPick() {return browser!=null && browser.pick;}
    public int fixture$browserRows() {return browser==null?-1:browser.pick?browser.choices.size():browser.cards.size();}
    public boolean fixture$dialogOpen() {return dialog!=null;}
    public int fixture$dialogOptions() {return dialog==null?-1:dialog.optionCount();}
    public int fixture$dialogButtons() {return dialog==null?-1:dialog.buttonCount();}
    /** Board rectangle of one cell in virtual coordinates, or null when it is not drawn. */
    public int[] fixture$cell(int player,int location,int sequence) {
        DuelLayout.Rect rect=rectOf(layout(),new Cell(player,location,sequence));
        return rect==null?null:new int[]{rect.x(),rect.y(),rect.w(),rect.h()};
    }
    /** Centre of the enabled control with this label, or null. */
    public int[] fixture$control(String label) {
        for(DuelUi.Control control:controls)
            if(control.enabled() && control.label().equals(label))
                return new int[]{control.x()+control.w()/2,control.y()+control.h()/2};
        return null;
    }
    /** Clickable points of the open overlay: {x,y,choice}, with choice -1 for a dialog button. */
    public List<int[]> fixture$overlayTargets() {
        List<int[]> targets=new ArrayList<>();
        if(dialog!=null) {
            for(int i=0;i<dialog.optionCount();i++)
                targets.add(dialog.optionPoint(i));
            for(DuelWidgets.Button button:dialog.buttons())
                targets.add(new int[]{button.x()+button.w()/2,button.y()+button.h()/2,-1});
            return targets;
        }
        if(menu!=null) {
            for(int i=0;i<menu.items().size();i++)
                targets.add(new int[]{menu.x()+8,menu.rowTop(i)+menu.rowHeight()/2,menu.choiceOf(i)});
            return targets;
        }
        if(browser!=null) {
            List<CardView> shown=shownCards();
            for(int slot=0;slot<gridPerPage && page*gridPerPage+slot<shown.size();slot++) {
                int index=page*gridPerPage+slot;
                int cx=gridX+(slot%8)*28,cy=gridY+(slot/8)*44;
                targets.add(new int[]{cx+3,cy+3,browser.pick?browser.choices.get(index).index:-1});
            }
        }
        return targets;
    }
    /** Number of zone candidates the current prompt offers on the board. */
    public int fixture$placeCandidates() {
        int count=0;
        for(Choice choice:model.choices) {
            if(!choice.isCard())continue;
            if(rectOf(layout(),new Cell(choice.player,choice.location,choice.sequence))!=null)count++;
        }
        return count;
    }
}
