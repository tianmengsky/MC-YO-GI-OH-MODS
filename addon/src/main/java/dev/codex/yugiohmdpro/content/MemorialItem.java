package dev.codex.yugiohmdpro.content;
import com.google.gson.JsonObject;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.List;
public final class MemorialItem extends Item {
    private final JsonObject data;
    public MemorialItem(JsonObject data){super(new Item.Properties().stacksTo(1));this.data=data;}
    public JsonObject data(){return data;}
    @Override public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer){consumer.accept(new dev.codex.yugiohmdpro.client.ProductClient.Extensions());}
    @Override public void appendHoverText(ItemStack stack,Level level,List<Component> lines,TooltipFlag flag) {
        lines.add(Component.literal("纪念奖品卡 · 官方标注不可用于决斗").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("KONAMI CID "+HistoricalContent.str(data,"cid")).withStyle(ChatFormatting.GRAY));
    }
}
