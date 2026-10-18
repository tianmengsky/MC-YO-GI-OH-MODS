package dev.codex.yugiohmdpro.content;
import com.google.gson.JsonObject;
import com.kiki.yugioh.item.CardItem;
import com.kiki.yugioh.item.DeckContents;
import com.kiki.yugioh.card.CardRarity;
import com.kiki.yugioh.registry.ModItems;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.fml.DistExecutor;

public final class HistoricalProductItem extends Item {
    private final JsonObject product;
    private final List<JsonObject> pool;
    public HistoricalProductItem(JsonObject product){super(new Item.Properties().stacksTo(16));this.product=product;pool=HistoricalContent.playableRows(product);}
    public JsonObject product(){return product;}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
        ItemStack stack=player.getItemInHand(hand);
        if(player.isShiftKeyDown()) {
            if(level.isClientSide)DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->dev.codex.yugiohmdpro.client.ProductClient.open(product));
            return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
        }
        if(!level.isClientSide&&player instanceof ServerPlayer server) {
            if(pool.isEmpty()){server.sendSystemMessage(Component.literal("此产品仅收录不可决斗的纪念卡；Shift + 右键查看收录。"));return InteractionResultHolder.fail(stack);}
            List<JsonObject> drawn=new ArrayList<>();
            if(HistoricalContent.booster(product))for(int i=0;i<5;i++)drawn.add(pool.get(player.getRandom().nextInt(pool.size())));
            else {
                ItemStack box=buildDeckBox(product);
                if(!server.getInventory().add(box))server.drop(box,false);
            }
            for(var row:drawn) {
                var item=ModItems.cardItem(HistoricalContent.str(row,"id"));
                if(item==null)throw new IllegalStateException("Missing imported card item: "+row);
                String marks=HistoricalContent.str(row,"rarity_ids");
                var nativeRarities=Arrays.stream(marks.split(",")).filter(s->!s.isBlank()).map(s->switch(s){
                    case "2"->CardRarity.RARE;case "3"->CardRarity.SUPER_RARE;case "4"->CardRarity.ULTRA_RARE;case "5"->CardRarity.SECRET_RARE;default->CardRarity.COMMON;
                }).distinct().toList();
                CardRarity rarity=nativeRarities.isEmpty()?CardRarity.COMMON:nativeRarities.get(player.getRandom().nextInt(nativeRarities.size()));
                ItemStack card=CardItem.withRarity(new ItemStack(item),rarity);
                card.getOrCreateTag().putString("HistoricalProduct",HistoricalContent.str(product,"product_id"));
                card.getOrCreateTag().putString("OfficialRarityIds",HistoricalContent.str(row,"rarity_ids"));
                if(!server.getInventory().add(card))server.drop(card,false);
            }
            stack.shrink(1);server.getInventory().setChanged();
            server.sendSystemMessage(Component.literal(HistoricalContent.str(product,"name")+(HistoricalContent.booster(product)?"：获得 5 张收录卡。":"：获得装有 40 张主卡的卡组盒。")));
            level.playSound(null,player.blockPosition(),SoundEvents.EXPERIENCE_ORB_PICKUP,SoundSource.PLAYERS,0.7f,1.0f);
        }
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,Level level,List<Component> lines,TooltipFlag flag) {
        lines.add(Component.literal(HistoricalContent.kindLabel(product)+" · "+HistoricalContent.str(product,"locale").toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY));
        String date=HistoricalContent.str(product,"release_date");if(date.isEmpty())date=HistoricalContent.str(product,"index_year")+" 年（日期未知）";
        lines.add(Component.literal(date+" · "+product.getAsJsonArray("cards").size()+" 种收录 / "+pool.size()+" 种可决斗").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(HistoricalContent.booster(product)?"右键：随机 5 张（游戏规则，各收录卡等概率）":"右键：获得 40 张主卡的卡组盒（含配套额外卡）").withStyle(ChatFormatting.GOLD));
        if(!HistoricalContent.booster(product)&&!HistoricalContent.str(product.getAsJsonObject("deck_recipe"),"basis").startsWith("physical set list"))
            lines.add(Component.literal("依对应产品收录整理为 40 张游戏预组").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Shift + 右键：查看封面及全部收录").withStyle(ChatFormatting.AQUA));
        if(!product.get("cover").getAsBoolean())lines.add(Component.literal("尚无已核验的产品封面").withStyle(ChatFormatting.DARK_GRAY));
    }
    /** Same 90-slot NBT layout and deck-box item as original StructureDeckItem. */
    public static ItemStack buildDeckBox(JsonObject product) {
        var recipe=product.getAsJsonObject("deck_recipe");
        if(recipe==null)throw new IllegalStateException("Missing reviewed deck recipe: "+product);
        ItemStack box=new ItemStack(ModItems.DECK_BOX.get());
        var handler=DeckContents.handlerFor(box);
        int total=0;
        for(String section:List.of("main","extra")) {
            int slot=section.equals("main")?0:60;
            for(var e:recipe.getAsJsonArray(section)) {
                var row=e.getAsJsonObject();int count=HistoricalContent.num(row,"count");
                var card=ModItems.cardItem(HistoricalContent.str(row,"id"));
                if(card==null||count<1||count>3)throw new IllegalStateException("Invalid deck recipe member: "+row);
                var stack=CardItem.withRarity(new ItemStack(card,count),CardRarity.COMMON);
                stack.getOrCreateTag().putString("HistoricalProduct",HistoricalContent.str(product,"product_id"));
                handler.setStackInSlot(slot++,stack);if(section.equals("main"))total+=count;
            }
        }
        if(total!=40)throw new IllegalStateException("Historical deck main count must be 40");
        DeckContents.save(box,handler);box.setHoverName(Component.literal(HistoricalContent.str(product,"name")));
        box.getOrCreateTag().putString("HistoricalProduct",HistoricalContent.str(product,"product_id"));
        return box;
    }
}
