package com.x4yi.automata.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;

class WorldCacheManagerTest {

    @Test
    void classifiesThinSnowAndCarpetLayersAsPassableAir() {
        assertEquals(BlockCellType.AIR, WorldCacheManager.classifyCollisionBox(null));
        assertEquals(BlockCellType.AIR, WorldCacheManager.classifyCollisionBox(new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.0625D, 1.0D)));
        assertEquals(BlockCellType.AIR, WorldCacheManager.classifyCollisionBox(new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.125D, 1.0D)));
        assertEquals(BlockCellType.AIR, WorldCacheManager.classifyCollisionBox(new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.25D, 1.0D)));
    }

    @Test
    void classifiesThickLayersAndTallObstaclesAppropriately() {
        assertEquals(BlockCellType.SOLID, WorldCacheManager.classifyCollisionBox(new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.375D, 1.0D)));
        assertEquals(BlockCellType.SOLID, WorldCacheManager.classifyCollisionBox(new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D)));
        assertEquals(BlockCellType.TALL_OBSTACLE, WorldCacheManager.classifyCollisionBox(new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.5D, 1.0D)));
    }

    @Test
    void refreshesSectionByWorldTicksAndHandlesReset() {
        assertTrue(WorldCacheManager.shouldRefreshSection(false, 100L, 105L));
        assertTrue(WorldCacheManager.shouldRefreshSection(true, -1L, 105L));
        assertFalse(WorldCacheManager.shouldRefreshSection(true, 100L, 110L));
        assertTrue(WorldCacheManager.shouldRefreshSection(true, 100L, 100L + WorldCacheManager.SECTION_REFRESH_INTERVAL_TICKS));
        assertTrue(WorldCacheManager.shouldRefreshSection(true, 200L, 50L));
    }

    @Test
    void dimensionSectionCacheEvictsLruAndIsolatesSnapshots() {
        WorldCacheManager.DimensionSectionCache cache = new WorldCacheManager.DimensionSectionCache(2);
        byte[] a = new byte[] {1};
        byte[] b = new byte[] {2};
        byte[] c = new byte[] {3};
        cache.put(10L, a, 1L);
        cache.put(20L, b, 2L);
        assertNotNull(cache.getCells(10L));
        cache.put(30L, c, 3L);

        assertEquals(2, cache.size());
        assertNotNull(cache.getCells(10L));
        assertNull(cache.getCells(20L));

        Map<Long, byte[]> snapshot = cache.snapshotCopy();
        cache.remove(10L);
        assertEquals(2, snapshot.size());
        assertNotNull(snapshot.get(10L));
    }
}

