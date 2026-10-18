package dev.codex.yugiohmdpro.mixin;
import dev.codex.yugiohfix.shop.ShopTrade;
import dev.codex.yugiohmdpro.content.*;
import net.minecraft.world.item.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=ShopTrade.class,remap=false)
public abstract class HistoricalShopMixin {
    @Shadow private static List<ItemStack> copy(List<ItemStack> bag){throw new AssertionError();}
    @Shadow private static String payAndInsert(List<ItemStack> bag,int price,ItemStack stack){throw new AssertionError();}
    @Shadow private static String payAndInsert(List<ItemStack> bag,Item currency,int price,ItemStack stack,String insufficient){throw new AssertionError();}
    @Shadow private static void commit(List<ItemStack> bag,List<ItemStack> after){throw new AssertionError();}
    @Inject(method="packs",at=@At("RETURN"),cancellable=true)
    private static void mdpro$packs(CallbackInfoReturnable<List<ItemStack>> ci) {
        var result=new ArrayList<>(ci.getReturnValue());
        result.removeIf(s->!HistoricalContent.legacyVisible(s.getItem()));
        for(var ref:ContentItems.PRODUCTS.values()) {
            var item=(HistoricalProductItem)ref.get();if(HistoricalContent.booster(item.product())&&!HistoricalContent.playableRows(item.product()).isEmpty())result.add(new ItemStack(item));
        }
        ci.setReturnValue(List.copyOf(result));
    }
    @Inject(method="decks",at=@At("RETURN"),cancellable=true)
    private static void mdpro$decks(CallbackInfoReturnable<List<ItemStack>> ci) {
        var result=new ArrayList<>(ci.getReturnValue());
        result.removeIf(s->!HistoricalContent.legacyVisible(s.getItem()));
        for(var ref:ContentItems.PRODUCTS.values()) {
            var item=(HistoricalProductItem)ref.get();
            if(!HistoricalContent.booster(item.product())&&!HistoricalContent.playableRows(item.product()).isEmpty())result.add(new ItemStack(item));
        }
        ci.setReturnValue(List.copyOf(result));
    }
    @Inject(method="buyDeck",at=@At("HEAD"),cancellable=true)
    private static void mdpro$buyDeck(List<ItemStack> bag,String key,CallbackInfoReturnable<String> ci) {
        var location=key==null?null:ResourceLocation.tryParse(key);
        if(location!=null&&location.getNamespace().equals("yugioh")&&!HistoricalContent.LEGACY_PRODUCTS.containsKey(location.toString())) {ci.setReturnValue("该海外版预组已停止出售，请选择日版产品");return;}
        if(location==null||!location.getNamespace().equals("yugioh_mdpro_addon"))return;
        if(!ContentItems.PRODUCTS.containsKey(location.getPath())||!(ForgeRegistries.ITEMS.getValue(location) instanceof HistoricalProductItem item)||HistoricalContent.booster(item.product())||HistoricalContent.playableRows(item.product()).isEmpty()) {
            ci.setReturnValue("无效的历史预组");return;
        }
        var after=copy(bag);
        String error=payAndInsert(after,dev.codex.yugiohfix.item.GoldCardItems.GOLD_CARD.get(),1,new ItemStack(item),"金卡不足");
        if(error.isEmpty())commit(bag,after);ci.setReturnValue(error);
    }
    @Inject(method="buyPack",at=@At("HEAD"),cancellable=true)
    private static void mdpro$buy(List<ItemStack> bag,String key,CallbackInfoReturnable<String> ci) {
        var location=key==null?null:ResourceLocation.tryParse(key);
        if(location!=null&&location.getNamespace().equals("yugioh")&&!HistoricalContent.LEGACY_PRODUCTS.containsKey(location.toString())) {ci.setReturnValue("该海外版卡包已停止出售，请选择日版产品");return;}
        if(location==null||!location.getNamespace().equals("yugioh_mdpro_addon"))return;
        if(!ContentItems.PRODUCTS.containsKey(location.getPath())||!(ForgeRegistries.ITEMS.getValue(location) instanceof HistoricalProductItem item)||!HistoricalContent.booster(item.product())) {
            ci.setReturnValue("无效的历史卡包");return;
        }
        var after=copy(bag);String error=payAndInsert(after,6,new ItemStack(item));
        if(error.isEmpty())commit(bag,after);ci.setReturnValue(error);
    }
}
