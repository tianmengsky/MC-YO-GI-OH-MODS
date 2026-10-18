package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.card.CardType;
import com.kiki.yugioh.client.screen.CardCatalogScreen;
import java.util.List;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reviewed access to the original catalog layout, used by paging and by the acceptance fixture. */
@Mixin(value=CardCatalogScreen.class,remap=false)
public interface CatalogAccess {
    @Invoker("buildChips") void mdpro$buildChips();
    @Invoker("panelLeft") int mdpro$panelLeft();
    @Invoker("panelTop") int mdpro$panelTop();
    @Invoker("headerHeight") int mdpro$headerHeight();
    @Invoker("panelHeight") int mdpro$panelHeight();
    @Invoker("visibleRows") int mdpro$visibleRows();
    @Invoker("refresh") void mdpro$refresh();
    @Accessor("chips") List<Object> mdpro$chips();
    @Accessor("search") EditBox mdpro$search();
    @Accessor("results") List<CardDefinition> mdpro$results();
    @Accessor("sorted") List<CardDefinition> mdpro$sorted();
    @Accessor("scroll") int mdpro$scroll();
    @Accessor("scroll") void mdpro$scroll(int scroll);
    @Accessor("typeFilter") CardType mdpro$typeFilter();
    @Accessor("typeFilter") void mdpro$typeFilter(CardType type);
    @Accessor("attributeFilter") com.kiki.yugioh.card.Attribute mdpro$attribute();
    @Accessor("attributeFilter") void mdpro$attribute(com.kiki.yugioh.card.Attribute value);
    @Accessor("monsterTypeFilter") com.kiki.yugioh.card.MonsterType mdpro$race();
    @Accessor("monsterTypeFilter") void mdpro$race(com.kiki.yugioh.card.MonsterType value);
    @Accessor("levelFilter") int mdpro$level();
    @Accessor("levelFilter") void mdpro$level(int value);
    @Accessor("categoryFilter") com.kiki.yugioh.card.MonsterCategory mdpro$category();
    @Accessor("categoryFilter") void mdpro$category(com.kiki.yugioh.card.MonsterCategory value);
    @Accessor("spellKindFilter") com.kiki.yugioh.card.SpellKind mdpro$spell();
    @Accessor("spellKindFilter") void mdpro$spell(com.kiki.yugioh.card.SpellKind value);
    @Accessor("trapKindFilter") com.kiki.yugioh.card.TrapKind mdpro$trap();
    @Accessor("trapKindFilter") void mdpro$trap(com.kiki.yugioh.card.TrapKind value);
    @Accessor("takeRarity") com.kiki.yugioh.card.CardRarity mdpro$rarity();
    @Accessor("takeRarity") void mdpro$rarity(com.kiki.yugioh.card.CardRarity value);
    @Invoker("setType") void mdpro$setType(CardType value);
    @Invoker("take") void mdpro$take(CardDefinition value);
    @Invoker("tooltipOf") List<net.minecraft.network.chat.Component> mdpro$tooltip(CardDefinition value);
}
