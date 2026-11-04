package com.x4yi.automata.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

class RegionSelectionRendererTest {

    @Test
    void storesIndependentZonesPerAutomaton() {
        RegionSelectionRenderer.clearAllZones();
        RegionSelectionRenderer.setLastConfiguredZone(10, new BlockPos(0, 64, 0), new BlockPos(10, 70, 10));
        RegionSelectionRenderer.setLastConfiguredZone(20, new BlockPos(50, 64, 50), new BlockPos(60, 70, 60));

        assertNotNull(RegionSelectionRenderer.getConfiguredZone(10));
        assertNotNull(RegionSelectionRenderer.getConfiguredZone(20));
        assertEquals(new BlockPos(0, 64, 0), RegionSelectionRenderer.getConfiguredZone(10).min);
        assertEquals(new BlockPos(50, 64, 50), RegionSelectionRenderer.getConfiguredZone(20).min);

        RegionSelectionRenderer.setLastConfiguredZone(10, null, null);
        assertNull(RegionSelectionRenderer.getConfiguredZone(10));
        assertNotNull(RegionSelectionRenderer.getConfiguredZone(20));
    }
}

