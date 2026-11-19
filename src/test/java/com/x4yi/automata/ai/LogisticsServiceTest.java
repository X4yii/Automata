
package com.x4yi.automata.ai;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticsServiceTest {

    @Test
    void occupancyRatioHandlesEmptyAndFullBounds() {
        assertEquals(0.0D, LogisticsService.computeOccupancyRatio(0, 0), 1e-6);
        assertEquals(0.0D, LogisticsService.computeOccupancyRatio(0, 27), 1e-6);
        assertEquals(23.0D / 27.0D, LogisticsService.computeOccupancyRatio(23, 27), 1e-6);
        assertEquals(1.0D, LogisticsService.computeOccupancyRatio(27, 27), 1e-6);
    }

    @Test
    void requiresProximityWithinOneBlockOfChestEdge() {
        BlockPos chest = new BlockPos(10, 64, 10);
        // Standing right next to chest (within 1 block of edge)
        assertTrue(LogisticsService.isWithinOneBlockEdge(9.5D, 64.0D, 10.5D, chest));
        assertTrue(LogisticsService.isWithinOneBlockEdge(11.5D, 64.0D, 10.5D, chest));
        // Too far (2+ blocks away)
        assertFalse(LogisticsService.isWithinOneBlockEdge(7.5D, 64.0D, 10.5D, chest));
        assertFalse(LogisticsService.isWithinOneBlockEdge(9.5D, 67.0D, 10.5D, chest));
    }
}

