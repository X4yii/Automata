package com.x4yi.automata.world;

import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class CropTrampleProtectionHandler {

    private CropTrampleProtectionHandler() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!AutomataConfig.preventAutomatonCropTrampling) {
            return;
        }
        if (event.getEntity() instanceof EntityAutomaton) {
            event.setCanceled(true);
        }
    }
}

