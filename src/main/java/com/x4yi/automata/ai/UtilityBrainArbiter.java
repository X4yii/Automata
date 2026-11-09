package com.x4yi.automata.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import com.x4yi.automata.config.AutomataConfig;

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

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean inHazardOrExplosive;
            private boolean lowHealthNeedsSurvival;
            private boolean allyNeedsRevive;
            private boolean hasThreatOrRevenge;
            private boolean safeLootAvailable;
            private boolean logisticsReady;
            private float inventoryOccupancy;
            private boolean hasActiveCommittedJob;

            public Builder inHazardOrExplosive(boolean value) {
                this.inHazardOrExplosive = value;
                return this;
            }

            public Builder lowHealthNeedsSurvival(boolean value) {
                this.lowHealthNeedsSurvival = value;
                return this;
            }

            public Builder allyNeedsRevive(boolean value) {
                this.allyNeedsRevive = value;
                return this;
            }

            public Builder hasThreatOrRevenge(boolean value) {
                this.hasThreatOrRevenge = value;
                return this;
            }

            public Builder safeLootAvailable(boolean value) {
                this.safeLootAvailable = value;
                return this;
            }

            public Builder logisticsReady(boolean value) {
                this.logisticsReady = value;
                return this;
            }

            public Builder inventoryOccupancy(float value) {
                this.inventoryOccupancy = value;
                return this;
            }

            public Builder hasActiveCommittedJob(boolean value) {
                this.hasActiveCommittedJob = value;
                return this;
            }

            public UtilityState build() {
                return new UtilityState(
                        inHazardOrExplosive,
                        lowHealthNeedsSurvival,
                        allyNeedsRevive,
                        hasThreatOrRevenge,
                        safeLootAvailable,
                        logisticsReady,
                        inventoryOccupancy,
                        hasActiveCommittedJob);
            }
        }
    }

    private static final Map<BrainBehavior, ToDoubleFunction<UtilityState>> SCORERS = new EnumMap<>(BrainBehavior.class);

    static {
        SCORERS.put(BrainBehavior.SURVIVAL, UtilityBrainArbiter::scoreSurvival);
        SCORERS.put(BrainBehavior.SELF_DEFENSE, state -> state.hasThreatOrRevenge() ? AutomataConfig.arbiterSelfDefenseWeight : 0.0D);
        SCORERS.put(BrainBehavior.LOGISTICS, state -> state.logisticsReady()
                ? AutomataConfig.arbiterLogisticsBaseWeight
                        + AutomataConfig.arbiterLogisticsOccupancyScale * Math.max(0.0F, Math.min(1.0F, state.inventoryOccupancy()))
                : 0.0D);
        SCORERS.put(BrainBehavior.SEEK_LOOT, UtilityBrainArbiter::scoreSeekLoot);
        SCORERS.put(BrainBehavior.ACTIVE_JOB, state -> state.hasActiveCommittedJob()
                ? AutomataConfig.arbiterActiveJobCommittedWeight
                : AutomataConfig.arbiterActiveJobBaseWeight);
    }

    private UtilityBrainArbiter() {}

    public static BrainBehavior selectBestBehavior(
            UtilityState state,
            @Nullable BrainBehavior currentBehavior) {
        BrainBehavior best = BrainBehavior.ACTIVE_JOB;
        float bestScore = -1.0F;
        float hysteresis = (float) AutomataConfig.arbiterHysteresisBonus;
        for (BrainBehavior candidate : BrainBehavior.values()) {
            float score = scoreBehavior(candidate, state);
            if (score <= 0.0F) {
                continue;
            }
            if (candidate == currentBehavior) {
                score += hysteresis;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public static float scoreBehavior(BrainBehavior behavior, UtilityState state) {
        ToDoubleFunction<UtilityState> scorer = SCORERS.get(behavior);
        return scorer == null ? 0.0F : (float) scorer.applyAsDouble(state);
    }

    private static double scoreSurvival(UtilityState state) {
        if (state.inHazardOrExplosive()) {
            return AutomataConfig.arbiterSurvivalHazardWeight;
        }
        if (state.lowHealthNeedsSurvival()) {
            return AutomataConfig.arbiterSurvivalLowHealthWeight;
        }
        if (state.allyNeedsRevive()) {
            return AutomataConfig.arbiterSurvivalReviveWeight;
        }
        return 0.0D;
    }

    private static double scoreSeekLoot(UtilityState state) {
        if (!state.safeLootAvailable()) {
            return 0.0D;
        }
        return state.hasActiveCommittedJob()
                ? AutomataConfig.arbiterSeekLootCommittedWeight
                : AutomataConfig.arbiterSeekLootIdleWeight;
    }
}

