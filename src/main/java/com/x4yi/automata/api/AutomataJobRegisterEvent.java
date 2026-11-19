package com.x4yi.automata.api;

import net.minecraftforge.fml.common.eventhandler.Event;

public class AutomataJobRegisterEvent extends Event {

    private final AutomatonJobRegistry registry;

    public AutomataJobRegisterEvent(AutomatonJobRegistry registry) {
        this.registry = registry;
    }

    public AutomatonJobRegistry getRegistry() {
        return registry;
    }

    public void register(JobDescriptor descriptor) {
        registry.register(descriptor);
    }
}

