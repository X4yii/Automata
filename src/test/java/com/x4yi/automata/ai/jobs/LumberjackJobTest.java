package com.x4yi.automata.ai.jobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LumberjackJobTest {

    @Test
    void spiralQuadrantCyclesFourStepsCleanly() {
        assertEquals(0, LumberjackJob.spiralQuadrantForOffset(0));
        assertEquals(1, LumberjackJob.spiralQuadrantForOffset(1));
        assertEquals(2, LumberjackJob.spiralQuadrantForOffset(2));
        assertEquals(3, LumberjackJob.spiralQuadrantForOffset(3));
        assertEquals(0, LumberjackJob.spiralQuadrantForOffset(4));
    }
}

