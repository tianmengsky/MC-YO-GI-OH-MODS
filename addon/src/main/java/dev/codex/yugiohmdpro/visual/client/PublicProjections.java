package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.network.CardView;
import dev.codex.yugiohmdpro.duel.NativeBoard;
import dev.codex.yugiohmdpro.visual.board.BoardLayout;
import dev.codex.yugiohmdpro.visual.board.BoardPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Separate from ClientDuelHandler: watching a board never opens or replaces a duel GUI. */
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon",value=Dist.CLIENT)
public final class PublicProjections {
    private static final Map<UUID,Board> BOARDS=new LinkedHashMap<>();
    public static final class Board {
        public final UUID id;
        public final String dimension;
        public final double x,y,z,yaw;
        public final List<CardView> firstMonsters,firstSpells,secondMonsters,secondSpells;
        public final HologramActions.Context animations;
        private final long received;
        private final int attackEvent;
        private Board(CompoundTag tag,Board previous) {
            id=tag.getUUID("duel");dimension=tag.getString("dimension");
            x=tag.getDouble("x");y=tag.getDouble("y");z=tag.getDouble("z");yaw=tag.getDouble("yaw");
            firstMonsters=read(tag,"p0l4");firstSpells=read(tag,"p0l8");
            secondMonsters=read(tag,"p1l4");secondSpells=read(tag,"p1l8");
            received=System.nanoTime();attackEvent=tag.getInt("attackEvent");
            animations=previous==null?new HologramActions.Context():previous.animations;
            HologramActions.inContext(animations,()->{
                if(previous!=null && attackEvent>previous.attackEvent) {
                    long now=System.currentTimeMillis();
                    HologramActions.attack(tag.getInt("attackerUid"),now);
                    HologramActions.attackDeclared(tag.getInt("targetUid"),now);
                }
            });
        }
        public List<BoardPlan.Slot> slots() {
            var forward=BoardLayout.forwardFromYaw(yaw);
            return BoardPlan.plan(x,y,z,forward[0],forward[1],firstMonsters,firstSpells,secondMonsters,secondSpells,true);
        }
    }
    private static List<CardView> read(CompoundTag tag,String key) {
        var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(tag.getByteArray(key)));
        try {
            int count=buf.readVarInt();if(count<0 || count>5)throw new IllegalArgumentException("Invalid public field size");
            var cards=new ArrayList<CardView>();for(int i=0;i<count;i++)cards.add(CardView.read(buf));
            return List.copyOf(cards);
        }finally{buf.release();}
    }
    public static void receive(CompoundTag tag) {
        if(tag==null || !tag.hasUUID("duel"))return;
        UUID id=tag.getUUID("duel");
        if(tag.getBoolean("finished")){BOARDS.remove(id);return;}
        BOARDS.put(id,new Board(tag,BOARDS.get(id)));
    }
    public static List<Board> current(String dimension) {
        long now=System.nanoTime();
        BOARDS.values().removeIf(b->!b.dimension.equals(dimension) || now-b.received>5_000_000_000L);
        return List.copyOf(BOARDS.values());
    }
    public static void clear(){BOARDS.clear();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){clear();}
}
