package dev.codex.yugiohmdpro.rules;

import com.kiki.yugioh.card.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.exceptions.*;
import dev.codex.yugiohmdpro.content.HistoricalContent;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon")
public final class BanlistCommands {
    private static final DynamicCommandExceptionType UNKNOWN=new DynamicCommandExceptionType(s->Component.literal("未找到唯一卡片："+s+"。请使用卡片密码或物品 ID。"));
    public static CardDefinition resolve(String value) throws CommandSyntaxException {
        String id=value.startsWith("yugioh:")?value.substring(7):value.startsWith("minecraft:")?value.substring(10):value;
        var card=Cards.byId(id);if(card!=null)return card;
        try {var row=HistoricalContent.BY_CODE.get(Integer.parseInt(id));if(row!=null)return Cards.byId(HistoricalContent.str(row,"id"));}catch(NumberFormatException ignored){}
        var matches=HistoricalContent.CARDS.values().stream().filter(c->HistoricalContent.str(c,"name").equals(value)||HistoricalContent.str(c,"name_en").equalsIgnoreCase(value)||HistoricalContent.str(c,"name_ja").equals(value)).toList();
        if(!matches.isEmpty()&&matches.stream().map(c->ServerBanlist.key(HistoricalContent.str(c,"id"))).distinct().count()==1)return Cards.byId(HistoricalContent.str(matches.get(0),"id"));
        throw UNKNOWN.create(value);
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,ResourceLocation> cardArg() {
        return Commands.argument("card",ResourceLocationArgument.id()).suggests((ctx,builder)->SharedSuggestionProvider.suggest(HistoricalContent.CARDS.values().stream().map(c->HistoricalContent.str(c,"id")),builder));
    }
    private static int list(CommandSourceStack source,int page) {
        var entries=ServerBanlist.entries();int pages=Math.max(1,(entries.size()+11)/12);
        if(page>pages){source.sendFailure(Component.literal("页码超出范围，共 "+pages+" 页。"));return 0;}
        source.sendSuccess(()->Component.literal("服务器自定义禁限共 "+entries.size()+" 项（"+page+"/"+pages+" 页）。未列出的卡默认最多 3 张。"),false);
        for(int i=(page-1)*12;i<Math.min(page*12,entries.size());i++) {
            var e=entries.get(i);source.sendSuccess(()->Component.translatable("item.yugioh."+e.id()).append(Component.literal(" ["+e.id()+"]："+e.limit())),false);
        }
        return 1;
    }
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var tree=Commands.literal("banlist").requires(s->s.hasPermission(2))
            .executes(c->{c.getSource().sendSuccess(()->Component.literal("服务器禁限表：set <卡片密码或ID> <0禁用/1限制/2准限/3解除>；get <卡片>；list；clear。只检查规则，不改写卡片或卡组盒。"),false);return 1;})
            .then(Commands.literal("set").then(cardArg().then(Commands.argument("limit",IntegerArgumentType.integer(0,3)).executes(c->{
                var card=resolve(ResourceLocationArgument.getId(c,"card").toString());int limit=IntegerArgumentType.getInteger(c,"limit");
                ServerBanlist.get(c.getSource().getServer()).set(c.getSource().getServer(),card.getId(),limit);
                c.getSource().sendSuccess(()->Component.literal("服务器禁限已更新：").append(Component.translatable("item.yugioh."+card.getId())).append(Component.literal(" = "+limit+" 张（同名卡合计）。")),true);return 1;
            }))))
            .then(Commands.literal("get").then(cardArg().executes(c->{var card=resolve(ResourceLocationArgument.getId(c,"card").toString());c.getSource().sendSuccess(()->Component.translatable("item.yugioh."+card.getId()).append(Component.literal("：最多 "+ServerBanlist.limit(card.getId())+" 张（同名卡合计）。")),false);return 1;})))
            .then(Commands.literal("list").executes(c->list(c.getSource(),1)).then(Commands.argument("page",IntegerArgumentType.integer(1)).executes(c->list(c.getSource(),IntegerArgumentType.getInteger(c,"page")))))
            .then(Commands.literal("clear").executes(c->{ServerBanlist.get(c.getSource().getServer()).clear(c.getSource().getServer());c.getSource().sendSuccess(()->Component.literal("已清空服务器禁限表；所有卡恢复默认最多 3 张。卡片与卡组盒内容保持原样。"),true);return 1;}));
        event.getDispatcher().register(Commands.literal("mdpro").then(tree));
    }
}
