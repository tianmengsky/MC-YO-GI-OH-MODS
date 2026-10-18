package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.ClientDuelHandler;
import com.kiki.yugioh.network.S2CDuelStatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=ClientDuelHandler.class,remap=false)
public interface ClientDuelAccess {
    @Accessor("state") static void mdpro$state(S2CDuelStatePacket state) {throw new AssertionError();}
}
