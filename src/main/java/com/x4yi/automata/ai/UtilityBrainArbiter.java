package com.x4yi.automata.ai;

import javax.annotation.Nullable;

public final class UtilityBrainArbiter {

    public enum BrainBehavior {
        SURVIVAL,
        SELF_DEFENSE,
        LOGISTICS,
        SEEK_LOOT,
        ACTIVE_JOB
    }

    public record UtilityState(
            boolean inHazardOrExplosive,
            boolean lowHealthNeedsSurvival,
            boolean allyNeedsRevive,
            boolean hasThreatOrRevenge,
            boolean safeLootAvailable,
            boolean logisticsReady,
            float inventoryOccupancy,
            boolean hasActiveCommittedJob) {
    }

    static final float COMMITMENT_HYSTERESIS_BONUS = 0.08F;

    private UtilityBrainArbiter() {}

    public static BrainBehavior selectBestBehavior(
            UtilityState state,
            @Nullable BrainBehavior currentBehavior) {
        BrainBehavior best = BrainBehavior.ACTIVE_JOB;
        float bestScore = -1.0F;
        for (BrainBehavior candidate : BrainBehavior.values()) {
            float score = scoreBehavior(candidate, state);
            if (score <= 0.0F) {
                continue;
            }
            if (candidate == currentBehavior) {
                score += COMMITMENT_HYSTERESIS_BONUS;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public static float scoreBehavior(BrainBehavior behavior, UtilityState state) {
        return switch (behavior) {
            case SURVIVAL -> scoreSurvival(state);
            case SELF_DEFENSE -> state.hasThreatOrRevenge() ? 0.78F : 0.0F;
            case LOGISTICS -> state.logisticsReady()
                    ? 0.45F + 0.35F * Math.max(0.0F, Math.min(1.0F, state.inventoryOccupancy()))
                    : 0.0F;
            case SEEK_LOOT -> scoreSeekLoot(state);
            case ACTIVE_JOB -> state.hasActiveCommittedJob() ? 0.68F : 0.55F;
        };
    }

    private static float scoreSurvival(UtilityState state) {
        if (state.inHazardOrExplosive()) {
            return 1.00F;
        }
        if (state.lowHealthNeedsSurvival()) {
            return 0.90F;
        }
        if (state.allyNeedsRevive()) {
            return 0.84F;
        }
        return 0.0F;
    }

    private static float scoreSeekLoot(UtilityState state) {
        if (!state.safeLootAvailable()) {
            return 0.0F;
        }
        return state.hasActiveCommittedJob() ? 0.46F : 0.65F;
    }
}

