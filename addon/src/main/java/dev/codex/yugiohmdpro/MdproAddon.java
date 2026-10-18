package dev.codex.yugiohmdpro;

import net.minecraftforge.fml.common.Mod;

@Mod(MdproAddon.MOD_ID)
public final class MdproAddon {
    public static final String MOD_ID = "yugioh_mdpro_addon";
    public MdproAddon() {
        dev.codex.yugiohmdpro.content.ContentItems.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        dev.codex.yugiohmdpro.duel.DuelNetwork.register();
    }
}
