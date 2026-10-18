package dev.codex.yugiohmaid;

import net.minecraftforge.fml.common.Mod;

@Mod(TouhouMaidDuel.MOD_ID)
public final class TouhouMaidDuel {
    public static final String MOD_ID = "touhou_maid_duel";

    public TouhouMaidDuel() {
        MaidDuelNetwork.register();
    }
}
