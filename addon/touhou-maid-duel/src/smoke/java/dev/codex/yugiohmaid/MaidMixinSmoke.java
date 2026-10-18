package dev.codex.yugiohmaid;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** Opt-in fixture only; excluded from the distributed jar. */
@Mod.EventBusSubscriber(modid = TouhouMaidDuel.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MaidMixinSmoke {
    @SubscribeEvent
    public static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                Class<?> formats = Class.forName("com.kiki.yugioh.duel.MatchFormats");
                Class<?> match = Class.forName("com.kiki.yugioh.duel.Match");
                if (java.util.Arrays.stream(formats.getDeclaredMethods()).noneMatch(m -> m.getName().contains("confirmedSolo")))
                    throw new AssertionError("Format confirmation redirect missing");
                if (java.util.Arrays.stream(match.getDeclaredFields()).noneMatch(f -> f.getName().contains("touhouMaidDuel$session")))
                    throw new AssertionError("Match session injection missing");
                org.slf4j.LoggerFactory.getLogger("MaidMixinSmoke").info("MAID_MIXIN_SMOKE_PASS: format confirmation and Match injections transformed in production Forge");
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }
}
