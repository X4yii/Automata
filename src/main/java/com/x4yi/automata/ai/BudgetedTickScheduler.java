
package com.x4yi.automata.ai;

public final class BudgetedTickScheduler {

    private BudgetedTickScheduler() {}

    public static boolean shouldRun(long worldTime, int entityId, int intervalTicks) {
        if (intervalTicks <= 1) {
            return true;
        }
        long phase = Math.floorMod(worldTime + entityId, intervalTicks);
        return phase == 0L;
    }
}

