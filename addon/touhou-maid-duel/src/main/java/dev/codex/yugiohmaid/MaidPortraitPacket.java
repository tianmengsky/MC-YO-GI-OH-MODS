package dev.codex.yugiohmaid;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Supplier;

public record MaidPortraitPacket(UUID opponentId, UUID maidId, String maidName, String modelId,
                                 boolean ysmModel, String ysmModelId, String ysmTexture, String ysmName, ItemStack offhand) {
    public MaidPortraitPacket { offhand = offhand.copy(); }
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(opponentId);
        buf.writeUUID(maidId);
        buf.writeUtf(maidName, 128);
        buf.writeUtf(modelId, 256);
        buf.writeBoolean(ysmModel);
        buf.writeUtf(ysmModelId, 256);
        buf.writeUtf(ysmTexture, 256);
        buf.writeUtf(ysmName, 128);
        buf.writeItem(offhand);
    }

    public static MaidPortraitPacket decode(FriendlyByteBuf buf) {
        return new MaidPortraitPacket(buf.readUUID(), buf.readUUID(), buf.readUtf(128), buf.readUtf(256),
                buf.readBoolean(), buf.readUtf(256), buf.readUtf(256), buf.readUtf(128), buf.readItem());
    }

    public static void handle(MaidPortraitPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> MaidPortraitClient.accept(packet));
        context.get().setPacketHandled(true);
    }
}
