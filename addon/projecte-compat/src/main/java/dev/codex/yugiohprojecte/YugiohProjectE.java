package dev.codex.yugiohprojecte;

import moze_intel.projecte.api.imc.CustomEMCRegistration;
import moze_intel.projecte.api.imc.IMCMethods;
import moze_intel.projecte.api.nss.NSSItem;
import moze_intel.projecte.api.event.PlayerAttemptLearnEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(YugiohProjectE.ID)
public final class YugiohProjectE {
    public static final String ID = "yugioh_projecte";
    public YugiohProjectE() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::enqueue);
        MinecraftForge.EVENT_BUS.addListener(this::denyStoredItems);
    }
    private void denyStoredItems(PlayerAttemptLearnEvent event) {
        ItemStack stack = event.getSourceInfo().createStack();
        if (YugiohNBT.isForbiddenContainer(stack)) event.setCanceled(true);
    }
    private void enqueue(InterModEnqueueEvent event) {
        EmcValues.load();
        ForgeRegistries.ITEMS.getEntries().forEach(entry -> {
            var id = entry.getKey().location();
            if (EmcValues.target(id)) {
                long value = EmcValues.base(entry.getValue());
                InterModComms.sendTo("projecte", IMCMethods.REGISTER_CUSTOM_EMC,
                    () -> new CustomEMCRegistration(NSSItem.createItem(id), value));
            }
        });
    }
}
