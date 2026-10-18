package dev.codex.yugiohmaid;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;

public final class MaidDuelNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TouhouMaidDuel.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);
    private static int nextId;

    private MaidDuelNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(MaidPortraitPacket.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MaidPortraitPacket::encode).decoder(MaidPortraitPacket::decode)
                .consumerMainThread(MaidPortraitPacket::handle).add();
    }

    public static void sendPortrait(ServerPlayer player, UUID opponentId, MaidDuelSession session) {
        CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new MaidPortraitPacket(opponentId, session.maidId(), session.maidName(), session.modelId(),
                        session.ysmModel(), session.ysmModelId(), session.ysmTexture(), session.ysmName(), session.offhand()));
    }
}
