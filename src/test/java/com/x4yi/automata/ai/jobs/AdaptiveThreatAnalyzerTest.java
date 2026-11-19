package com.x4yi.automata.ai.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdaptiveThreatAnalyzerTest {

    @Test
    void scalesPriorityMultiplierByThreatProfile() {
        double baseline = AdaptiveThreatAnalyzer.computeThreatPriorityMultiplier(
                false, false, false, false, false);
        assertEquals(1.0D, baseline);

        double primedCreeper = AdaptiveThreatAnalyzer.computeThreatPriorityMultiplier(
                true, false, false, false, false);
        double aerial = AdaptiveThreatAnalyzer.computeThreatPriorityMultiplier(
                false, true, false, false, false);
        double rangedBossSrp = AdaptiveThreatAnalyzer.computeThreatPriorityMultiplier(
                false, true, true, true, true);

        assertTrue(primedCreeper < baseline);
        assertTrue(aerial < baseline);
        assertTrue(rangedBossSrp < aerial);
    }

    @Test
    void buildsThreatProfileWithSlotsAndPriorityFromTraits() {
        AdaptiveThreatAnalyzer.ThreatProfile simple = AdaptiveThreatAnalyzer.buildProfileFromTraits(
                java.util.EnumSet.noneOf(AdaptiveThreatAnalyzer.ThreatTrait.class), 20.0F, 20.0F, 3.0D);
        assertEquals(1, simple.recommendedMeleeSlots());
        assertEquals(1.0D, simple.priorityMultiplier(), 1.0E-5D);

        AdaptiveThreatAnalyzer.ThreatProfile summoner = AdaptiveThreatAnalyzer.buildProfileFromTraits(
                java.util.EnumSet.of(
                        AdaptiveThreatAnalyzer.ThreatTrait.SUMMONER_SPAWNER,
                        AdaptiveThreatAnalyzer.ThreatTrait.RANGED),
                60.0F, 60.0F, 6.0D);
        assertEquals(3, summoner.recommendedMeleeSlots());
        assertTrue(summoner.priorityMultiplier() < 0.35D);

        AdaptiveThreatAnalyzer.ThreatProfile boss = AdaptiveThreatAnalyzer.buildProfileFromTraits(
                java.util.EnumSet.of(AdaptiveThreatAnalyzer.ThreatTrait.BOSS_TIER),
                250.0F, 250.0F, 18.0D);
        assertEquals(Integer.MAX_VALUE, boss.recommendedMeleeSlots());
        assertTrue(boss.riskScore() > summoner.riskScore());
    }
}

