package com.x4yi.automata.ai;

import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.api.IAutomatonContext;

/**
 * Contract for an automaton job behavior.
 */
public interface IAutomatonJob {

    ResourceLocation getJobId();

    void tick(IAutomatonContext ctx);

    default void onStop(IAutomatonContext ctx) {
    }

    default boolean hasCommittedWork(IAutomatonContext ctx) {
        return false;
    }
}
