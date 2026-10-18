package dev.codex.yugiohmdpro.client;
import dev.codex.yugiohmdpro.rules.ServerBanlist;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon",value=Dist.CLIENT)
public final class BanlistClient {
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){ServerBanlist.resetClient();}
}
