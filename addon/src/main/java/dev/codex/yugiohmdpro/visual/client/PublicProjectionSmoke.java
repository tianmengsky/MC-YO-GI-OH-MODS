package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.duel.CardPosition;
import com.kiki.yugioh.network.CardView;
import dev.codex.yugiohmdpro.duel.NativeBoard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.util.*;

/** Explicit visual-fixture checks for a client that is not participating in any duel. */
public final class PublicProjectionSmoke extends Screen {
    private static final String DIMENSION="minecraft:overworld";
    private static final UUID FIRST=UUID.randomUUID(),SECOND=UUID.randomUUID();
    private int drawn;
    private PublicProjectionSmoke(){super(Component.literal("旁观公开投影：两场决斗 / 盖卡隐藏"));}
    private static CompoundTag fixture(UUID id,double x) {
        var tag=new CompoundTag();tag.putUUID("duel",id);tag.putString("dimension",DIMENSION);
        tag.putDouble("x",x);tag.putDouble("y",0);tag.putDouble("z",0);tag.putDouble("yaw",0);
        var blue=new CardView(true,"blue_eyes_white_dragon",1,CardPosition.ATTACK,3000,2500,false,false,false,CardRarity.COMMON);
        var dark=new CardView(true,"dark_magician",2,CardPosition.DEFENSE,2500,2100,false,false,false,CardRarity.COMMON);
        var hidden=new CardView(true,"",3,CardPosition.FACE_DOWN_DEFENSE,0,0,false,false,false,CardRarity.COMMON);
        tag.putByteArray("p0l4",NativeBoard.encodeCards(List.of(blue,hidden,CardView.EMPTY,CardView.EMPTY,CardView.EMPTY)));
        tag.putByteArray("p1l4",NativeBoard.encodeCards(List.of(dark,CardView.EMPTY,CardView.EMPTY,CardView.EMPTY,CardView.EMPTY)));
        tag.putByteArray("p0l8",NativeBoard.encodeCards(List.of()));tag.putByteArray("p1l8",NativeBoard.encodeCards(List.of()));
        return tag;
    }
    public static void verifyIndependentClient() {
        var mc=Minecraft.getInstance();
        var screen=mc.screen;var privateState=com.kiki.yugioh.client.ClientDuelHandler.getState();
        PublicProjections.clear();
        PublicProjections.receive(fixture(FIRST,-12));PublicProjections.receive(fixture(SECOND,12));
        var boards=PublicProjections.current(DIMENSION);
        check(boards.size()==2,"A bystander must retain both simultaneous duels");
        check(mc.screen==screen && com.kiki.yugioh.client.ClientDuelHandler.getState()==privateState,
                "Receiving public projections must not open or replace a participant GUI/state");
        for(var board:boards) {
            check(board.slots().size()==3,"Three occupied world slots must use the shared board layout");
            check(board.slots().stream().filter(s->s.projection()!=null).count()==2,"Only face-up monsters may project");
        }
        long now=System.currentTimeMillis();
        HologramActions.inContext(boards.get(0).animations,()->HologramActions.attack(1,now));
        HologramActions.inContext(boards.get(1).animations,()->check(HologramActions.secondsInto(1,now+100)==0,
                "Identical per-duel card UIDs must not share animations"));
        var ended=new CompoundTag();ended.putUUID("duel",FIRST);ended.putBoolean("finished",true);
        PublicProjections.receive(ended);
        check(PublicProjections.current(DIMENSION).size()==1,"Ending one duel must preserve the other projection");
        check(PublicProjections.current("minecraft:the_nether").isEmpty(),"Dimension changes must clear stale boards");
        PublicProjections.clear();
    }
    public static Screen screen() {
        verifyIndependentClient();
        PublicProjections.receive(fixture(FIRST,-12));PublicProjections.receive(fixture(SECOND,12));
        return new PublicProjectionSmoke();
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial) {
        renderBackground(g);g.drawCenteredString(font,title,width/2,24,0xffffff);
        g.pose().pushPose();g.pose().translate(width/2.0,height/2.0+30,300);g.pose().scale(6,-6,6);
        g.pose().mulPose(com.mojang.math.Axis.XP.rotationDegrees(35));
        drawn=0;
        for(var board:PublicProjections.current(DIMENSION))HologramActions.inContext(board.animations,
                ()->drawn+=InWorldBoardRenderer.drawBoard(g.pose(),g.bufferSource(),board));
        g.flush();g.pose().popPose();
        g.drawCenteredString(font,"公开场面；不传手牌、盖卡身份或操作选项",width/2,height-28,0xdddddd);
    }
    public static void finish() throws Exception {
        check(Minecraft.getInstance().screen instanceof PublicProjectionSmoke screen && screen.drawn==6,
                "The actual world renderer must draw both public boards");
        PublicProjections.clear();
        Files.writeString(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("../generated/public-projection-smoke-result.txt").normalize(),
                "PASS: independent bystander cache, two simultaneous boards, shared world renderer, hidden set cards, per-duel animations, end/dimension cleanup, no private GUI mutation\n");
    }
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
