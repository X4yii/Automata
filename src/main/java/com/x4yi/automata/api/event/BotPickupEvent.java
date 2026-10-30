package com.x4yi.automata.api.event;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Fired on {@code MinecraftForge.EVENT_BUS} before an automaton picks up a ground item.
 */
@Cancelable
public class BotPickupEvent extends Event {

    private final EntityAutomaton automaton;
    private final EntityItem itemEntity;
    private final ItemStack stack;

    public BotPickupEvent(EntityAutomaton automaton, EntityItem itemEntity, ItemStack stack) {
        this.automaton = automaton;
        this.itemEntity = itemEntity;
        this.stack = stack;
    }

    public EntityAutomaton getAutomaton() {
        return automaton;
    }

    public EntityItem getItemEntity() {
        return itemEntity;
    }

    public ItemStack getStack() {
        return stack;
    }
}

