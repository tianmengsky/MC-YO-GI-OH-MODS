package dev.codex.yugiohmdpro.client;

import com.kiki.yugioh.card.MonsterType;
import com.kiki.yugioh.item.CardItem;
import dev.codex.yugiohfix.client.BinderFilter;
import dev.codex.yugiohfix.client.BinderClient;
import net.minecraft.world.item.ItemStack;

public final class DeckFilterModel implements CardFilterPanel.Model {
    public static final DeckFilterModel INSTANCE=new DeckFilterModel();
    private MonsterType race;
    private DeckFilterModel() {}
    public boolean raceActive() {return race!=null;}
    public void clearRace() {race=null;}
    public boolean passes(ItemStack stack) {var d=CardItem.definitionOf(stack);return race==null||d!=null&&d.isMonster()&&d.getMonsterType()==race;}
    public int selected(String group) {
        return switch(group) {
            case "rarity" -> BinderFilter.rarity()==null?-1:BinderFilter.rarities().indexOf(BinderFilter.rarity());
            case "type" -> BinderFilter.type()==null?-1:BinderFilter.types().indexOf(BinderFilter.type());
            case "attribute" -> BinderFilter.attribute()==null?-1:CardFilterPanel.attributes().indexOf(BinderFilter.attribute());
            case "level" -> BinderFilter.level()-1;
            case "race" -> race==null?-1:CardFilterPanel.races().indexOf(race);
            default -> -1;
        };
    }
    public void choose(String group,int index) {
        if(group.equals("race")) {var value=CardFilterPanel.races().get(index);race=race==value?null:value;}
        else {
            var category=switch(group) {case "rarity"->BinderFilter.Category.RARITY;case "type"->BinderFilter.Category.TYPE;
                case "attribute"->BinderFilter.Category.ATTRIBUTE;default->BinderFilter.Category.LEVEL;};
            BinderFilter.apply(new BinderFilter.Hit(category,index+1));
        }
        BinderClient.setStripScroll(0);
    }
    public void reset() {BinderFilter.clear();race=null;BinderClient.setStripScroll(0);}
}
