package com.x4yi.automata.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AllySeparationTest {

    @Test
    void pushesAlliesCloserThanHalfBlockGapAndIgnoresFartherAllies() {
        double[] out = new double[2];

        AutomatonAIContext.accumulateSeparationStep(0.0D, 0.0D, 0.3D, 1, 1.15D, 0.0D, 0.3D, 2, 0.5D, out);
        assertEquals(0.0D, out[0], 1.0E-6D);
        assertEquals(0.0D, out[1], 1.0E-6D);

        AutomatonAIContext.accumulateSeparationStep(0.0D, 0.0D, 0.3D, 1, 0.7D, 0.0D, 0.3D, 2, 0.5D, out);
        assertTrue(out[0] < -0.1D);
        assertEquals(0.0D, out[1], 1.0E-6D);

        out[0] = 0.0D;
        out[1] = 0.0D;
        AutomatonAIContext.accumulateSeparationStep(0.0D, 0.0D, 0.3D, 1, 0.0D, 0.0D, 0.3D, 2, 0.5D, out);
        assertTrue(Math.hypot(out[0], out[1]) > 0.5D);
    }
}

