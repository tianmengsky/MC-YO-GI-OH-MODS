package dev.codex.yugiohmdpro.mixin;
import dev.codex.yugiohfix.engine.BanList;
import dev.codex.yugiohmdpro.rules.ServerBanlist;
import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Replace the legacy baked-in list for both original validation and tooltip APIs. */
@Mixin(value=BanList.class,remap=false)
public abstract class ServerBanlistMixin {
    @Inject(method="limitOf(Ljava/lang/String;)I",at=@At("HEAD"),cancellable=true)
    private static void mdpro$limit(String id,CallbackInfoReturnable<Integer> ci){ci.setReturnValue(ServerBanlist.limit(id));}
    @Inject(method="entries",at=@At("HEAD"),cancellable=true)
    private static void mdpro$entries(CallbackInfoReturnable<List<BanList.Entry>> ci){ci.setReturnValue(ServerBanlist.entries());}
    @Inject(method="source",at=@At("HEAD"),cancellable=true)
    private static void mdpro$source(CallbackInfoReturnable<String> ci){ci.setReturnValue("服务器自定义禁限表（OP 管理）");}
    @Inject(method="effectiveFrom",at=@At("HEAD"),cancellable=true)
    private static void mdpro$date(CallbackInfoReturnable<String> ci){ci.setReturnValue("动态生效");}
    @Inject(method="unknownIds",at=@At("HEAD"),cancellable=true)
    private static void mdpro$unknown(CallbackInfoReturnable<List<String>> ci){ci.setReturnValue(List.of());}
}
