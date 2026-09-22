package com.x4yi.automata.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningProgressControllerTest {

    @Test
    void clampsAdversarialProgressAndStages() {
        assertEquals(-1, MiningProgressController.computeBreakStage(0.0F));
        assertEquals(-1, MiningProgressController.computeBreakStage(-5.0F));
        assertEquals(-1, MiningProgressController.computeBreakStage(Float.NaN));
        assertEquals(0, MiningProgressController.computeBreakStage(0.001F));
        assertEquals(5, MiningProgressController.computeBreakStage(0.55F));
        assertEquals(9, MiningProgressController.computeBreakStage(0.99F));
        assertEquals(9, MiningProgressController.computeBreakStage(42.0F));

        float p = MiningProgressController.advanceProgress(0.4F, Float.NaN);
        assertEquals(0.4F, p, 0.0001F);

        p = MiningProgressController.advanceProgress(0.4F, -10.0F);
        assertEquals(0.4F, p, 0.0001F);

        p = MiningProgressController.advanceProgress(0.8F, 5.0F);
        assertEquals(1.0F, p, 0.0001F);
    }

    @Test
    void defersMiningWhileAirborneOutOfWaterAndLadder() {
        assertTrue(MiningProgressController.shouldDeferMiningInAir(false, false, false));
        assertFalse(MiningProgressController.shouldDeferMiningInAir(true, false, false));
        assertFalse(MiningProgressController.shouldDeferMiningInAir(false, true, false));
        assertFalse(MiningProgressController.shouldDeferMiningInAir(false, false, true));
    }
}

