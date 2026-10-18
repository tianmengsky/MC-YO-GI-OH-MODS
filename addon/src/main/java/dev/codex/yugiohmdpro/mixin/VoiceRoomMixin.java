package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.duel.Duel;
import dev.codex.yugiohfix.voice.ServerVoiceRooms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes the interaction-fix mod's private voice room feature.
 *
 * <p>The feature creates a Simple Voice Chat group for every duel and is requested by default
 * ({@code ClientDuelRules.requestedPrivateVoiceRoom = true}). This addon does not want duels to open
 * a voice room, so both entry points are cancelled: no room is created, no voice-chat group is
 * joined, and nothing is registered even if Simple Voice Chat is installed later. The reviewed
 * interaction-fix jar itself is left untouched; see also {@link ClientDuelRulesMixin}, which pins the
 * user-facing rule to "off".</p>
 */
@Mixin(value=ServerVoiceRooms.class, remap=false)
public abstract class VoiceRoomMixin {
    @Inject(method="start", at=@At("HEAD"), cancellable=true, require=0)
    private static void mdpro$noVoiceRoom(Duel duel, CallbackInfo ci) { ci.cancel(); }

    @Inject(method="stop", at=@At("HEAD"), cancellable=true, require=0)
    private static void mdpro$noVoiceRoomStop(Duel duel, CallbackInfo ci) { ci.cancel(); }
}
