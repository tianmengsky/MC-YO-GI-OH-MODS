package dev.codex.yugiohmdpro.duel;

import dev.codex.yugiohmdpro.client.NativeDuelScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.function.Supplier;

public final class DuelNetwork {
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation("yugioh_mdpro_addon","duel"),()->"8","8"::equals,"8"::equals);
    public static void register() {
        CHANNEL.registerMessage(0,State.class,(p,b)->b.writeNbt(p.tag),b->new State(b.readNbt()),State::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1,Action.class,Action::encode,Action::decode,Action::handle,Optional.of(NetworkDirection.PLAY_TO_SERVER));
    // Single-card binder moves: the reviewed fix moves whole stacks and has no reverse operation, so
    // these two server-bound messages carry the deck editor's one-card interactions.
    CHANNEL.registerMessage(4,BinderAddOne.class,BinderAddOne::encode,BinderAddOne::decode,BinderAddOne::handle,Optional.of(NetworkDirection.PLAY_TO_SERVER));
    CHANNEL.registerMessage(5,BinderReturnOne.class,BinderReturnOne::encode,BinderReturnOne::decode,BinderReturnOne::handle,Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2,Restrictions.class,(p,b)->b.writeNbt(p.tag),b->new Restrictions(b.readNbt()),Restrictions::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3,Projection.class,(p,b)->b.writeNbt(p.tag),b->new Projection(b.readNbt()),Projection::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void send(ServerPlayer player,CompoundTag tag) { CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new State(tag)); }
    public static void send(Action action) {CHANNEL.sendToServer(action);}
    public static void sendRestrictions(ServerPlayer player,CompoundTag tag){CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new Restrictions(tag));}
    public static void sendProjection(ServerPlayer player,CompoundTag tag){CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new Projection(tag));}
    public record Projection(CompoundTag tag) {
        void handle(Supplier<NetworkEvent.Context> supplier) {
            var ctx=supplier.get();
            ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->
                    dev.codex.yugiohmdpro.visual.client.PublicProjections.receive(tag)));
            ctx.setPacketHandled(true);
        }
    }
    public record Restrictions(CompoundTag tag) {
        void handle(Supplier<NetworkEvent.Context> supplier) {
            var ctx=supplier.get();ctx.enqueueWork(()->{if(tag!=null)dev.codex.yugiohmdpro.rules.ServerBanlist.receive(tag);});ctx.setPacketHandled(true);
        }
    }
    public record State(CompoundTag tag) {
        void handle(Supplier<NetworkEvent.Context> supplier) {
            var ctx=supplier.get();
            ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->NativeDuelScreen.receive(tag)));
            ctx.setPacketHandled(true);
        }
    }
    public record Action(UUID duel,int revision,int[] selected,boolean cancel,String text,boolean surrender) {
        void encode(FriendlyByteBuf b) {b.writeUUID(duel);b.writeVarInt(revision);b.writeVarIntArray(selected);b.writeBoolean(cancel);b.writeUtf(text,256);b.writeBoolean(surrender);}
        static Action decode(FriendlyByteBuf b) {return new Action(b.readUUID(),b.readVarInt(),b.readVarIntArray(63),b.readBoolean(),b.readUtf(256),b.readBoolean());}
        void handle(Supplier<NetworkEvent.Context> supplier) {
            var ctx=supplier.get();ctx.enqueueWork(()->DuelBridge.input(ctx.getSender(),duel,revision,selected,cancel,text,surrender));ctx.setPacketHandled(true);
        }
    }

    /** Sent when one card in the binder list is clicked: adds exactly that one card. */
    public record BinderAddOne(int cell, ItemStack expected) {
        void encode(net.minecraft.network.FriendlyByteBuf buffer) {buffer.writeVarInt(cell);buffer.writeItem(expected);}
        static BinderAddOne decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new BinderAddOne(buffer.readVarInt(),buffer.readItem());
        }
        void handle(java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> supplier) {
            var context=supplier.get();
            context.enqueueWork(()->dev.codex.yugiohmdpro.binder.BinderDeckOps.addOne(context.getSender(),cell,expected));
            context.setPacketHandled(true);
        }
    }

    /** Sent when a card in the deck grid is right-clicked: returns exactly that one card. */
    public record BinderReturnOne(int slot) {
        void encode(net.minecraft.network.FriendlyByteBuf buffer) {buffer.writeVarInt(slot);}
        static BinderReturnOne decode(net.minecraft.network.FriendlyByteBuf buffer) {return new BinderReturnOne(buffer.readVarInt());}
        void handle(java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> supplier) {
            var context=supplier.get();
            context.enqueueWork(()->dev.codex.yugiohmdpro.binder.BinderDeckOps.returnOne(context.getSender(),slot));
            context.setPacketHandled(true);
        }
    }

    public static void sendAddOne(int cell, ItemStack expected) {CHANNEL.sendToServer(new BinderAddOne(cell,expected));}
    public static void sendReturnOne(int slot) {CHANNEL.sendToServer(new BinderReturnOne(slot));}
}