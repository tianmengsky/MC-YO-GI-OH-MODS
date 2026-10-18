package dev.codex.yugiohmdpro.content;
import com.kiki.yugioh.item.CardItem;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon")
public final class ImportedCardDetails {
    @SubscribeEvent public static void tooltip(ItemTooltipEvent event) {
        var definition=CardItem.definitionOf(event.getItemStack());if(definition==null)return;
        var card=HistoricalContent.CARDS.get(definition.getId());if(card==null)return;
        int t=HistoricalContent.num(card,"type"),lv=HistoricalContent.num(card,"level_packed");
        if((t&0x1000000)!=0)event.getToolTip().add(Component.literal("灵摆刻度 "+((lv>>>24)&255)+" / "+((lv>>>16)&255)).withStyle(ChatFormatting.AQUA));
        event.getToolTip().add(Component.literal(String.format("密码 %08d · CID %d",HistoricalContent.num(card,"code"),HistoricalContent.num(card,"cid"))).withStyle(ChatFormatting.DARK_GRAY));
    }
}
