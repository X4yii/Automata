
package com.x4yi.automata.client;

import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.entity.EntityAutomaton;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID, value = Side.CLIENT)
public final class ClientInit {

    private ClientInit() {}

    @SubscribeEvent
    public static void registerRenderers(ModelRegistryEvent event) {
        RenderingRegistry.registerEntityRenderingHandler(EntityAutomaton.class, RenderAutomaton::new);
    }
}

