package com.x4yi.automata.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.x4yi.automata.ai.UtilityBrainArbiter.BrainBehavior;
import com.x4yi.automata.ai.UtilityBrainArbiter.UtilityState;

class UtilityBrainArbiterTest {

    @Test
    void prioritizesHazardAndLowHealthOverCombatJobAndLoot() {
        UtilityState hazard = new UtilityState(true, false, false, true, true, true, 0.95F, true);
        assertEquals(BrainBehavior.SURVIVAL, UtilityBrainArbiter.selectBestBehavior(hazard, BrainBehavior.ACTIVE_JOB));

        UtilityState lowHp = new UtilityState(false, true, false, true, true, false, 0.2F, true);
        assertEquals(BrainBehavior.SURVIVAL, UtilityBrainArbiter.selectBestBehavior(lowHp, BrainBehavior.SELF_DEFENSE));
    }

    @Test
    void commitsToActiveVeinOverLootAndAppliesHysteresis() {
        UtilityState committedVeinWithLoot = new UtilityState(false, false, false, false, true, false, 0.3F, true);
        assertEquals(BrainBehavior.ACTIVE_JOB,
                UtilityBrainArbiter.selectBestBehavior(committedVeinWithLoot, BrainBehavior.ACTIVE_JOB));

        UtilityState idleWithLoot = new UtilityState(false, false, false, false, true, false, 0.3F, false);
        assertEquals(BrainBehavior.SEEK_LOOT,
                UtilityBrainArbiter.selectBestBehavior(idleWithLoot, BrainBehavior.ACTIVE_JOB));

        UtilityState borderlineLogistics = new UtilityState(false, false, false, false, false, true, 0.65F, true);
        assertEquals(BrainBehavior.LOGISTICS,
                UtilityBrainArbiter.selectBestBehavior(borderlineLogistics, BrainBehavior.LOGISTICS));
    }
}

