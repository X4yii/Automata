package com.x4yi.automata.ai.jobs;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.ai.LogisticsService;
import com.x4yi.automata.ai.TaskCompletionService;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.movement.BlockPlacementService;
import com.x4yi.automata.navigation.PackedPos;

public class ExcavationJob implements IAutomatonJob {

    private static final long UNREACHABLE_TTL_TICKS = 80L;
    private static final int QUEUE_LOW_WATERMARK = 32;
    private static final double REANCHOR_DISTANCE_SQ = 144.0D;
    private static final long STAGNATION_REBUILD_TICKS = 40L;
    private static final double STAGNATION_RADIUS_SQ = 9.0D;

    private final AreaBlacklistTracker blacklistTracker = new AreaBlacklistTracker(UNREACHABLE_TTL_TICKS);
    private final BlockWorkQueue workQueue = new BlockWorkQueue(BlockWorkQueue.DEFAULT_CAPACITY);
    private final LongOpenHashSet placedSupportBlocks = new LongOpenHashSet();
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    private int planMinX;
    private int planMinY;
    private int planMinZ;
    private int planMaxX;
    private int planMaxY;
    private int planMaxZ;
    private boolean hasPlanBounds;
    private int streamY;
    private int streamX;
    private int streamZ;
    private boolean streamReverseX;
    private boolean streamExhausted;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private long lastProgressTick = Long.MIN_VALUE;

    @Nullable
    private BlockPos cachedTarget;

    @Override
    public ResourceLocation getJobId() {
        return AutomatonBuiltInJobs.EXCAVATION;
    }

    @Override
    public boolean hasCommittedWork(IAutomatonContext ctx) {
        return cachedTarget != null || !workQueue.isEmpty();
    }

    @Override
    public int copyPlannedBreakTargets(long[] dest, int maxCount) {
        int written = 0;
        int limit = Math.min(maxCount, dest.length);
        if (cachedTarget != null && written < limit) {
            dest[written++] = PackedPos.pack(cachedTarget.getX(), cachedTarget.getY(), cachedTarget.getZ());
        }
        if (written < limit) {
            written += workQueue.copyTo(dest, written, limit - written);
        }
        return written;
    }

    @Override
    public void onStop(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        if (cachedTarget != null) {
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }
        blacklistTracker.clear();
        workQueue.clear();
        placedSupportBlocks.clear();
        hasPlanBounds = false;
        streamExhausted = false;
        lastProgressTick = Long.MIN_VALUE;
    }

    @Override
    public void tick(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        if (ctx.getTaskConfig().getPos1() == null || ctx.getTaskConfig().getPos2() == null) {
            return;
        }
        EntityAutomaton bot = ctx.getAutomaton();
        WorldServer world = ctx.getWorld();
        long now = ctx.getWorldTime();

        if (lastProgressTick == Long.MIN_VALUE
                || ctx.getMiningController().isMining()
                || bot.getDistanceSq(anchorX, anchorY, anchorZ) > STAGNATION_RADIUS_SQ) {
            anchorX = bot.posX;
            anchorY = bot.posY;
            anchorZ = bot.posZ;
            lastProgressTick = now;
        }

        long placedPacked = ctx.getMovement().consumeLastPlacedBlock();
        if (placedPacked != Long.MIN_VALUE) {
            placedSupportBlocks.add(placedPacked);
        }

        if (ctx.getTaskConfig().getSavingChest() != null
                && LogisticsService.getInventoryOccupancyRatio(bot.getInventory().getMainInventory()) >= 0.90F) {
            if (cachedTarget != null) {
                JobBlockSupport.release(ctx, cachedTarget);
                cachedTarget = null;
            }
            ctx.stopNavigation();
            return;
        }

        if (cachedTarget != null && ctx.getMovement().getLastStuckGoal() != Long.MIN_VALUE) {
            ctx.getMovement().clearLastStuckGoal();
            blacklistTarget(cachedTarget, now);
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }

        JobBlockSupport.AreaBounds bounds = JobBlockSupport.resolveBounds(ctx.getTaskConfig(), bot.getPosition(), 4);
        syncPlanBounds(bounds);
        pruneClearedSupportBlocks(world);

        if (shouldRebuildOnStagnation(now, lastProgressTick)) {
            if (cachedTarget != null) {
                blacklistTarget(cachedTarget, now);
                JobBlockSupport.release(ctx, cachedTarget);
                cachedTarget = null;
            }
            reanchorStreamAtLayer(bounds, findHighestAccessibleLayer(world, bounds, now), bot);
            anchorX = bot.posX;
            anchorY = bot.posY;
            anchorZ = bot.posZ;
            lastProgressTick = now;
        } else if (cachedTarget != null && bot.getDistanceSqToCenter(cachedTarget) > REANCHOR_DISTANCE_SQ) {
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
            reanchorStreamAtLayer(bounds, Math.max(bounds.minY(), Math.min(bounds.maxY(), streamY)), bot);
        }

        BlockPos liquidToSeal = hasBuildingBlock(bot) ? findSealableSourceLiquid(bot, world, bounds, blacklistTracker, now) : null;
        if (liquidToSeal != null) {
            if (bot.getDistanceSqToCenter(liquidToSeal) <= JobBlockSupport.INTERACT_REACH_SQ) {
                ctx.stopNavigation();
                if (ctx.getMovement().sealLiquidAt(world, liquidToSeal)) {
                    placedSupportBlocks.add(liquidToSeal.toLong());
                } else if (!ctx.getMovement().isActionLookLocked()) {
                    blacklistTarget(liquidToSeal, now);
                }
                return;
            }
            BlockPos stand = JobBlockSupport.findNearestStandingSpot(world, liquidToSeal);
            if (stand != null && !ctx.isGoalOnCooldown(stand, now)) {
                ctx.requestPathTo(stand, false);
                return;
            }
            blacklistTarget(liquidToSeal, now);
        }

        BlockPos target = resolveOrFindExcavationBlock(ctx, bounds, now);
        if (target != null) {
            bot.getChunkLoader().setExtraWorkChunk(target);
            if (hasBuildingBlock(bot)) {
                BlockPos adjacentLiquid = findAdjacentSourceLiquid(world, target, blacklistTracker, now);
                if (adjacentLiquid != null && bot.getDistanceSqToCenter(adjacentLiquid) <= JobBlockSupport.INTERACT_REACH_SQ) {
                    if (ctx.getMovement().sealLiquidAt(world, adjacentLiquid)) {
                        placedSupportBlocks.add(adjacentLiquid.toLong());
                        return;
                    }
                }
            }
            BlockPos sightBlocker = JobBlockSupport.findBlockingBlockOnSight(bot, world, target);
            if (sightBlocker != null && bot.getDistanceSqToCenter(sightBlocker) <= JobBlockSupport.INTERACT_REACH_SQ) {
                long blockerPacked = sightBlocker.toLong();
                if (isInsideBounds(bounds, sightBlocker)
                        && !placedSupportBlocks.contains(blockerPacked)
                        && sightBlocker.getY() >= target.getY()
                        && isBreakableBlock(world, sightBlocker)
                        && JobBlockSupport.tryReserve(ctx, sightBlocker)) {
                    workQueue.pushPriority(PackedPos.pack(target.getX(), target.getY(), target.getZ()));
                    JobBlockSupport.release(ctx, target);
                    cachedTarget = sightBlocker;
                    target = sightBlocker;
                } else if (JobBlockSupport.isLeafBlock(world, sightBlocker)) {
                    ctx.stopNavigation();
                    JobBlockSupport.breakBlockWithBestTool(ctx, sightBlocker);
                    return;
                } else if (bot.getDistanceSqToCenter(target) <= JobBlockSupport.INTERACT_REACH_SQ) {
                    blacklistTarget(target, now);
                    JobBlockSupport.release(ctx, target);
                    cachedTarget = null;
                    return;
                }
            }
            if (!JobBlockSupport.isWithinReach(bot, target)) {
                BlockPos stand = JobBlockSupport.findNearestStandingSpot(world, target);
                if (stand == null && ctx.getTaskConfig().isAllowScaffolding()) {
                    stand = JobBlockSupport.findScaffoldApproachSpot(world, bot, target);
                }
                if (stand == null
                        || ctx.isGoalOnCooldown(stand, now)
                        || (bot.getDistanceSqToCenter(stand) <= 1.2D && !ctx.getMovement().hasActivePath())) {
                    blacklistTarget(target, now);
                    JobBlockSupport.release(ctx, target);
                    cachedTarget = null;
                    return;
                }
                ctx.requestPathTo(stand, false);
                return;
            }
            ctx.stopNavigation();
            if (JobBlockSupport.breakBlockWithBestTool(ctx, target)) {
                placedSupportBlocks.remove(target.toLong());
                lastProgressTick = now;
                if (target.getY() + 1 <= bounds.maxY()) {
                    BlockPos above = target.up();
                    if (world.isBlockLoaded(above) && isBreakableBlock(world, above)) {
                        workQueue.pushPriority(PackedPos.pack(above.getX(), above.getY(), above.getZ()));
                    }
                }
                cachedTarget = null;
            }
            return;
        }

        if (!hasAnyBreakableInBounds(ctx, bounds)) {
            TaskCompletionService.completeTask(ctx, "Excavation");
        } else {
            reanchorStreamAtLayer(bounds, bounds.maxY(), bot);
            bot.getChunkLoader().setExtraWorkChunk(null);
        }
    }

    private void syncPlanBounds(JobBlockSupport.AreaBounds bounds) {
        if (!hasPlanBounds
                || planMinX != bounds.minX()
                || planMinY != bounds.minY()
                || planMinZ != bounds.minZ()
                || planMaxX != bounds.maxX()
                || planMaxY != bounds.maxY()
                || planMaxZ != bounds.maxZ()) {
            this.planMinX = bounds.minX();
            this.planMinY = bounds.minY();
            this.planMinZ = bounds.minZ();
            this.planMaxX = bounds.maxX();
            this.planMaxY = bounds.maxY();
            this.planMaxZ = bounds.maxZ();
            this.hasPlanBounds = true;
            this.workQueue.clear();
            this.streamY = bounds.maxY();
            this.streamZ = bounds.minZ();
            this.streamX = bounds.minX();
            this.streamReverseX = false;
            this.streamExhausted = false;
        }
    }

    private void reanchorStreamAtLayer(JobBlockSupport.AreaBounds bounds, int startY, EntityAutomaton bot) {
        workQueue.clear();
        streamY = Math.max(bounds.minY(), Math.min(bounds.maxY(), startY));
        boolean nearMaxZ = Math.abs(bot.posZ - bounds.maxZ()) < Math.abs(bot.posZ - bounds.minZ());
        streamZ = nearMaxZ ? bounds.maxZ() : bounds.minZ();
        boolean nearMaxX = Math.abs(bot.posX - bounds.maxX()) < Math.abs(bot.posX - bounds.minX());
        streamReverseX = nearMaxX;
        streamX = streamReverseX ? bounds.maxX() : bounds.minX();
        streamExhausted = false;
    }

    private void blacklistTarget(BlockPos pos, long now) {
        blacklistTracker.blacklist(pos, now);
    }

    @Nullable
    private BlockPos resolveOrFindExcavationBlock(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds, long now) {
        if (cachedTarget != null && isExcavatableValid(ctx, bounds, cachedTarget, now)) {
            return cachedTarget;
        }
        if (cachedTarget != null) {
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }
        if (workQueue.needsRefill(QUEUE_LOW_WATERMARK) && !streamExhausted) {
            refillQueueTopDownSerpentine(ctx, bounds, now);
        }
        WorldServer world = ctx.getWorld();
        EntityAutomaton bot = ctx.getAutomaton();
        boolean allowScaffold = ctx.getTaskConfig().isAllowScaffolding();
        long nextPacked = workQueue.pollNextValid(packed -> {
            int x = PackedPos.unpackX(packed);
            int y = PackedPos.unpackY(packed);
            int z = PackedPos.unpackZ(packed);
            scratchPos.setPos(x, y, z);
            if (x < bounds.minX() || x > bounds.maxX()
                    || y < bounds.minY() || y > bounds.maxY()
                    || z < bounds.minZ() || z > bounds.maxZ()) {
                return false;
            }
            if (blacklistTracker.isBlacklisted(scratchPos, now) || !isBreakableBlock(world, scratchPos)) {
                return false;
            }
            if (!isTargetAccessible(world, bot, scratchPos, allowScaffold)) {
                return false;
            }
            if (isSupportBlockProtected(bot.getPosition(), scratchPos, placedSupportBlocks)
                    && hasNonSupportAboveOrInLayer(world, bounds, y, placedSupportBlocks)) {
                return false;
            }
            return !ctx.getBlackboard().isReservedByOther(bot.getEntityId(), scratchPos, now)
                    && JobBlockSupport.tryReserve(ctx, scratchPos.toImmutable());
        });
        if (nextPacked != Long.MIN_VALUE) {
            cachedTarget = new BlockPos(
                    PackedPos.unpackX(nextPacked),
                    PackedPos.unpackY(nextPacked),
                    PackedPos.unpackZ(nextPacked));
            return cachedTarget;
        }
        return null;
    }

    private void refillQueueTopDownSerpentine(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds, long now) {
        WorldServer world = ctx.getWorld();
        EntityAutomaton bot = ctx.getAutomaton();
        boolean allowScaffold = ctx.getTaskConfig().isAllowScaffolding();
        int botId = bot.getEntityId();
        while (!workQueue.isFull() && streamY >= bounds.minY()) {
            int addedInLayer = 0;
            while (streamZ >= bounds.minZ() && streamZ <= bounds.maxZ() && !workQueue.isFull()) {
                while (streamX >= bounds.minX() && streamX <= bounds.maxX() && !workQueue.isFull()) {
                    scratchPos.setPos(streamX, streamY, streamZ);
                    if (world.isBlockLoaded(scratchPos)
                            && !blacklistTracker.isBlacklisted(scratchPos, now)
                            && isBreakableBlock(world, scratchPos)
                            && isTargetAccessible(world, bot, scratchPos, allowScaffold)
                            && !ctx.getBlackboard().isReservedByOther(botId, scratchPos, now)) {
                        long packed = PackedPos.pack(streamX, streamY, streamZ);
                        if (!workQueue.contains(packed)) {
                            workQueue.append(packed);
                            addedInLayer++;
                        }
                    }
                    streamX += streamReverseX ? -1 : 1;
                }
                if (streamX < bounds.minX() || streamX > bounds.maxX()) {
                    streamZ++;
                    streamReverseX = !streamReverseX;
                    streamX = streamReverseX ? bounds.maxX() : bounds.minX();
                }
            }
            if (streamZ > bounds.maxZ()) {
                streamY--;
                streamZ = bounds.minZ();
                streamReverseX = false;
                streamX = bounds.minX();
                if (addedInLayer > 0 || !workQueue.isEmpty()) {
                    break;
                }
            }
        }
        if (streamY < bounds.minY()) {
            streamExhausted = true;
        }
    }

    static boolean shouldRebuildOnStagnation(long now, long lastProgressTick) {
        return lastProgressTick != Long.MIN_VALUE && (now - lastProgressTick) >= STAGNATION_REBUILD_TICKS;
    }

    private int findHighestAccessibleLayer(WorldServer world, JobBlockSupport.AreaBounds bounds, long now) {
        for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    scratchPos.setPos(x, y, z);
                    if (!blacklistTracker.isBlacklisted(scratchPos, now) && isBreakableBlock(world, scratchPos)) {
                        return y;
                    }
                }
            }
        }
        return bounds.maxY();
    }

    private static boolean isTargetAccessible(
            WorldServer world,
            EntityAutomaton bot,
            BlockPos pos,
            boolean allowScaffolding) {
        if (JobBlockSupport.isWithinReach(bot, pos)) {
            return true;
        }
        if (JobBlockSupport.findNearestStandingSpot(world, pos) != null) {
            return true;
        }
        return allowScaffolding && JobBlockSupport.findScaffoldApproachSpot(world, bot, pos) != null;
    }

    private boolean isExcavatableValid(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds, BlockPos pos, long now) {
        if (pos.getX() < bounds.minX() || pos.getX() > bounds.maxX()
                || pos.getY() < bounds.minY() || pos.getY() > bounds.maxY()
                || pos.getZ() < bounds.minZ() || pos.getZ() > bounds.maxZ()) {
            return false;
        }
        if (blacklistTracker.isBlacklisted(pos, now)) {
            return false;
        }
        WorldServer world = ctx.getWorld();
        if (!isBreakableBlock(world, pos)) {
            return false;
        }
        if (!com.x4yi.automata.compat.ClaimProtectionCompat.canModifyBlock(world, ctx.getAutomaton(), pos)) {
            blacklistTracker.blacklist(pos, ctx.getWorldTime());
            return false;
        }
        return !ctx.getBlackboard().isReservedByOther(ctx.getAutomaton().getEntityId(), pos, now)
                && JobBlockSupport.tryReserve(ctx, pos);
    }

    private static boolean isInsideBounds(JobBlockSupport.AreaBounds bounds, BlockPos pos) {
        return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }

    private static boolean isBreakableBlock(WorldServer world, BlockPos pos) {
        if (!world.isBlockLoaded(pos) || world.isAirBlock(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getMaterial().isLiquid()) {
            return false;
        }
        if (JobBlockSupport.isBlockBlacklisted(state)) {
            return false;
        }
        return state.getBlockHardness(world, pos) >= 0.0F;
    }

    private static boolean hasAnyBreakableInBounds(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds) {
        WorldServer world = ctx.getWorld();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.setPos(x, y, z);
                    if (isBreakableBlock(world, cursor)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean isSupportBlockProtected(BlockPos botPos, BlockPos candidate, LongOpenHashSet placedSupports) {
        return placedSupports.contains(candidate.toLong())
                || (candidate.getX() == botPos.getX()
                        && candidate.getZ() == botPos.getZ()
                        && candidate.getY() == botPos.getY() - 1
                        && placedSupports.contains(candidate.toLong()));
    }

    private static boolean hasNonSupportAboveOrInLayer(
            WorldServer world,
            JobBlockSupport.AreaBounds bounds,
            int layerY,
            LongOpenHashSet placedSupports) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = bounds.maxY(); y >= layerY; y--) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.setPos(x, y, z);
                    if (!placedSupports.contains(cursor.toLong()) && isBreakableBlock(world, cursor)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void pruneClearedSupportBlocks(WorldServer world) {
        LongIterator it = placedSupportBlocks.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            BlockPos pos = BlockPos.fromLong(packed);
            if (!world.isBlockLoaded(pos) || world.isAirBlock(pos)) {
                it.remove();
            }
        }
    }

    static boolean isSourceLiquid(IBlockState state) {
        if (!state.getMaterial().isLiquid()) {
            return false;
        }
        return state.getPropertyKeys().contains(BlockLiquid.LEVEL) && state.getValue(BlockLiquid.LEVEL) == 0;
    }

    private static boolean hasBuildingBlock(EntityAutomaton bot) {
        net.minecraftforge.items.IItemHandlerModifiable inv = bot.getInventory().getMainInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (BlockPlacementService.isValidScaffoldBlock(inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos findAdjacentSourceLiquid(
            WorldServer world,
            BlockPos center,
            AreaBlacklistTracker blacklist,
            long now) {
        for (EnumFacing facing : EnumFacing.VALUES) {
            BlockPos adj = center.offset(facing);
            if (!blacklist.isBlacklisted(adj, now) && world.isBlockLoaded(adj) && isSourceLiquid(world.getBlockState(adj))) {
                return adj;
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findSealableSourceLiquid(
            EntityAutomaton bot,
            WorldServer world,
            JobBlockSupport.AreaBounds bounds,
            AreaBlacklistTracker blacklist,
            long now) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.setPos(x, y, z);
                    if (blacklist.isBlacklisted(cursor, now)
                            || !world.isBlockLoaded(cursor)
                            || !isSourceLiquid(world.getBlockState(cursor))) {
                        continue;
                    }
                    double d = bot.getDistanceSqToCenter(cursor);
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = cursor.toImmutable();
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }
}
