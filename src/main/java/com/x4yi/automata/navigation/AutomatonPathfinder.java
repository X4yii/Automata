
package com.x4yi.automata.navigation;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

public final class AutomatonPathfinder {

    private static final int[] CARDINAL_DX = {1, -1, 0, 0};
    private static final int[] CARDINAL_DZ = {0, 0, 1, -1};
    private static final int[] DIAG_DX = {1, 1, -1, -1};
    private static final int[] DIAG_DZ = {1, -1, 1, -1};

    private static final float COST_CARDINAL = 1.0F;
    private static final float COST_DIAGONAL = 1.4142135F;
    private static final float COST_JUMP_UP = 1.35F;
    private static final float COST_FALL_PER_BLOCK = 1.15F;
    private static final float COST_SPRINT_JUMP_BASE = 1.25F;
    private static final float COST_PILLAR_UP = 9.0F;
    private static final float COST_BRIDGE = 6.5F;
    private static final float COST_BREAK_THROUGH = 140.0F;
    private static final float COST_ENCLOSED_STEP = 0.35F;
    private static final float COST_TURN_PENALTY = 0.15F;
    private static final float COST_EDGE_HAZARD_PENALTY = 0.4F;
    private static final float COST_WATER_PENALTY = 5.5F;
    private static final float COST_OPEN_DOOR = 1.5F;
    private static final float HEURISTIC_WEIGHT = 1.05F;
    private static final float[] BARITONE_COEFFICIENTS = {1.5F, 2.0F, 2.5F, 3.0F, 4.0F, 5.0F, 10.0F};
    private static final int[][] DIAG_PARKOUR_OFFSETS = {
            {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
            {1, 2}, {1, -2}, {-1, 2}, {-1, -2},
            {2, 2}, {2, -2}, {-2, 2}, {-2, -2},
            {3, 1}, {3, -1}, {-3, 1}, {-3, -1},
            {1, 3}, {1, -3}, {-1, 3}, {-1, -3},
            {3, 2}, {3, -2}, {-3, 2}, {-3, -2},
            {2, 3}, {2, -3}, {-2, 3}, {-2, -3}
    };

    private static final ThreadLocal<ScratchBuffers> SCRATCH =
            ThreadLocal.withInitial(() -> new ScratchBuffers(PathfindingSettings.DEFAULT_MAX_NODES));

    private AutomatonPathfinder() {}

    public static PathResult findPath(
            INavigationWorldView world,
            int startX,
            int startY,
            int startZ,
            int goalX,
            int goalY,
            int goalZ,
            PathfindingSettings settings,
            @Nullable AtomicBoolean cancelled) {
        return findPath(world, startX, startY, startZ, goalX, goalY, goalZ, settings, cancelled, null);
    }

    public static PathResult findPath(
            INavigationWorldView world,
            int startX,
            int startY,
            int startZ,
            int goalX,
            int goalY,
            int goalZ,
            PathfindingSettings settings,
            @Nullable AtomicBoolean cancelled,
            @Nullable it.unimi.dsi.fastutil.longs.Long2FloatMap nodePenalties) {
        return runSinglePass(world, startX, startY, startZ, goalX, goalY, goalZ, settings, cancelled, nodePenalties);
    }

    private static PathResult runSinglePass(
            INavigationWorldView world,
            int startX,
            int startY,
            int startZ,
            int goalX,
            int goalY,
            int goalZ,
            PathfindingSettings settings,
            @Nullable AtomicBoolean cancelled,
            @Nullable it.unimi.dsi.fastutil.longs.Long2FloatMap nodePenalties) {
        long startNanos = System.nanoTime();
        long startKey = PackedPos.pack(startX, startY, startZ);
        long goalKey = PackedPos.pack(goalX, goalY, goalZ);

        if (startKey == goalKey) {
            return new PathResult(new long[] {startKey}, new byte[] {PathNodeAction.WALK}, true, 0, System.nanoTime() - startNanos);
        }

        ScratchBuffers buf = SCRATCH.get();
        buf.reset();
        buf.activePenalties = nodePenalties;
        try {
            buf.table.putOrUpdate(startKey, startKey, 0.0F, PathNodeAction.WALK, PrimitiveNodeTable.STATE_OPEN);
            float startH = heuristic(startX, startY, startZ, goalX, goalY, goalZ);
            buf.heap.push(startKey, startH);

            long bestKey = startKey;
            float bestH = startH;
            long[] coeffBestKeys = new long[BARITONE_COEFFICIENTS.length];
            float[] coeffBestScores = new float[BARITONE_COEFFICIENTS.length];
            for (int i = 0; i < BARITONE_COEFFICIENTS.length; i++) {
                coeffBestKeys[i] = startKey;
                coeffBestScores[i] = BARITONE_COEFFICIENTS[i] * startH;
            }
            int expanded = 0;
            int maxNodes = Math.max(64, settings.maxExpandedNodes());
            long maxDurationNanos = settings.maxDurationNanos();

            while (!buf.heap.isEmpty() && expanded < maxNodes) {
                if ((expanded & 63) == 0) {
                    if (cancelled != null && cancelled.get()) {
                        return PathResult.EMPTY;
                    }
                    if (expanded > 0 && maxDurationNanos > 0L && (System.nanoTime() - startNanos) >= maxDurationNanos) {
                        break;
                    }
                }

                long currentKey = buf.heap.popKey();
                int currentSlot = buf.table.findSlot(currentKey);
                if (currentSlot < 0 || buf.table.getState(currentSlot) == PrimitiveNodeTable.STATE_CLOSED) {
                    continue;
                }

                buf.table.setClosed(currentSlot);
                float currentG = buf.table.getGScore(currentSlot);
                buf.currentScaffolds = buf.table.getScaffoldsUsed(currentSlot);
                long grandParent = buf.table.getParent(currentSlot);
                if (grandParent == currentKey) {
                    buf.currentPrevDx = 0;
                    buf.currentPrevDz = 0;
                } else {
                    buf.currentPrevDx = Integer.compare(PackedPos.unpackX(currentKey), PackedPos.unpackX(grandParent));
                    buf.currentPrevDz = Integer.compare(PackedPos.unpackZ(currentKey), PackedPos.unpackZ(grandParent));
                }
                expanded++;

                int cx = PackedPos.unpackX(currentKey);
                int cy = PackedPos.unpackY(currentKey);
                int cz = PackedPos.unpackZ(currentKey);

                if (currentKey == goalKey) {
                    return reconstructPath(world, buf.table, currentKey, startKey, true, expanded, System.nanoTime() - startNanos, buf);
                }

                float currentH = heuristic(cx, cy, cz, goalX, goalY, goalZ);
                if (currentH < bestH) {
                    bestH = currentH;
                    bestKey = currentKey;
                }
                for (int i = 0; i < BARITONE_COEFFICIENTS.length; i++) {
                    float fCoeff = currentG + BARITONE_COEFFICIENTS[i] * currentH;
                    if (fCoeff < coeffBestScores[i]) {
                        coeffBestScores[i] = fCoeff;
                        coeffBestKeys[i] = currentKey;
                    }
                }

                expandCardinalAndVertical(world, buf, settings, currentKey, cx, cy, cz, currentG, goalX, goalY, goalZ);
                expandDiagonals(world, buf, settings, currentKey, cx, cy, cz, currentG, goalX, goalY, goalZ);
            }

            long partialKey = selectBestPartialKey(startX, startY, startZ, goalY, startKey, startH, bestKey, bestH, coeffBestKeys);
            boolean reached = partialKey == goalKey;
            if (!reached && partialKey == startKey) {
                return PathResult.EMPTY;
            }
            return reconstructPath(world, buf.table, partialKey, startKey, reached, expanded, System.nanoTime() - startNanos, buf);
        } finally {
            buf.activePenalties = null;
        }
    }

    private static long selectBestPartialKey(
            int startX,
            int startY,
            int startZ,
            int goalY,
            long startKey,
            float startH,
            long bestKey,
            float bestH,
            long[] coeffBestKeys) {
        if (goalY >= startY && bestKey != startKey && bestH < startH - 1.5F) {
            int bdx = PackedPos.unpackX(bestKey) - startX;
            int bdy = PackedPos.unpackY(bestKey) - startY;
            int bdz = PackedPos.unpackZ(bestKey) - startZ;
            if ((bdx * bdx + bdy * bdy + bdz * bdz) >= 4) {
                return bestKey;
            }
        }
        int startIdx = goalY >= startY ? coeffBestKeys.length - 1 : 0;
        int step = goalY >= startY ? -1 : 1;
        for (int i = startIdx; i >= 0 && i < coeffBestKeys.length; i += step) {
            long candidate = coeffBestKeys[i];
            if (candidate == startKey) {
                continue;
            }
            int dx = PackedPos.unpackX(candidate) - startX;
            int dy = PackedPos.unpackY(candidate) - startY;
            int dz = PackedPos.unpackZ(candidate) - startZ;
            if ((dx * dx + dy * dy + dz * dz) >= 16) {
                return candidate;
            }
        }
        if (bestKey != startKey && bestH < startH - 1.5F) {
            return bestKey;
        }
        return startKey;
    }

    private static void expandCardinalAndVertical(
            INavigationWorldView world,
            ScratchBuffers buf,
            PathfindingSettings settings,
            long parentKey,
            int cx,
            int cy,
            int cz,
            float currentG,
            int goalX,
            int goalY,
            int goalZ) {
        int parentSlot = buf.table.findSlot(parentKey);
        int parentScaffolds = parentSlot >= 0 ? buf.table.getScaffoldsUsed(parentSlot) : 0;
        boolean canScaffoldMore = settings.allowScaffolding()
                && (settings.availableScaffoldBlocks() == Integer.MAX_VALUE
                        || parentScaffolds < settings.availableScaffoldBlocks());

        for (int dir = 0; dir < 4; dir++) {
            int dx = CARDINAL_DX[dir];
            int dz = CARDINAL_DZ[dir];
            int nx = cx + dx;
            int nz = cz + dz;

            if (canStandAt(world, nx, cy, nz)) {
                offerNode(world, buf, parentKey, nx, cy, nz, currentG + COST_CARDINAL, PathNodeAction.WALK, goalX, goalY, goalZ);
            } else if (canSwimAt(world, cx, cy, cz, nx, cy, nz)) {
                offerNode(world, buf, parentKey, nx, cy, nz, currentG + COST_CARDINAL, PathNodeAction.WALK, goalX, goalY, goalZ);
                if (world.isPassable(cx, cy + 2, cz) && canSwimAt(world, cx, cy, cz, nx, cy + 1, nz)) {
                    offerNode(world, buf, parentKey, nx, cy + 1, nz, currentG + COST_JUMP_UP, PathNodeAction.JUMP, goalX, goalY, goalZ);
                }
                if (canSwimAt(world, cx, cy, cz, nx, cy - 1, nz)) {
                    offerNode(world, buf, parentKey, nx, cy - 1, nz, currentG + COST_FALL_PER_BLOCK, PathNodeAction.FALL, goalX, goalY, goalZ);
                }
            } else if (world.isPassable(cx, cy + 2, cz) && canStandAt(world, nx, cy + 1, nz)) {
                offerNode(world, buf, parentKey, nx, cy + 1, nz, currentG + COST_JUMP_UP, PathNodeAction.JUMP, goalX, goalY, goalZ);
            } else if (world.isPassable(nx, cy, nz) && world.isPassable(nx, cy + 1, nz)) {
                int landY = traceSafeFallY(world, nx, cy, nz, settings.maxFallDistance());
                if (landY != Integer.MIN_VALUE) {
                    int drop = cy - landY;
                    float fallCost = COST_CARDINAL + drop * COST_FALL_PER_BLOCK;
                    offerNode(world, buf, parentKey, nx, landY, nz, currentG + fallCost, PathNodeAction.FALL, goalX, goalY, goalZ);
                } else if (canScaffoldMore
                        && world.getCellType(nx, cy - 1, nz) != BlockCellType.HAZARD) {
                    offerNode(world, buf, parentKey, nx, cy, nz, currentG + COST_BRIDGE, PathNodeAction.BRIDGE, goalX, goalY, goalZ);
                }
            } else if (settings.allowBreakThrough()) {
                tryBreakThroughMoves(world, buf, parentKey, cx, cy, cz, nx, nz, currentG, goalX, goalY, goalZ);
            }

            if (settings.allowSprintJump() && world.isPassable(cx, cy + 2, cz)) {
                trySprintJump(world, buf, settings, parentKey, cx, cy, cz, dx, dz, currentG, goalX, goalY, goalZ);
            }
        }

        if (settings.allowSprintJump() && world.isPassable(cx, cy + 2, cz)) {
            tryDiagonalParkourJumps(world, buf, parentKey, cx, cy, cz, currentG, goalX, goalY, goalZ);
        }

        if (world.isWater(cx, cy, cz)) {
            if (world.isPassable(cx, cy + 1, cz) && world.isWater(cx, cy + 1, cz)) {
                offerNode(world, buf, parentKey, cx, cy + 1, cz, currentG + COST_JUMP_UP, PathNodeAction.JUMP, goalX, goalY, goalZ);
            }
            if (world.isPassable(cx, cy - 1, cz) && world.isWater(cx, cy - 1, cz)) {
                offerNode(world, buf, parentKey, cx, cy - 1, cz, currentG + COST_FALL_PER_BLOCK, PathNodeAction.FALL, goalX, goalY, goalZ);
            }
        }

        if (canScaffoldMore
                && world.isPassable(cx, cy + 1, cz)
                && world.isPassable(cx, cy + 2, cz)
                && world.getCellType(cx, cy, cz) != BlockCellType.HAZARD) {
            offerNode(world, buf, parentKey, cx, cy + 1, cz, currentG + COST_PILLAR_UP, PathNodeAction.PILLAR_UP, goalX, goalY, goalZ);
        }
    }

    private static void tryBreakThroughMoves(
            INavigationWorldView world,
            ScratchBuffers buf,
            long parentKey,
            int cx,
            int cy,
            int cz,
            int nx,
            int nz,
            float currentG,
            int goalX,
            int goalY,
            int goalZ) {
        if (goalY > cy
                && isBreakableCell(world, cx, cy + 2, cz)
                && isBreakableCell(world, nx, cy + 1, nz)
                && isBreakableCell(world, nx, cy + 2, nz)
                && world.isSolidSupport(nx, cy, nz)) {
            offerNode(world, buf, parentKey, nx, cy + 1, nz, currentG + COST_BREAK_THROUGH + 2.0F, PathNodeAction.BREAK_THROUGH, goalX, goalY, goalZ);
        }
        if (goalY < cy
                && isBreakableCell(world, nx, cy - 1, nz)
                && isBreakableCell(world, nx, cy, nz)
                && isBreakableCell(world, nx, cy + 1, nz)
                && world.isSolidSupport(nx, cy - 2, nz)) {
            offerNode(world, buf, parentKey, nx, cy - 1, nz, currentG + COST_BREAK_THROUGH + 2.0F, PathNodeAction.BREAK_THROUGH, goalX, goalY, goalZ);
        }
        if (isBreakableCell(world, nx, cy, nz)
                && isBreakableCell(world, nx, cy + 1, nz)
                && world.isSolidSupport(nx, cy - 1, nz)) {
            offerNode(world, buf, parentKey, nx, cy, nz, currentG + COST_BREAK_THROUGH, PathNodeAction.BREAK_THROUGH, goalX, goalY, goalZ);
        }
    }

    private static boolean isBreakableCell(INavigationWorldView world, int x, int y, int z) {
        if (y < 0 || y > 255) {
            return false;
        }
        byte cell = world.getCellType(x, y, z);
        return cell != BlockCellType.HAZARD
                && cell != BlockCellType.UNLOADED
                && cell != BlockCellType.UNBREAKABLE;
    }

    private static void trySprintJump(
            INavigationWorldView world,
            ScratchBuffers buf,
            PathfindingSettings settings,
            long parentKey,
            int cx,
            int cy,
            int cz,
            int dx,
            int dz,
            float currentG,
            int goalX,
            int goalY,
            int goalZ) {
        if (!world.isPassable(cx, cy + 2, cz)) {
            return;
        }
        int parentSlot = buf.table.findSlot(parentKey);
        long grandParent = parentSlot >= 0 ? buf.table.getParent(parentSlot) : parentKey;
        boolean straightRunup = grandParent == parentKey
                || (PackedPos.unpackX(grandParent) == cx - dx
                        && PackedPos.unpackZ(grandParent) == cz - dz
                        && PackedPos.unpackY(grandParent) == cy);
        boolean hasRunup = straightRunup
                && canStandAt(world, cx - dx, cy, cz - dz)
                && world.isPassable(cx - dx, cy + 2, cz - dz);
        int maxGap = hasRunup ? 4 : 2;
        for (int gap = 1; gap <= maxGap; gap++) {
            int checkX = cx + dx * gap;
            int checkZ = cz + dz * gap;
            if (!world.isPassable(checkX, cy, checkZ)
                    || !world.isPassable(checkX, cy + 1, checkZ)
                    || !world.isPassable(checkX, cy + 2, checkZ)
                    || world.isClosedDoor(checkX, cy, checkZ)
                    || world.isClosedDoor(checkX, cy + 1, checkZ)) {
                return;
            }
            if (world.isHazard(checkX, cy - 1, checkZ)) {
                return;
            }
            int landX = cx + dx * (gap + 1);
            int landZ = cz + dz * (gap + 1);
            if (gap <= 3 && canStandAt(world, landX, cy, landZ) && world.isPassable(landX, cy + 2, landZ)) {
                float cost = COST_SPRINT_JUMP_BASE + gap * 0.9F;
                offerNode(world, buf, parentKey, landX, cy, landZ, currentG + cost, PathNodeAction.SPRINT_JUMP, goalX, goalY, goalZ);
            }
            if (gap <= 1
                    && world.isPassable(checkX, cy + 3, checkZ)
                    && canStandAt(world, landX, cy + 1, landZ)
                    && world.isPassable(landX, cy + 3, landZ)) {
                float cost = COST_SPRINT_JUMP_BASE + gap * 1.1F + 0.5F;
                offerNode(world, buf, parentKey, landX, cy + 1, landZ, currentG + cost, PathNodeAction.SPRINT_JUMP, goalX, goalY, goalZ);
            }
            if (canStandAt(world, landX, cy - 1, landZ) && world.isPassable(landX, cy + 1, landZ)) {
                float cost = COST_SPRINT_JUMP_BASE + gap * 0.95F + 0.3F;
                offerNode(world, buf, parentKey, landX, cy - 1, landZ, currentG + cost, PathNodeAction.SPRINT_JUMP, goalX, goalY, goalZ);
            }
            if (gap >= 2 && canStandAt(world, landX, cy - 2, landZ) && world.isPassable(landX, cy + 1, landZ)) {
                float cost = COST_SPRINT_JUMP_BASE + gap * 1.0F + 0.6F;
                offerNode(world, buf, parentKey, landX, cy - 2, landZ, currentG + cost, PathNodeAction.SPRINT_JUMP, goalX, goalY, goalZ);
            }
        }
    }

    private static void tryDiagonalParkourJumps(
            INavigationWorldView world,
            ScratchBuffers buf,
            long parentKey,
            int cx,
            int cy,
            int cz,
            float currentG,
            int goalX,
            int goalY,
            int goalZ) {
        if (!world.isPassable(cx, cy + 2, cz)) {
            return;
        }
        for (int[] off : DIAG_PARKOUR_OFFSETS) {
            int ox = off[0];
            int oz = off[1];
            if (!isDiagonalParkourCorridorClear(world, cx, cy, cz, ox, oz)) {
                continue;
            }
            int landX = cx + ox;
            int landZ = cz + oz;
            float dist = (float) Math.hypot(ox, oz);
            if (canStandAt(world, landX, cy, landZ) && world.isPassable(landX, cy + 2, landZ)) {
                float cost = COST_SPRINT_JUMP_BASE + dist * 0.85F;
                offerNode(world, buf, parentKey, landX, cy, landZ, currentG + cost, PathNodeAction.SPRINT_JUMP, goalX, goalY, goalZ);
            }
            if (dist <= 3.25F
                    && world.isPassable(cx, cy + 3, cz)
                    && canStandAt(world, landX, cy + 1, landZ)
                    && world.isPassable(landX, cy + 3, landZ)) {
                float cost = COST_SPRINT_JUMP_BASE + dist * 1.05F + 0.5F;
                offerNode(world, buf, parentKey, landX, cy + 1, landZ, currentG + cost, PathNodeAction.SPRINT_JUMP, goalX, goalY, goalZ);
            }
            if (canStandAt(world, landX, cy - 1, landZ) && world.isPassable(landX, cy + 1, landZ)) {
                float cost = COST_SPRINT_JUMP_BASE + dist * 0.9F + 0.3F;
                offerNode(world, buf, parentKey, landX, cy - 1, landZ, currentG + cost, PathNodeAction.SPRINT_JUMP, goalX, goalY, goalZ);
            }
            if (canStandAt(world, landX, cy - 2, landZ) && world.isPassable(landX, cy + 1, landZ)) {
                float cost = COST_SPRINT_JUMP_BASE + dist * 0.95F + 0.55F;
                offerNode(world, buf, parentKey, landX, cy - 2, landZ, currentG + cost, PathNodeAction.SPRINT_JUMP, goalX, goalY, goalZ);
            }
        }
    }

    private static boolean isDiagonalParkourCorridorClear(
            INavigationWorldView world,
            int cx,
            int cy,
            int cz,
            int ox,
            int oz) {
        int signX = Integer.signum(ox);
        int signZ = Integer.signum(oz);
        boolean xCornerFree = hasBodyClearance(world, cx + signX, cy, cz) && world.isPassable(cx + signX, cy + 2, cz);
        boolean zCornerFree = hasBodyClearance(world, cx, cy, cz + signZ) && world.isPassable(cx, cy + 2, cz + signZ);
        if (!xCornerFree && !zCornerFree) {
            return false;
        }
        int steps = Math.max(Math.abs(ox), Math.abs(oz));
        for (int s = 1; s < steps; s++) {
            float t = (float) s / (float) steps;
            int sx = cx + Math.round(ox * t);
            int sz = cz + Math.round(oz * t);
            if (!world.isPassable(sx, cy + 1, sz)
                    || !world.isPassable(sx, cy + 2, sz)
                    || world.isClosedDoor(sx, cy, sz)
                    || world.isClosedDoor(sx, cy + 1, sz)
                    || world.isHazard(sx, cy - 1, sz)) {
                return false;
            }
        }
        return true;
    }

    private static void expandDiagonals(
            INavigationWorldView world,
            ScratchBuffers buf,
            PathfindingSettings settings,
            long parentKey,
            int cx,
            int cy,
            int cz,
            float currentG,
            int goalX,
            int goalY,
            int goalZ) {
        for (int dir = 0; dir < 4; dir++) {
            int dx = DIAG_DX[dir];
            int dz = DIAG_DZ[dir];
            int nx = cx + dx;
            int nz = cz + dz;

            if (!hasBodyClearance(world, cx + dx, cy, cz) || !hasBodyClearance(world, cx, cy, cz + dz)) {
                continue;
            }

            if (canStandAt(world, nx, cy, nz) || canSwimAt(world, cx, cy, cz, nx, cy, nz)) {
                offerNode(world, buf, parentKey, nx, cy, nz, currentG + COST_DIAGONAL, PathNodeAction.WALK, goalX, goalY, goalZ);
            } else if (world.isPassable(nx, cy, nz) && world.isPassable(nx, cy + 1, nz)) {
                int landY = traceSafeFallY(world, nx, cy, nz, settings.maxFallDistance());
                if (landY != Integer.MIN_VALUE) {
                    int drop = cy - landY;
                    float fallCost = COST_DIAGONAL + drop * COST_FALL_PER_BLOCK;
                    offerNode(world, buf, parentKey, nx, landY, nz, currentG + fallCost, PathNodeAction.FALL, goalX, goalY, goalZ);
                }
            }
        }
    }

    private static boolean canStandAt(INavigationWorldView world, int x, int y, int z) {
        return world.isSolidSupport(x, y - 1, z) && hasBodyClearance(world, x, y, z);
    }

    private static boolean canSwimAt(INavigationWorldView world, int cx, int cy, int cz, int nx, int ny, int nz) {
        if (!(world.isWater(cx, cy, cz) || world.isWater(cx, cy - 1, cz) || world.isWater(nx, ny, nz) || world.isWater(nx, ny - 1, nz))) {
            return false;
        }
        if (world.isWater(nx, ny, nz)) {
            return world.isPassable(nx, ny, nz);
        }
        return world.isWater(nx, ny - 1, nz) && hasBodyClearance(world, nx, ny, nz);
    }

    private static boolean hasBodyClearance(INavigationWorldView world, int x, int y, int z) {
        return world.isPassable(x, y, z) && world.isPassable(x, y + 1, z);
    }

    public static int traceSafeFallY(INavigationWorldView world, int x, int startY, int z, int maxFall) {
        int minAllowedY = Math.max(0, startY - maxFall);
        for (int y = startY - 1; y >= minAllowedY; y--) {
            byte cell = world.getCellType(x, y, z);
            if (cell == BlockCellType.HAZARD || cell == BlockCellType.UNLOADED || cell == BlockCellType.TALL_OBSTACLE) {
                return Integer.MIN_VALUE;
            }
            if (cell == BlockCellType.SOLID || cell == BlockCellType.UNBREAKABLE) {
                return y + 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static void offerNode(
            INavigationWorldView world,
            ScratchBuffers buf,
            long parentKey,
            int nx,
            int ny,
            int nz,
            float tentativeG,
            byte action,
            int goalX,
            int goalY,
            int goalZ) {
        if (ny < 0 || ny > 255) {
            return;
        }
        float dirPenalty = directionPenalty(buf, parentKey, nx, nz);
        if (action == PathNodeAction.BREAK_THROUGH && dirPenalty > 0.0F) {
            dirPenalty += 2.0F;
        }
        long key = PackedPos.pack(nx, ny, nz);
        float failurePenalty = buf.activePenalties != null ? buf.activePenalties.get(key) : 0.0F;
        float doorPenalty = (world.isClosedDoor(nx, ny, nz) || world.isClosedDoor(nx, ny + 1, nz)) ? COST_OPEN_DOOR : 0.0F;
        float adjustedG = tentativeG
                + dirPenalty
                + failurePenalty
                + doorPenalty
                + edgeHazardPenalty(world, nx, ny, nz)
                + enclosedPenalty(world, nx, ny, nz)
                + waterPenalty(world, parentKey, nx, ny, nz, goalX, goalY, goalZ);
        int slot = buf.table.findOrInsertSlot(key);
        byte existingState = buf.table.getState(slot);
        if (existingState == PrimitiveNodeTable.STATE_CLOSED) {
            return;
        }
        if (existingState == PrimitiveNodeTable.STATE_OPEN && adjustedG >= buf.table.getGScore(slot)) {
            return;
        }
        int nextScaffolds = (action == PathNodeAction.BRIDGE || action == PathNodeAction.PILLAR_UP)
                ? Math.min(65535, buf.currentScaffolds + 1)
                : buf.currentScaffolds;
        buf.table.updateSlot(slot, parentKey, adjustedG, action, PrimitiveNodeTable.STATE_OPEN, (short) nextScaffolds);
        float fScore = adjustedG + heuristic(nx, ny, nz, goalX, goalY, goalZ) * HEURISTIC_WEIGHT;
        buf.heap.push(key, fScore);
    }

    private static float directionPenalty(ScratchBuffers buf, long parentKey, int nx, int nz) {
        if (buf.currentPrevDx == 0 && buf.currentPrevDz == 0) {
            return 0.0F;
        }
        int px = PackedPos.unpackX(parentKey);
        int pz = PackedPos.unpackZ(parentKey);
        int curDx = Integer.compare(nx, px);
        int curDz = Integer.compare(nz, pz);
        if (buf.currentPrevDx != curDx || buf.currentPrevDz != curDz) {
            return COST_TURN_PENALTY;
        }
        return 0.0F;
    }

    private static float edgeHazardPenalty(INavigationWorldView world, int nx, int ny, int nz) {
        for (int dir = 0; dir < 4; dir++) {
            int ax = nx + CARDINAL_DX[dir];
            int az = nz + CARDINAL_DZ[dir];
            if (world.isHazard(ax, ny, az) || world.isHazard(ax, ny - 1, az)) {
                return COST_EDGE_HAZARD_PENALTY;
            }
            if (world.isPassable(ax, ny - 1, az)
                    && world.isPassable(ax, ny - 2, az)
                    && world.isPassable(ax, ny - 3, az)) {
                return COST_EDGE_HAZARD_PENALTY;
            }
        }
        return 0.0F;
    }

    private static float enclosedPenalty(INavigationWorldView world, int nx, int ny, int nz) {
        return world.isPassable(nx, ny + 2, nz) ? 0.0F : COST_ENCLOSED_STEP;
    }

    private static float waterPenalty(
            INavigationWorldView world,
            long parentKey,
            int nx,
            int ny,
            int nz,
            int goalX,
            int goalY,
            int goalZ) {
        if (!(world.isWater(nx, ny, nz) || world.isWater(nx, ny - 1, nz))) {
            return 0.0F;
        }
        int px = PackedPos.unpackX(parentKey);
        int py = PackedPos.unpackY(parentKey);
        int pz = PackedPos.unpackZ(parentKey);
        boolean alreadyInWater = world.isWater(px, py, pz) || world.isWater(px, py - 1, pz);
        boolean goalInWater = world.isWater(goalX, goalY, goalZ) || world.isWater(goalX, goalY - 1, goalZ);
        return (alreadyInWater || goalInWater) ? 1.0F : COST_WATER_PENALTY;
    }

    private static float heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int dx = Math.abs(gx - x);
        int dy = Math.abs(gy - y);
        int dz = Math.abs(gz - z);
        int minXZ = Math.min(dx, dz);
        int maxXZ = Math.max(dx, dz);
        return (minXZ * COST_DIAGONAL) + (maxXZ - minXZ) + dy * 1.15F;
    }

    private static PathResult reconstructPath(
            INavigationWorldView world,
            PrimitiveNodeTable table,
            long endKey,
            long startKey,
            boolean complete,
            int expanded,
            long elapsedNanos,
            ScratchBuffers buf) {
        int count = 0;
        long cursor = endKey;
        while (true) {
            buf.ensureReverseCapacity(count + 1);
            int slot = table.findSlot(cursor);
            buf.reverseKeys[count] = cursor;
            buf.reverseActions[count] = slot >= 0 ? table.getAction(slot) : PathNodeAction.WALK;
            count++;
            if (cursor == startKey || slot < 0) {
                break;
            }
            long parent = table.getParent(slot);
            if (parent == cursor) {
                break;
            }
            cursor = parent;
        }

        long[] steps = new long[count];
        byte[] actions = new byte[count];
        for (int i = 0; i < count; i++) {
            int rev = count - 1 - i;
            steps[i] = buf.reverseKeys[rev];
            actions[i] = buf.reverseActions[rev];
        }
        return smoothWalkSegments(world, steps, actions, count, complete, expanded, elapsedNanos, buf);
    }

    private static PathResult smoothWalkSegments(
            INavigationWorldView world,
            long[] steps,
            byte[] actions,
            int count,
            boolean complete,
            int expanded,
            long elapsedNanos,
            ScratchBuffers buf) {
        if (count <= 2) {
            return new PathResult(steps, actions, complete, expanded, elapsedNanos);
        }
        long[] outSteps = new long[count];
        byte[] outActions = new byte[count];
        int out = 0;
        outSteps[out] = steps[0];
        outActions[out] = actions[0];
        out++;

        int anchor = 0;
        while (anchor < count - 1) {
            if (actions[anchor + 1] != PathNodeAction.WALK) {
                anchor++;
                outSteps[out] = steps[anchor];
                outActions[out] = actions[anchor];
                out++;
                continue;
            }
            int anchorY = PackedPos.unpackY(steps[anchor]);
            int farthest = anchor + 1;
            int maxProbe = Math.min(count, anchor + 25);
            for (int probe = anchor + 2; probe < maxProbe; probe++) {
                if (actions[probe] != PathNodeAction.WALK || PackedPos.unpackY(steps[probe]) != anchorY) {
                    break;
                }
                if (hasWalkLineOfSight(world, steps[anchor], steps[probe], buf)) {
                    farthest = probe;
                }
            }
            anchor = farthest;
            outSteps[out] = steps[anchor];
            outActions[out] = PathNodeAction.WALK;
            out++;
        }
        return new PathResult(
                java.util.Arrays.copyOf(outSteps, out),
                java.util.Arrays.copyOf(outActions, out),
                complete,
                expanded,
                elapsedNanos);
    }

    private static boolean hasWalkLineOfSight(INavigationWorldView world, long fromKey, long toKey, ScratchBuffers buf) {
        int y = PackedPos.unpackY(fromKey);
        int x0i = PackedPos.unpackX(fromKey);
        int z0i = PackedPos.unpackZ(fromKey);
        int x1i = PackedPos.unpackX(toKey);
        int z1i = PackedPos.unpackZ(toKey);
        boolean allowWater = world.isWater(x0i, y, z0i) || world.isWater(x1i, y, z1i);
        double x0 = x0i + 0.5D;
        double z0 = z0i + 0.5D;
        double x1 = x1i + 0.5D;
        double z1 = z1i + 0.5D;
        double dx = x1 - x0;
        double dz = z1 - z0;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001D) {
            return true;
        }
        double perpX = -dz / dist * 0.38D;
        double perpZ = dx / dist * 0.38D;
        int samples = Math.max(2, (int) Math.ceil(dist / 0.3D));
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            double px = x0 + dx * t;
            double pz = z0 + dz * t;
            int sx = (int) Math.floor(px);
            int sz = (int) Math.floor(pz);
            int sxPlus = (int) Math.floor(px + perpX);
            int szPlus = (int) Math.floor(pz + perpZ);
            int sxMinus = (int) Math.floor(px - perpX);
            int szMinus = (int) Math.floor(pz - perpZ);
            if (!isWalkSampleClear(world, sx, y, sz, allowWater, buf)
                    || !isWalkSampleClear(world, sxPlus, y, szPlus, allowWater, buf)
                    || !isWalkSampleClear(world, sxMinus, y, szMinus, allowWater, buf)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWalkSampleClear(
            INavigationWorldView world,
            int x,
            int y,
            int z,
            boolean allowWater,
            ScratchBuffers buf) {
        if (!canStandAt(world, x, y, z)) {
            return false;
        }
        if (world.isClosedDoor(x, y, z) || world.isClosedDoor(x, y + 1, z)) {
            return false;
        }
        if (!allowWater && (world.isWater(x, y, z) || world.isWater(x, y - 1, z))) {
            return false;
        }
        if (buf.activePenalties != null && buf.activePenalties.get(PackedPos.pack(x, y, z)) > 0.0F) {
            return false;
        }
        return true;
    }

    private static final class ScratchBuffers {
        final PrimitiveMinHeap heap;
        final PrimitiveNodeTable table;
        long[] reverseKeys;
        byte[] reverseActions;
        int currentScaffolds;
        int currentPrevDx;
        int currentPrevDz;
        @Nullable
        it.unimi.dsi.fastutil.longs.Long2FloatMap activePenalties;

        ScratchBuffers(int expectedNodes) {
            this.heap = new PrimitiveMinHeap(expectedNodes);
            this.table = new PrimitiveNodeTable(expectedNodes);
            this.reverseKeys = new long[256];
            this.reverseActions = new byte[256];
        }

        void reset() {
            heap.clear();
            table.clear();
        }

        void ensureReverseCapacity(int needed) {
            if (needed > reverseKeys.length) {
                int next = Math.max(needed, reverseKeys.length << 1);
                reverseKeys = java.util.Arrays.copyOf(reverseKeys, next);
                reverseActions = java.util.Arrays.copyOf(reverseActions, next);
            }
        }
    }
}

