package com.x4yi.automata.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

