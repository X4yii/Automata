package com.x4yi.automata.ai;

import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.api.IAutomatonContext;

/**
 * Contract for an automaton job behavior.
 */
public interface IAutomatonJob {

    default AutomatonJobType getType() {
        return AutomatonJobType.IDLE;
    }

    default ResourceLocation getJobId() {
        return new ResourceLocation("automata", getType().getId());
    }

    default void tick(IAutomatonContext ctx) {
        if (ctx instanceof AutomatonAIContext aiCtx) {
            tick(aiCtx);
        }
    }

    default void tick(AutomatonAIContext ctx) {
    }

    default void onStop(IAutomatonContext ctx) {
        if (ctx instanceof AutomatonAIContext aiCtx) {
            onStop(aiCtx);
        }
    }

    default void onStop(AutomatonAIContext ctx) {
    }
}
