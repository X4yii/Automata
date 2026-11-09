
package com.x4yi.automata.ai;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;

import com.x4yi.automata.config.AutomataConfig;

public final class BudgetedTickScheduler {

    private static volatile int cachedTickCounter = Integer.MIN_VALUE;
    private static volatile double cachedMspt = 0.0D;

    private BudgetedTickScheduler() {}

    public static boolean shouldRun(long worldTime, int entityId, int intervalTicks) {
        if (intervalTicks <= 1) {
            return true;
        }
        int mixed = mixEntityId(entityId);
        long phase = Math.floorMod(worldTime + Integer.toUnsignedLong(mixed), intervalTicks);
        return phase == 0L;
    }

    public static boolean shouldRun(
            long worldTime,
            int entityId,
            int baseIntervalTicks,
            @Nullable MinecraftServer server) {
        int scaled = scaleInterval(baseIntervalTicks, getServerMspt(server));
        return shouldRun(worldTime, entityId, scaled);
    }

    public static int scaleInterval(int baseIntervalTicks, double mspt) {
        if (baseIntervalTicks <= 1 || !AutomataConfig.adaptiveTpsThrottling) {
            return baseIntervalTicks;
        }
        if (mspt >= 75.0D) {
            return baseIntervalTicks * 4;
        }
        if (mspt >= 50.0D) {
            return baseIntervalTicks * 3;
        }
        if (mspt >= 35.0D) {
            return baseIntervalTicks * 2;
        }
        return baseIntervalTicks;
    }

    public static double getServerMspt(@Nullable MinecraftServer server) {
        if (server == null) {
            return 0.0D;
        }
        int tickCounter = server.getTickCounter();
        if (tickCounter == cachedTickCounter) {
            return cachedMspt;
        }
        long[] tickTimes = server.tickTimeArray;
        double mspt = 0.0D;
        if (tickTimes != null && tickTimes.length > 0) {
            long sumNanos = 0L;
            for (long nanos : tickTimes) {
                sumNanos += nanos;
            }
            mspt = (sumNanos / (double) tickTimes.length) * 1.0E-6D;
        }
        cachedMspt = mspt;
        cachedTickCounter = tickCounter;
        return mspt;
    }

    static int mixEntityId(int entityId) {
        int x = entityId * 0x9E3779B9;
        x ^= (x >>> 16);
        return x;
    }
}

