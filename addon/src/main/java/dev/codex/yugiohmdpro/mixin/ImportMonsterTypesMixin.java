package dev.codex.yugiohmdpro.mixin;
import com.kiki.yugioh.card.MonsterType;
import java.util.Arrays;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=MonsterType.class,remap=false)
public abstract class ImportMonsterTypesMixin {
    @Shadow @Final @Mutable private static MonsterType[] $VALUES;
    @Invoker("<init>") private static MonsterType mdpro$new(String name,int ordinal,String id){throw new AssertionError();}
    @Inject(method="<clinit>",at=@At("TAIL"))
    private static void mdpro$types(CallbackInfo ci) {
        for(String name:new String[]{"PSYCHIC","CREATOR_GOD","WYRM"}) {
            var t=mdpro$new(name,$VALUES.length,name.toLowerCase(java.util.Locale.ROOT));
            $VALUES=Arrays.copyOf($VALUES,$VALUES.length+1);$VALUES[$VALUES.length-1]=t;
        }
    }
}
