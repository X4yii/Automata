package com.x4yi.automata.api.event;

import net.minecraft.entity.Entity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

import com.x4yi.automata.entity.EntityAutomaton;

@Cancelable
public class BotAttackEvent extends Event {

    private final EntityAutomaton automaton;
    private final WorldServer world;
    private final Entity target;

    public BotAttackEvent(EntityAutomaton automaton, WorldServer world, Entity target) {
        this.automaton = automaton;
        this.world = world;
        this.target = target;
    }

    public EntityAutomaton getAutomaton() {
        return automaton;
    }

    public WorldServer getWorld() {
        return world;
    }

    public Entity getTarget() {
        return target;
    }
}

