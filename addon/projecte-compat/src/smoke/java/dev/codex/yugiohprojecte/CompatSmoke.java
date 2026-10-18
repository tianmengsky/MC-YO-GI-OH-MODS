package dev.codex.yugiohprojecte;

import com.kiki.yugioh.item.*;
import com.kiki.yugioh.card.CardRarity;
import java.nio.file.*;
import java.util.*;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.event.PlayerAttemptLearnEvent;
import moze_intel.projecte.api.proxy.IEMCProxy;
import moze_intel.projecte.emc.EMCMappingHandler;
import moze_intel.projecte.emc.mappers.APICustomEMCMapper;
import moze_intel.projecte.network.packets.to_client.SyncEmcPKT.EmcPKTInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid=YugiohProjectE.ID, value=Dist.CLIENT)
public final class CompatSmoke {
    private static boolean done;
    private static ItemStack stack(String id) { return new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id))); }
    private static void check(boolean test, String message) { if (!test) throw new AssertionError(message); }
    @SubscribeEvent public static void run(ScreenEvent.Render.Post event) {
        if (done || !Boolean.getBoolean("yugioh.projecte.smoke") || !(event.getScreen() instanceof TitleScreen)
                || Minecraft.getInstance().getOverlay()!=null) return;
        done=true;
        Path result=FMLPaths.GAMEDIR.get().resolve("../generated/projecte-smoke-result.txt").normalize();
        try {
            // Read the registrations ProjectE actually accepted, then emulate its client sync packet.
            var field=APICustomEMCMapper.class.getDeclaredField("customEMCforMod");field.setAccessible(true);
            var mods=(Map<?,?>)field.get(APICustomEMCMapper.INSTANCE);
            var values=(Map<?,?>)mods.get(YugiohProjectE.ID);
            check(values!=null,"ProjectE never received custom EMC registration");
            var packets=new ArrayList<EmcPKTInfo>();
            for(var entry:values.entrySet()) {
                ItemInfo info=ItemInfo.fromNSS((moze_intel.projecte.api.nss.NSSItem)entry.getKey());
                check(info!=null,"Unregistered EMC item");
                packets.add(new EmcPKTInfo(info.getItem(),info.getNBT(),(Long)entry.getValue()));
            }
            long expected=ForgeRegistries.ITEMS.getKeys().stream().filter(EmcValues::target).count();
            check(packets.size()==expected,"Incomplete registry coverage: "+packets.size()+" / "+expected);
            EMCMappingHandler.fromPacket(packets.toArray(EmcPKTInfo[]::new));
            var coverage=new StringBuilder("item\tregistered_emc\tdefault_stack_emc\n");
            for(var packet:packets) {
                ItemStack sample=new ItemStack(packet.item());
                long actual=IEMCProxy.INSTANCE.getValue(sample);
                boolean forbidden=YugiohNBT.isForbiddenContainer(sample);
                check(forbidden ? actual==0 : actual>0,"Missing or unsafe EMC: "+ForgeRegistries.ITEMS.getKey(sample.getItem()));
                coverage.append(ForgeRegistries.ITEMS.getKey(sample.getItem())).append('\t').append(EmcValues.base(sample.getItem())).append('\t').append(actual).append('\n');
            }
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("../generated/projecte-compat/item-emc-values.tsv").normalize(),coverage);
            var blue=CardItem.withRarity(stack("yugioh:blue_eyes_white_dragon"),CardRarity.SECRET_RARE);
            check(IEMCProxy.INSTANCE.getValue(blue)>0,"Card needs EMC");
            check(ItemStack.isSameItemSameTags(blue,IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromStack(blue)).createStack()),"Rarity lost");
            var emptyBox=stack("yugioh:deck_box");
            DeckContents.save(emptyBox,DeckContents.handlerFor(emptyBox));
            check(IEMCProxy.INSTANCE.getValue(emptyBox)>0,"Empty box needs EMC");
            var box=emptyBox.copy();var handler=DeckContents.handlerFor(box);
            handler.setStackInSlot(75,blue.copy());DeckContents.save(box,handler);
            check(YugiohNBT.isForbiddenContainer(box),"Side deck contents not detected");
            check(IEMCProxy.INSTANCE.getValue(box)==0,"Filled box has EMC");
            var glove=stack("ygostar:gloves");
            check(YugiohNBT.isForbiddenContainer(glove),"New glove's default stars not detected");
            check(IEMCProxy.INSTANCE.getValue(glove)==0,"New glove has EMC");
            var inventory=(IItemHandlerModifiable)glove.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new);
            for(int i=0;i<inventory.getSlots();i++)inventory.setStackInSlot(i,ItemStack.EMPTY);
            check(!YugiohNBT.isForbiddenContainer(glove),"Empty glove rejected");
            check(IEMCProxy.INSTANCE.getValue(glove)>0,"Empty glove needs EMC");
            var restored=IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromStack(glove)).createStack();
            check(!YugiohNBT.isForbiddenContainer(restored),"Transmutation restored default stars into empty glove");
            var filledGlove=stack("ygostar:gloves_7");
            for(var blocked:List.of(box,filledGlove)) {
                var info=ItemInfo.fromStack(blocked);
                var learn=new PlayerAttemptLearnEvent(null,info,IEMCProxy.INSTANCE.getPersistentInfo(info));
                check(MinecraftForge.EVENT_BUS.post(learn),"Learn event did not block contents");
            }
            // Slot validation has no inventory dependency. Allocate without constructing a player/world.
            var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
            var unsafe=(sun.misc.Unsafe)unsafeField.get(null);
            for(String name:List.of("SlotInput","SlotConsume","SlotLock","SlotUnlearn")) {
                var cls=Class.forName("moze_intel.projecte.gameObjs.container.slots.transmutation."+name);
                Slot slot=(Slot)unsafe.allocateInstance(cls);
                check(!slot.mayPlace(box),name+" accepted filled box");
                check(!slot.mayPlace(filledGlove),name+" accepted glove stars");
            }
            Files.writeString(result,"PASS: ProjectE accepted "+expected+" item registrations; card rarity retained; empty box/glove EMC positive; filled box and default-star gloves EMC zero; empty glove roundtrip stays empty; learn events and all four table slot types reject contents. Client EMC sync simulated from actual IMC registrations; no player world used.\n");
        } catch(Throwable error) {
            org.slf4j.LoggerFactory.getLogger("YugiohProjectESmoke").error("Compatibility smoke failed",error);
            try {Files.writeString(result,"FAIL: "+error+"\n");}catch(Exception ignored){}
        }
        Minecraft.getInstance().stop();
    }
}
