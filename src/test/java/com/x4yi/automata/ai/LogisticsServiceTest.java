
package com.x4yi.automata.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogisticsServiceTest {

    @Test
    void occupancyRatioHandlesEmptyAndFullBounds() {
        assertEquals(0.0D, LogisticsService.computeOccupancyRatio(0, 0), 1e-6);
        assertEquals(0.0D, LogisticsService.computeOccupancyRatio(0, 27), 1e-6);
        assertEquals(23.0D / 27.0D, LogisticsService.computeOccupancyRatio(23, 27), 1e-6);
        assertEquals(1.0D, LogisticsService.computeOccupancyRatio(27, 27), 1e-6);
    }
}

