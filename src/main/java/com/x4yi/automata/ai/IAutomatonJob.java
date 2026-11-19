package com.x4yi.automata.ai;

import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.api.IAutomatonContext;

public interface IAutomatonJob {

    ResourceLocation getJobId();

    void tick(IAutomatonContext ctx);

    default void onStop(IAutomatonContext ctx) {
    }

    default boolean hasCommittedWork(IAutomatonContext ctx) {
        return false;
    }

    /**
     * Copies up to {@code maxCount} planned packed block coordinates into {@code dest}
     * without heap allocations, for debug visualization.
     */
    default int copyPlannedBreakTargets(long[] dest, int maxCount) {
        return 0;
    }
}
