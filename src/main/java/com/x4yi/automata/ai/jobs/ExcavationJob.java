package com.x4yi.automata.ai.jobs;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.AutomatonJobType;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.ai.TaskCompletionService;
import com.x4yi.automata.entity.EntityAutomaton;

public class ExcavationJob implements IAutomatonJob {

    private static final long UNREACHABLE_TTL_TICKS = 80L;

    private final AreaBlacklistTracker blacklistTracker = new AreaBlacklistTracker(UNREACHABLE_TTL_TICKS);

    @Nullable
    private BlockPos cachedTarget;

    @Override
    public AutomatonJobType getType() {
        return AutomatonJobType.EXCAVATION;
    }

    @Override
    public void onStop(AutomatonAIContext ctx) {
        if (cachedTarget != null) {
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }
        blacklistTracker.clear();
    }

    @Override
    public void tick(AutomatonAIContext ctx) {
        if (ctx.getTaskConfig().getPos1() == null || ctx.getTaskConfig().getPos2() == null) {
            return;
        }
        EntityAutomaton bot = ctx.getAutomaton();
        WorldServer world = ctx.getWorld();
        long now = ctx.getWorldTime();
        if (cachedTarget != null && ctx.getMovement().getLastStuckGoal() != Long.MIN_VALUE) {
            ctx.getMovement().clearLastStuckGoal();
            blacklistTarget(cachedTarget, now);
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }

        JobBlockSupport.AreaBounds bounds = JobBlockSupport.resolveBounds(ctx.getTaskConfig(), bot.getPosition(), 4);

        BlockPos target = resolveOrFindExcavationBlock(ctx, bounds, now);
        if (target != null) {
            bot.getChunkLoader().setExtraWorkChunk(target);
            BlockPos sightBlocker = JobBlockSupport.findBlockingBlockOnSight(bot, world, target);
            if (sightBlocker != null && bot.getDistanceSqToCenter(sightBlocker) <= JobBlockSupport.INTERACT_REACH_SQ) {
                if (isInsideBounds(bounds, sightBlocker)
                        && sightBlocker.getY() >= target.getY()
                        && isBreakableBlock(world, sightBlocker)
                        && JobBlockSupport.tryReserve(ctx, sightBlocker)) {
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
                if (stand == null
                        || ctx.isGoalOnCooldown(stand, now)
                        || (bot.getDistanceSqToCenter(stand) <= 2.25D && !ctx.getMovement().hasActivePath())) {
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
                cachedTarget = null;
            }
            return;
        }

        if (!hasAnyBreakableInBounds(ctx, bounds)) {
            TaskCompletionService.completeTask(ctx, "Excavation");
        } else {
            bot.getChunkLoader().setExtraWorkChunk(null);
        }
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
        BlockPos found = findBestExcavatableBlock(ctx, bounds, now);
        cachedTarget = found;
        return found;
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

    @Nullable
    private BlockPos findBestExcavatableBlock(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds, long now) {
        EntityAutomaton bot = ctx.getAutomaton();
        WorldServer world = ctx.getWorld();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.setPos(x, y, z);
                    if (blacklistTracker.isBlacklisted(cursor, now) || !isBreakableBlock(world, cursor)) {
                        continue;
                    }
                    if (ctx.getBlackboard().isReservedByOther(bot.getEntityId(), cursor, now)) {
                        continue;
                    }
                    double score = bot.getDistanceSqToCenter(cursor);
                    if (score < bestScore) {
                        bestScore = score;
                        best = cursor.toImmutable();
                    }
                }
            }
            if (best != null) {
                break;
            }
        }
        if (best != null && JobBlockSupport.tryReserve(ctx, best)) {
            return best;
        }
        return null;
    }
}

