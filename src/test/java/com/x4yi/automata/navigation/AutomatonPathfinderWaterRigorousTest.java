package com.x4yi.automata.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Verifies 1x1 underwater tunnel traversal and water-to-dry-shore exit pathfinding.
 */
class AutomatonPathfinderWaterRigorousTest {

    @Test
    void navigatesUnderwaterTunnelAndExitsOntoDryShore() {
        Map<Long, Byte> worldMap = new HashMap<>();
        // Water pool x=0..6, y=61..63, solid roof over x=3..4 at y=62 (1x1 tunnel at y=61), dry shore x=7..12 at y=62
        for (int x = 0; x <= 12; x++) {
            for (int z = 0; z <= 4; z++) {
                worldMap.put(PackedPos.pack(x, 59, z), BlockCellType.SOLID);
                if (x <= 6) {
                    worldMap.put(PackedPos.pack(x, 60, z), BlockCellType.SOLID);
                    worldMap.put(PackedPos.pack(x, 61, z), BlockCellType.WATER);
                    worldMap.put(PackedPos.pack(x, 62, z), (x >= 3 && x <= 4) ? BlockCellType.SOLID : BlockCellType.WATER);
                    worldMap.put(PackedPos.pack(x, 63, z), (x >= 3 && x <= 4) ? BlockCellType.SOLID : BlockCellType.AIR);
                } else {
                    worldMap.put(PackedPos.pack(x, 60, z), BlockCellType.SOLID);
                    worldMap.put(PackedPos.pack(x, 61, z), BlockCellType.SOLID);
                    worldMap.put(PackedPos.pack(x, 62, z), BlockCellType.SOLID);
                    worldMap.put(PackedPos.pack(x, 63, z), BlockCellType.AIR);
                    worldMap.put(PackedPos.pack(x, 64, z), BlockCellType.AIR);
                }
            }
        }

        INavigationWorldView view = (x, y, z) -> {
            if (y < 0 || y > 255) {
                return BlockCellType.UNLOADED;
            }
            return worldMap.getOrDefault(PackedPos.pack(x, y, z), BlockCellType.AIR);
        };

        PathResult result = AutomatonPathfinder.findPath(
                view,
                1, 61, 2,
                10, 63, 2,
                PathfindingSettings.defaults(false, false),
                null);

        assertTrue(result.isComplete(), "Pathfinder should exit water through 1x1 tunnel onto dry shore");
        assertTrue(result.length() > 0);
        long lastStep = result.getStep(result.length() - 1);
        assertEquals(10, PackedPos.unpackX(lastStep));
        assertEquals(63, PackedPos.unpackY(lastStep));
        assertEquals(2, PackedPos.unpackZ(lastStep));
    }
}
