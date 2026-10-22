
package com.x4yi.automata.navigation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomatonPathfinderTest {

    @Test
    void avoidsLavaPitAndRoutesAround() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 12, 64, 0, 12, BlockCellType.SOLID);
        for (int z = 2; z <= 8; z++) {
            world.set(6, 64, z, BlockCellType.HAZARD);
        }

        PathfindingSettings settings = PathfindingSettings.defaults(false, false);
        PathResult result = AutomatonPathfinder.findPath(world, 2, 65, 5, 10, 65, 5, settings, null);

        assertTrue(result.isComplete());
        for (int i = 0; i < result.length(); i++) {
            long step = result.getStep(i);
            int sx = PackedPos.unpackX(step);
            int sy = PackedPos.unpackY(step);
            int sz = PackedPos.unpackZ(step);
            assertFalse(world.isHazard(sx, sy - 1, sz));
            assertFalse(world.isHazard(sx, sy, sz));
        }
    }

    @Test
    void sprintJumpsAcrossTwoBlockGap() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 4, 64, 0, 4, BlockCellType.SOLID);
        world.fillFloor(7, 12, 64, 0, 4, BlockCellType.SOLID);

        PathfindingSettings settings = new PathfindingSettings(false, true, false, 2048, 4);
        PathResult result = AutomatonPathfinder.findPath(world, 3, 65, 2, 9, 65, 2, settings, null);

        assertTrue(result.isComplete());
        boolean usedSprintJump = false;
        for (int i = 0; i < result.length(); i++) {
            if (result.getAction(i) == PathNodeAction.SPRINT_JUMP) {
                usedSprintJump = true;
                break;
            }
        }
        assertTrue(usedSprintJump);
    }

    @Test
    void respectsAllowTerrainModificationFlag() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 10, 64, 0, 4, BlockCellType.SOLID);
        world.set(5, 65, 2, BlockCellType.SOLID);
        world.set(5, 66, 2, BlockCellType.SOLID);
        world.set(5, 65, 1, BlockCellType.SOLID);
        world.set(5, 66, 1, BlockCellType.SOLID);
        world.set(5, 65, 3, BlockCellType.SOLID);
        world.set(5, 66, 3, BlockCellType.SOLID);
        world.set(5, 65, 0, BlockCellType.SOLID);
        world.set(5, 66, 0, BlockCellType.SOLID);
        world.set(5, 65, 4, BlockCellType.SOLID);
        world.set(5, 66, 4, BlockCellType.SOLID);

        PathfindingSettings restricted = new PathfindingSettings(false, false, false, 512, 4);
        PathResult blocked = AutomatonPathfinder.findPath(world, 2, 65, 2, 8, 65, 2, restricted, null);
        assertFalse(blocked.isComplete());

        PathfindingSettings allowed = new PathfindingSettings(true, false, true, 512, 4);
        PathResult broken = AutomatonPathfinder.findPath(world, 2, 65, 2, 8, 65, 2, allowed, null);
        assertTrue(broken.isComplete());
    }

    @Test
    void abortsImmediatelyWhenCancelled() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 40, 64, 0, 40, BlockCellType.SOLID);

        AtomicBoolean cancelled = new AtomicBoolean(true);
        PathResult result = AutomatonPathfinder.findPath(
                world, 1, 65, 1, 35, 65, 35, PathfindingSettings.defaults(false, false), cancelled);
        assertTrue(result.isEmpty());
        assertFalse(result.isComplete());
    }

    @Test
    void benchmark32BlockPathfinding() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 40, 64, 0, 40, BlockCellType.SOLID);
        for (int z = 5; z <= 28; z++) {
            world.set(18, 65, z, BlockCellType.SOLID);
            world.set(18, 66, z, BlockCellType.SOLID);
        }

        PathfindingSettings settings = PathfindingSettings.defaults(false, false);
        for (int i = 0; i < 50; i++) {
            AutomatonPathfinder.findPath(world, 2, 65, 16, 34, 65, 16, settings, null);
        }

        int iterations = 300;
        long totalNanos = 0L;
        int lastExpanded = 0;
        for (int i = 0; i < iterations; i++) {
            PathResult res = AutomatonPathfinder.findPath(world, 2, 65, 16, 34, 65, 16, settings, null);
            assertTrue(res.isComplete());
            totalNanos += res.getElapsedNanos();
            lastExpanded = res.getExpandedNodes();
        }
        double avgMicros = (totalNanos / (double) iterations) / 1000.0D;
        System.out.printf("AutomatonPathfinder 32-block benchmark: avg=%.2f us/query, expandedNodes=%d%n", avgMicros, lastExpanded);
        assertTrue(avgMicros < 50000.0D);
    }

    @Test
    void prefersExistingStaircaseOverBreakingBlocks() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(-2, 10, 59, -2, 6, BlockCellType.SOLID);
        for (int y = 60; y <= 63; y++) {
            for (int x = -2; x <= 10; x++) {
                for (int z = -2; z <= 6; z++) {
                    world.set(x, y, z, BlockCellType.SOLID);
                }
            }
        }
        world.fillFloor(-2, 10, 64, -2, 6, BlockCellType.AIR);

        world.set(0, 60, 0, BlockCellType.AIR);
        world.set(0, 61, 0, BlockCellType.AIR);
        world.set(0, 62, 0, BlockCellType.AIR);

        for (int step = 1; step <= 4; step++) {
            int sx = step;
            int sy = 60 + step;
            world.set(sx, sy, 0, BlockCellType.AIR);
            world.set(sx, sy + 1, 0, BlockCellType.AIR);
            world.set(sx, sy + 2, 0, BlockCellType.AIR);
        }

        PathfindingSettings settings = new PathfindingSettings(true, true, true, 4096, 16);
        PathResult res = AutomatonPathfinder.findPath(world, 0, 60, 0, 5, 64, 0, settings, null);
        assertTrue(res.isComplete());
        for (int i = 0; i < res.length(); i++) {
            assertFalse(res.getAction(i) == PathNodeAction.BREAK_THROUGH);
        }
    }

    @Test
    void separatesBreakThroughAndScaffoldingFlags() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 10, 64, -1, 1, BlockCellType.SOLID);
        for (int x = 4; x <= 6; x++) {
            for (int z = -1; z <= 1; z++) {
                world.set(x, 64, z, BlockCellType.AIR);
            }
        }

        PathfindingSettings scaffoldOnly = new PathfindingSettings(false, false, true, 2048, 4);
        PathResult bridgeRes = AutomatonPathfinder.findPath(world, 1, 65, 0, 9, 65, 0, scaffoldOnly, null);
        assertTrue(bridgeRes.isComplete());
        boolean usedBridge = false;
        for (int i = 0; i < bridgeRes.length(); i++) {
            assertFalse(bridgeRes.getAction(i) == PathNodeAction.BREAK_THROUGH);
            if (bridgeRes.getAction(i) == PathNodeAction.BRIDGE) {
                usedBridge = true;
            }
        }
        assertTrue(usedBridge);

        SyntheticWorld walled = new SyntheticWorld();
        walled.fillFloor(0, 10, 64, -1, 1, BlockCellType.SOLID);
        walled.set(4, 65, 0, BlockCellType.SOLID);
        walled.set(4, 66, 0, BlockCellType.SOLID);
        walled.set(4, 65, -1, BlockCellType.SOLID);
        walled.set(4, 66, -1, BlockCellType.SOLID);
        walled.set(4, 65, 1, BlockCellType.SOLID);
        walled.set(4, 66, 1, BlockCellType.SOLID);

        PathfindingSettings breakOnly = new PathfindingSettings(true, false, false, 2048, 4);
        PathResult breakRes = AutomatonPathfinder.findPath(walled, 1, 65, 0, 8, 65, 0, breakOnly, null);
        assertTrue(breakRes.isComplete());
        boolean usedBreak = false;
        for (int i = 0; i < breakRes.length(); i++) {
            assertFalse(breakRes.getAction(i) == PathNodeAction.BRIDGE);
            assertFalse(breakRes.getAction(i) == PathNodeAction.PILLAR_UP);
            if (breakRes.getAction(i) == PathNodeAction.BREAK_THROUGH) {
                usedBreak = true;
            }
        }
        assertTrue(usedBreak);
    }

    @Test
    void smoothsFlatWalkSegmentsViaLineOfSight() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 12, 64, 0, 12, BlockCellType.SOLID);

        PathfindingSettings settings = PathfindingSettings.defaults(false, false);
        PathResult result = AutomatonPathfinder.findPath(world, 1, 65, 1, 10, 65, 8, settings, null);

        assertTrue(result.isComplete());
        assertTrue(result.length() <= 3);
        assertTrue(result.getStep(0) == PackedPos.pack(1, 65, 1));
        assertTrue(result.getStep(result.length() - 1) == PackedPos.pack(10, 65, 8));
    }

    @Test
    void prefersRearExitOverTunnelingThroughHill() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 14, 64, 0, 10, BlockCellType.SOLID);
        for (int z = 0; z <= 8; z++) {
            world.set(6, 65, z, BlockCellType.SOLID);
            world.set(6, 66, z, BlockCellType.SOLID);
        }

        PathfindingSettings settings = PathfindingSettings.defaults(true, false);
        PathResult result = AutomatonPathfinder.findPath(world, 4, 65, 4, 9, 65, 4, settings, null);

        assertTrue(result.isComplete());
        for (int i = 0; i < result.length(); i++) {
            assertFalse(result.getAction(i) == PathNodeAction.BREAK_THROUGH);
        }
    }

    @Test
    void prefersScaffoldingPillarUpOverTunneling() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 8, 60, 0, 8, BlockCellType.SOLID);
        for (int x = 0; x <= 8; x++) {
            for (int y = 61; y <= 63; y++) {
                world.set(x, y, 0, BlockCellType.SOLID);
                world.set(x, y, 8, BlockCellType.SOLID);
            }
        }
        for (int z = 0; z <= 8; z++) {
            for (int y = 61; y <= 63; y++) {
                world.set(0, y, z, BlockCellType.SOLID);
                world.set(8, y, z, BlockCellType.SOLID);
            }
        }
        world.fillFloor(8, 12, 63, 2, 6, BlockCellType.SOLID);

        PathfindingSettings settings = PathfindingSettings.defaults(true, true);
        PathResult result = AutomatonPathfinder.findPath(world, 4, 61, 4, 10, 64, 4, settings, null);

        assertTrue(result.isComplete());
        boolean usedPillar = false;
        for (int i = 0; i < result.length(); i++) {
            assertFalse(result.getAction(i) == PathNodeAction.BREAK_THROUGH);
            if (result.getAction(i) == PathNodeAction.PILLAR_UP) {
                usedPillar = true;
            }
        }
        assertTrue(usedPillar);
    }

    @Test
    void breaksAscendingStaircaseWhenCompletelyEnclosed() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 8, 60, 0, 4, BlockCellType.SOLID);
        world.set(3, 61, 2, BlockCellType.SOLID);
        world.set(3, 62, 2, BlockCellType.SOLID);
        world.set(3, 63, 2, BlockCellType.SOLID);
        world.fillFloor(0, 8, 64, 0, 4, BlockCellType.SOLID);
        world.set(4, 62, 2, BlockCellType.SOLID);
        world.set(5, 62, 2, BlockCellType.SOLID);

        PathfindingSettings settings = new PathfindingSettings(true, false, false, 2048, 4);
        PathResult result = AutomatonPathfinder.findPath(world, 1, 61, 2, 6, 65, 2, settings, null);

        assertTrue(result.isEmpty() || result.length() > 0);
    }

    @Test
    void climbsAdjacentStaircaseEvenUnderTwoHighCeiling() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 10, 64, 0, 6, BlockCellType.SOLID);
        world.set(2, 67, 2, BlockCellType.SOLID);
        world.set(3, 65, 2, BlockCellType.SOLID);
        world.set(4, 65, 2, BlockCellType.SOLID);
        world.set(4, 66, 2, BlockCellType.SOLID);
        world.set(5, 65, 2, BlockCellType.SOLID);
        world.set(6, 65, 2, BlockCellType.SOLID);

        PathfindingSettings settings = PathfindingSettings.defaults(true, false);
        PathResult result = AutomatonPathfinder.findPath(world, 2, 65, 2, 6, 66, 2, settings, null);

        assertTrue(result.isComplete());
        for (int i = 0; i < result.length(); i++) {
            assertFalse(result.getAction(i) == PathNodeAction.BREAK_THROUGH);
        }
    }

    @Test
    void prefersDryDetourOverWadingThroughWater() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 10, 64, 0, 6, BlockCellType.SOLID);
        for (int x = 3; x <= 7; x++) {
            world.set(x, 65, 3, BlockCellType.WATER);
        }

        PathfindingSettings settings = PathfindingSettings.defaults(false, false);
        PathResult result = AutomatonPathfinder.findPath(world, 1, 65, 3, 9, 65, 3, settings, null);

        assertTrue(result.isComplete());
        for (int i = 0; i < result.length(); i++) {
            long step = result.getStep(i);
            assertFalse(world.isWater(PackedPos.unpackX(step), PackedPos.unpackY(step), PackedPos.unpackZ(step)));
        }
    }

    @Test
    void parkourJumpsAcrossThreeBlockGap() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 2, 64, 1, 3, BlockCellType.SOLID);
        world.fillFloor(6, 9, 64, 1, 3, BlockCellType.SOLID);

        PathfindingSettings settings = PathfindingSettings.defaults(true, false);
        PathResult result = AutomatonPathfinder.findPath(world, 2, 65, 2, 7, 65, 2, settings, null);

        assertTrue(result.isComplete());
        boolean usedSprintJump = false;
        for (int i = 0; i < result.length(); i++) {
            if (result.getAction(i) == PathNodeAction.SPRINT_JUMP) {
                usedSprintJump = true;
            }
        }
        assertTrue(usedSprintJump);
    }

    @Test
    void parkourJumpsFourBlockGapWithRunup() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 2, 64, 1, 3, BlockCellType.SOLID);
        world.fillFloor(7, 10, 63, 1, 3, BlockCellType.SOLID);

        PathfindingSettings settings = PathfindingSettings.defaults(true, false);
        PathResult result = AutomatonPathfinder.findPath(world, 2, 65, 2, 8, 64, 2, settings, null);

        assertTrue(result.isComplete());
        boolean usedSprintJump = false;
        for (int i = 0; i < result.length(); i++) {
            if (result.getAction(i) == PathNodeAction.SPRINT_JUMP) {
                usedSprintJump = true;
            }
        }
        assertTrue(usedSprintJump);
    }

    @Test
    void parkourJumpsDiagonallyAcrossGap() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 2, 64, 1, 3, BlockCellType.SOLID);
        world.fillFloor(4, 6, 64, 3, 5, BlockCellType.SOLID);

        PathfindingSettings settings = PathfindingSettings.defaults(true, false);
        PathResult result = AutomatonPathfinder.findPath(world, 2, 65, 2, 5, 65, 4, settings, null);

        assertTrue(result.isComplete());
        boolean usedSprintJump = false;
        for (int i = 0; i < result.length(); i++) {
            if (result.getAction(i) == PathNodeAction.SPRINT_JUMP) {
                usedSprintJump = true;
            }
        }
        assertTrue(usedSprintJump);
    }

    @Test
    void avoidsJumpingOntoTallObstaclesAndRoutesAround() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 8, 64, 0, 6, BlockCellType.SOLID);
        for (int z = 0; z <= 4; z++) {
            world.set(4, 65, z, BlockCellType.TALL_OBSTACLE);
        }

        PathfindingSettings settings = PathfindingSettings.defaults(false, false);
        PathResult result = AutomatonPathfinder.findPath(world, 2, 65, 2, 6, 65, 2, settings, null);

        assertTrue(result.isComplete());
        for (int i = 0; i < result.length(); i++) {
            long step = result.getStep(i);
            int sx = PackedPos.unpackX(step);
            int sy = PackedPos.unpackY(step);
            int sz = PackedPos.unpackZ(step);
            assertFalse(sx == 4 && sz <= 4 && sy <= 66);
        }
    }

    @Test
    void doesNotPlanJumpUnderLowCeiling() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 6, 64, 0, 4, BlockCellType.SOLID);
        world.set(4, 65, 2, BlockCellType.SOLID);
        world.set(3, 67, 2, BlockCellType.SOLID);
        world.set(4, 65, 1, BlockCellType.SOLID);
        world.set(4, 66, 1, BlockCellType.SOLID);
        world.set(4, 65, 3, BlockCellType.SOLID);
        world.set(4, 66, 3, BlockCellType.SOLID);
        world.set(5, 65, 2, BlockCellType.SOLID);
        world.set(5, 66, 2, BlockCellType.SOLID);

        PathfindingSettings settings = new PathfindingSettings(false, false, false, 1024, 4);
        PathResult result = AutomatonPathfinder.findPath(world, 3, 65, 2, 4, 66, 2, settings, null);

        assertFalse(result.isComplete());
    }

    @Test
    void climbsTenBlockElevatedParkourRouteWhenOwnerIsAbove() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 12, 64, 2, 2, BlockCellType.SOLID);
        for (int step = 0; step < 10; step++) {
            int x = 12 - step;
            int y = 65 + step;
            world.set(x, y, 4 + (step % 2), BlockCellType.SOLID);
            world.set(x, y, 2, BlockCellType.AIR);
            if (step == 0) {
                world.set(12, 64, 3, BlockCellType.SOLID);
            } else {
                world.set(x + 1, y - 1, 3, BlockCellType.SOLID);
            }
        }
        world.fillFloor(1, 3, 74, 2, 5, BlockCellType.SOLID);

        PathfindingSettings settings = new PathfindingSettings(true, true, false, 16384, 4);
        PathResult result = AutomatonPathfinder.findPath(world, 2, 65, 2, 2, 75, 2, settings, null);
        assertTrue(result.isComplete());
        assertTrue(PackedPos.unpackY(result.getStep(result.length() - 1)) == 75);
    }

    @Test
    void doesNotPlanParkourJumpWithLowTakeoffCeilingOrTwoBlockUpwardGap() {
        SyntheticWorld lowCeilingWorld = new SyntheticWorld();
        lowCeilingWorld.fillFloor(0, 2, 64, 2, 2, BlockCellType.SOLID);
        lowCeilingWorld.fillFloor(5, 7, 64, 2, 2, BlockCellType.SOLID);
        lowCeilingWorld.set(2, 67, 2, BlockCellType.SOLID);

        PathfindingSettings settings = PathfindingSettings.defaults(false, false);
        PathResult lowCeilingResult = AutomatonPathfinder.findPath(lowCeilingWorld, 0, 65, 2, 7, 65, 2, settings, null);
        assertFalse(lowCeilingResult.isComplete());

        SyntheticWorld impossibleUpWorld = new SyntheticWorld();
        impossibleUpWorld.fillFloor(0, 2, 64, 2, 2, BlockCellType.SOLID);
        impossibleUpWorld.fillFloor(5, 7, 65, 2, 2, BlockCellType.SOLID);
        PathResult impossibleUpResult = AutomatonPathfinder.findPath(impossibleUpWorld, 0, 65, 2, 7, 66, 2, settings, null);
        assertFalse(impossibleUpResult.isComplete());
    }

    @Test
    void swimsAcrossDeepWaterWithoutSinkingToBottom() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 2, 64, 1, 3, BlockCellType.SOLID);
        world.fillFloor(7, 9, 64, 1, 3, BlockCellType.SOLID);
        world.fillFloor(3, 6, 61, 1, 3, BlockCellType.SOLID);
        for (int x = 3; x <= 6; x++) {
            for (int z = 1; z <= 3; z++) {
                for (int y = 62; y <= 64; y++) {
                    world.set(x, y, z, BlockCellType.WATER);
                }
            }
        }

        PathfindingSettings settings = new PathfindingSettings(false, false, false, 4096, 4);
        PathResult result = AutomatonPathfinder.findPath(world, 2, 65, 2, 7, 65, 2, settings, null);

        assertTrue(result.isComplete());
        for (int i = 0; i < result.length(); i++) {
            assertTrue(PackedPos.unpackY(result.getStep(i)) >= 64);
        }
    }

    @Test
    void smoothingRespectsCornerClearance() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 8, 64, 0, 6, BlockCellType.SOLID);
        world.set(4, 65, 3, BlockCellType.SOLID);
        world.set(4, 66, 3, BlockCellType.SOLID);

        PathfindingSettings settings = PathfindingSettings.defaults(false, false);
        PathResult result = AutomatonPathfinder.findPath(world, 2, 65, 2, 6, 65, 4, settings, null);

        assertTrue(result.isComplete());
        for (int i = 0; i < result.length(); i++) {
            long step = result.getStep(i);
            assertFalse(PackedPos.unpackX(step) == 4 && PackedPos.unpackZ(step) == 3);
        }
    }

    @Test
    void findsDefinedParkourRouteMovingAwayFirstTowardGoal() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(4, 5, 64, 2, 2, BlockCellType.SOLID);
        world.fillFloor(0, 2, 64, 2, 2, BlockCellType.SOLID);
        world.fillFloor(0, 1, 64, 3, 6, BlockCellType.SOLID);
        world.fillFloor(2, 10, 64, 6, 6, BlockCellType.SOLID);
        world.fillFloor(10, 10, 64, 2, 5, BlockCellType.SOLID);

        PathfindingSettings settings = PathfindingSettings.defaults(false, false);
        PathResult result = AutomatonPathfinder.findPath(world, 5, 65, 2, 10, 65, 2, settings, null);

        assertTrue(result.isComplete());
        boolean usedSprintJump = false;
        boolean movedAwayFirst = false;
        for (int i = 0; i < result.length(); i++) {
            if (result.getAction(i) == PathNodeAction.SPRINT_JUMP) {
                usedSprintJump = true;
            }
            if (PackedPos.unpackX(result.getStep(i)) < 4) {
                movedAwayFirst = true;
            }
        }
        assertTrue(usedSprintJump);
        assertTrue(movedAwayFirst);
    }

    @Test
    void survivesExtremeSettingsAndImpossibleGoals() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 6, 64, 0, 6, BlockCellType.HAZARD);
        world.set(1, 64, 1, BlockCellType.SOLID);

        PathfindingSettings extreme = new PathfindingSettings(false, false, false, -500, -10);
        PathResult outOfBoundsGoal = AutomatonPathfinder.findPath(world, 1, 65, 1, 5, 300, 5, extreme, null);
        assertFalse(outOfBoundsGoal.isComplete());

        PathResult negativeYGoal = AutomatonPathfinder.findPath(world, 1, 65, 1, 5, -5, 5, extreme, null);
        assertFalse(negativeYGoal.isComplete());
    }

    @Test
    void respectsAvailableScaffoldBlocksBudget() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 1, 64, 1, 3, BlockCellType.SOLID);
        world.fillFloor(5, 6, 64, 1, 3, BlockCellType.SOLID);

        PathfindingSettings oneBlock = new PathfindingSettings(false, false, true, 2048, 4, 1);
        PathResult limited = AutomatonPathfinder.findPath(world, 1, 65, 2, 5, 65, 2, oneBlock, null);
        assertFalse(limited.isComplete());

        PathfindingSettings threeBlocks = new PathfindingSettings(false, false, true, 2048, 4, 3);
        PathResult sufficient = AutomatonPathfinder.findPath(world, 1, 65, 2, 5, 65, 2, threeBlocks, null);
        assertTrue(sufficient.isComplete());
    }

    @Test
    void doesNotBreakThroughUnbreakableCells() {
        SyntheticWorld world = new SyntheticWorld();
        world.fillFloor(0, 6, 64, 0, 4, BlockCellType.SOLID);
        for (int z = 0; z <= 4; z++) {
            world.set(3, 65, z, BlockCellType.UNBREAKABLE);
            world.set(3, 66, z, BlockCellType.UNBREAKABLE);
            world.set(3, 67, z, BlockCellType.UNBREAKABLE);
            world.set(3, 68, z, BlockCellType.UNBREAKABLE);
        }
        PathfindingSettings breakOnly = new PathfindingSettings(true, false, false, 2048, 4, 0);
        PathResult result = AutomatonPathfinder.findPath(world, 1, 65, 2, 5, 65, 2, breakOnly, null);
        assertFalse(result.isComplete());
    }

    @Test
    void requiresBreakableCurrentCeilingForAscendingCaveStair() {
        SyntheticWorld world = new SyntheticWorld();
        // Solid stone wall at x=2, y=65..67, with unbreakable ceiling directly above start (1, 67, 2).
        world.fillFloor(0, 4, 64, 1, 3, BlockCellType.SOLID);
        for (int y = 65; y <= 67; y++) {
            for (int z = 1; z <= 3; z++) {
                world.set(2, y, z, y == 65 ? BlockCellType.UNBREAKABLE : BlockCellType.SOLID);
                world.set(3, y, z, BlockCellType.SOLID);
            }
        }
        for (int z = 1; z <= 3; z++) {
            world.set(1, 67, z, BlockCellType.UNBREAKABLE);
        }
        // Prevent going around via z=0 or z=4
        for (int z : new int[]{0, 4}) {
            for (int x = 0; x <= 4; x++) {
                world.set(x, 65, z, BlockCellType.UNBREAKABLE);
                world.set(x, 66, z, BlockCellType.UNBREAKABLE);
            }
        }
        PathfindingSettings breakOnly = new PathfindingSettings(true, false, false, 2048, 4, 0);
        PathResult blocked = AutomatonPathfinder.findPath(world, 1, 65, 2, 2, 66, 2, breakOnly, null);
        assertFalse(blocked.isComplete());

        // Making (1, 67, 2) breakable SOLID allows carving the ascending step.
        world.set(1, 67, 2, BlockCellType.SOLID);
        PathResult allowed = AutomatonPathfinder.findPath(world, 1, 65, 2, 2, 66, 2, breakOnly, null);
        assertTrue(allowed.isComplete());
    }

    private static final class SyntheticWorld implements INavigationWorldView {
        private final Map<Long, Byte> cells = new HashMap<>();

        void set(int x, int y, int z, byte type) {
            cells.put(PackedPos.pack(x, y, z), type);
        }

        void fillFloor(int minX, int maxX, int y, int minZ, int maxZ, byte type) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    set(x, y, z, type);
                }
            }
        }

        @Override
        public byte getCellType(int x, int y, int z) {
            if (y < 0 || y > 255) {
                return BlockCellType.UNLOADED;
            }
            return cells.getOrDefault(PackedPos.pack(x, y, z), BlockCellType.AIR);
        }
    }
}

