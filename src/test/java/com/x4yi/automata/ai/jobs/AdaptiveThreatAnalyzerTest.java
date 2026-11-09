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
}

