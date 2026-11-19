package com.x4yi.automata.ai.jobs;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.ai.AutomatonAIContext;

public final class AreaBlacklistTracker {

    private static final long DEFAULT_BLACKLIST_TICKS = 100L;

    private final Map<Long, Long> unreachableUntil = new HashMap<>();
    private final long blacklistDurationTicks;

    public AreaBlacklistTracker() {
        this(DEFAULT_BLACKLIST_TICKS);
    }

    public AreaBlacklistTracker(long blacklistDurationTicks) {
        this.blacklistDurationTicks = Math.max(1L, blacklistDurationTicks);
    }

    public boolean isBlacklisted(BlockPos pos, long now) {
        long key = pos.toLong();
        Long until = unreachableUntil.get(key);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            unreachableUntil.remove(key);
            return false;
        }
        return true;
    }

    public void blacklist(BlockPos pos, long now) {
        if (pos == null) {
            return;
        }
        unreachableUntil.put(pos.toLong(), now + blacklistDurationTicks);
        if (unreachableUntil.size() > 64) {
            unreachableUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
    }

    public boolean blacklistStuckGoalIfPresent(AutomatonAIContext ctx, long now) {
        long stuckGoal = ctx.getMovement().getLastStuckGoal();
        if (stuckGoal == Long.MIN_VALUE) {
            return false;
        }
        unreachableUntil.put(stuckGoal, now + blacklistDurationTicks);
        if (unreachableUntil.size() > 64) {
            unreachableUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        return true;
    }

    public void clear() {
        unreachableUntil.clear();
    }
}

