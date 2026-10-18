package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.item.CardItem;
import dev.codex.yugiohmdpro.client.ui.DuelUi;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserve every paragraph, including both Pendulum and monster effects. */
@Mixin(value=CardItem.class,remap=false)
public abstract class CardRulesWrapMixin {
    @Inject(method="wrap",at=@At("HEAD"),cancellable=true)
    private static void mdpro$fullRules(String text,CallbackInfoReturnable<List<Component>> ci) {
        List<Component> lines=new ArrayList<>();
        for(String line:DuelUi.wrap(Minecraft.getInstance().font,text.replace("\r\n","\n").replace('\r','\n'),208,Integer.MAX_VALUE))
            lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        ci.setReturnValue(lines);
    }
}
