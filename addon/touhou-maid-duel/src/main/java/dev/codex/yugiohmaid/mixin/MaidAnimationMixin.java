package dev.codex.yugiohmaid.mixin;

import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.client.animation.HardcodedAnimationManger;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import dev.codex.yugiohmaid.client.MaidDuelAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.HashMap;

@Mixin(value = HardcodedAnimationManger.class, remap = false)
public abstract class MaidAnimationMixin {
    @Inject(method = "playGeckoMaidAnimation", at = @At("TAIL"))
    private static void maidDuel$gecko(IMaid maid, AnimatedGeoModel model, float swing, float amount,
                                       float age, float yaw, float pitch, CallbackInfo ci) {
        MaidDuelAnimation.gecko(maid, model);
    }

    @Inject(method = "playMaidAnimation", at = @At("TAIL"))
    private static void maidDuel$classic(IMaid maid, HashMap<String, ModelRendererWrapper> models,
                                         float swing, float amount, float age, float yaw, float pitch, CallbackInfo ci) {
        MaidDuelAnimation.bedrock(maid, models);
    }
}
