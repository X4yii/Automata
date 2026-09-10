
package com.x4yi.automata.entity;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.EntityEntryBuilder;

import com.x4yi.automata.AutomataMod;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class ModEntities {

    private ModEntities() {}

    @SubscribeEvent
    public static void registerEntities(RegistryEvent.Register<EntityEntry> event) {
        event.getRegistry().register(
                EntityEntryBuilder.create()
                        .entity(EntityAutomaton.class)
                        .id(new ResourceLocation(AutomataMod.MOD_ID, "automaton"), 0)
                        .name(AutomataMod.MOD_ID + ".automaton")
                        .tracker(64, 3, true)
                        .build());
    }
}

