package com.x4yi.automata.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.x4yi.automata.navigation.PathfindingSettings;

class AutomatonAIContextTest {

    @Test
    void scalesPathChunkRadiusAndNodeBudgetForLongDistances() {
        assertEquals(2, AutomatonAIContext.computePathChunkRadius(10.0D));
        assertEquals(6, AutomatonAIContext.computePathChunkRadius(80.0D));
        assertEquals(8, AutomatonAIContext.computePathChunkRadius(200.0D));

        assertEquals(PathfindingSettings.DEFAULT_MAX_NODES, AutomatonAIContext.computePathBudget(20.0D, 1024));
        assertTrue(AutomatonAIContext.computePathBudget(80.0D, 8192) >= 25600);
        assertEquals(32768, AutomatonAIContext.computePathBudget(150.0D, 8192));
    }

    @Test
    void reusesEntitySnapshotWithinSameTickAndSufficientRadius() {
        assertTrue(AutomatonAIContext.isSnapshotReusable(100L, 100L, 16.0D, 14.0D));
        assertFalse(AutomatonAIContext.isSnapshotReusable(100L, 101L, 16.0D, 14.0D));
        assertFalse(AutomatonAIContext.isSnapshotReusable(100L, 100L, 16.0D, 24.0D));
    }
}

