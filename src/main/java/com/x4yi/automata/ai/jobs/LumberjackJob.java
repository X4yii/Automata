package com.x4yi.automata.ai.jobs;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.ai.TaskCompletionService;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.PackedPos;
import com.x4yi.automata.world.FakePlayerService;

public class LumberjackJob implements IAutomatonJob {

    private static final int[] QUAD_DX = {0, 1, 1, 0};
    private static final int[] QUAD_DZ = {0, 0, 1, 1};
    private static final long UNREACHABLE_TTL_TICKS = 80L;
    private static final int MAX_PILLAR_ATTEMPTS = 3;
    private static final long STAGNATION_REBUILD_TICKS = 40L;
    private static final double STAGNATION_RADIUS_SQ = 9.0D;
    private static final Map<Block, Boolean> LOG_BLOCK_CACHE = new ConcurrentHashMap<>();

    private final AreaBlacklistTracker blacklistTracker = new AreaBlacklistTracker(UNREACHABLE_TTL_TICKS);
    private final BlockWorkQueue treeQueue = new BlockWorkQueue(BlockWorkQueue.DEFAULT_CAPACITY);
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    @Nullable
    private BlockPos cachedTarget;
    private int activeTreeX = Integer.MIN_VALUE;
    private int activeTreeZ = Integer.MIN_VALUE;
    private long pillarTargetKey = Long.MIN_VALUE;
    private int pillarAttempts;
    private long nextFullScanTick = Long.MIN_VALUE;
    private boolean lastScanSawUnblockedLog;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private long lastProgressTick = Long.MIN_VALUE;

    @Override
    public ResourceLocation getJobId() {
        return AutomatonBuiltInJobs.LUMBERJACK;
    }

    @Override
    public boolean hasCommittedWork(IAutomatonContext ctx) {
        return cachedTarget != null || !treeQueue.isEmpty();
    }

    @Override
    public int copyPlannedBreakTargets(long[] dest, int maxCount) {
        int written = 0;
        int limit = Math.min(maxCount, dest.length);
        if (cachedTarget != null && written < limit) {
            dest[written++] = PackedPos.pack(cachedTarget.getX(), cachedTarget.getY(), cachedTarget.getZ());
        }
        if (written < limit) {
            written += treeQueue.copyTo(dest, written, limit - written);
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
        treeQueue.clear();
        activeTreeX = Integer.MIN_VALUE;
        activeTreeZ = Integer.MIN_VALUE;
        pillarTargetKey = Long.MIN_VALUE;
        pillarAttempts = 0;
        nextFullScanTick = Long.MIN_VALUE;
        lastScanSawUnblockedLog = false;
        lastProgressTick = Long.MIN_VALUE;
    }

    @Override
    public void tick(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
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
        if ((now - lastProgressTick) >= STAGNATION_REBUILD_TICKS) {
            if (cachedTarget != null) {
                blacklistTarget(cachedTarget, now);
                JobBlockSupport.release(ctx, cachedTarget);
                cachedTarget = null;
            }
            treeQueue.clear();
            nextFullScanTick = now;
            anchorX = bot.posX;
            anchorY = bot.posY;
            anchorZ = bot.posZ;
            lastProgressTick = now;
        }
        if (cachedTarget != null && ctx.getMovement().getLastStuckGoal() != Long.MIN_VALUE) {
            ctx.getMovement().clearLastStuckGoal();
            blacklistTarget(cachedTarget, now);
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }

        JobBlockSupport.AreaBounds bounds = JobBlockSupport.resolveBounds(ctx.getTaskConfig(), bot.getPosition(), 8);
        BlockPos logPos = resolveOrFindLog(ctx, bounds, now);
        if (logPos == null) {
            if (now >= nextFullScanTick && !lastScanSawUnblockedLog && !hasAnyUnblockedLog(world, bounds, now)) {
                TaskCompletionService.completeTask(ctx, "Lumberjack");
            }
            return;
        }

        bot.getChunkLoader().setExtraWorkChunk(logPos);
        BlockPos sightBlocker = JobBlockSupport.findBlockingBlockOnSight(bot, world, logPos);
        if (sightBlocker != null && bot.getDistanceSqToCenter(sightBlocker) <= JobBlockSupport.INTERACT_REACH_SQ) {
            if (JobBlockSupport.isLeafBlock(world, sightBlocker)) {
                ctx.stopNavigation();
                JobBlockSupport.breakBlockWithBestTool(ctx, sightBlocker);
                return;
            }
            if (isLogBlock(world.getBlockState(sightBlocker))
                    && isInsideBounds(bounds, sightBlocker)
                    && JobBlockSupport.tryReserve(ctx, sightBlocker)) {
                JobBlockSupport.release(ctx, logPos);
                cachedTarget = sightBlocker;
                logPos = sightBlocker;
            }
        }

        if (!JobBlockSupport.isWithinReach(bot, logPos)) {
            BlockPos nearbyLeaf = findNearbyObstructingLeaf(world, bot);
            if (nearbyLeaf != null) {
                ctx.stopNavigation();
                JobBlockSupport.breakBlockWithBestTool(ctx, nearbyLeaf);
                return;
            }
            if (logPos.getY() > bot.posY + 4.0D
                    && Math.abs(bot.posX - (logPos.getX() + 0.5D)) <= 2.5D
                    && Math.abs(bot.posZ - (logPos.getZ() + 0.5D)) <= 2.5D) {
                if (pillarTargetKey != logPos.toLong()) {
                    pillarTargetKey = logPos.toLong();
                    pillarAttempts = 0;
                }
                if (pillarAttempts < MAX_PILLAR_ATTEMPTS && tryPillarUpWithLog(ctx, bot, world)) {
                    pillarAttempts++;
                    return;
                }
                blacklistTarget(logPos, now);
                JobBlockSupport.release(ctx, logPos);
                cachedTarget = null;
                pillarAttempts = 0;
                return;
            }
            BlockPos stand = JobBlockSupport.findNearestStandingSpot(world, logPos);
            if (stand == null
                    || ctx.isGoalOnCooldown(stand, now)
                    || (bot.getDistanceSqToCenter(stand) <= 2.25D && !ctx.getMovement().hasActivePath())) {
                blacklistTarget(logPos, now);
                JobBlockSupport.release(ctx, logPos);
                cachedTarget = null;
                return;
            }
            ctx.requestPathTo(stand, false);
            return;
        }

        pillarTargetKey = Long.MIN_VALUE;
        pillarAttempts = 0;
        ctx.stopNavigation();
        boolean hadSoilBelow = world.getBlockState(logPos.down()).getMaterial().isSolid()
                && !isLogBlock(world.getBlockState(logPos.down()));
        if (JobBlockSupport.breakBlockWithBestTool(ctx, logPos)) {
            cachedTarget = null;
            lastProgressTick = now;
            if (hadSoilBelow && world.isAirBlock(logPos)) {
                JobBlockSupport.useMatchingInventoryItemOnFace(
                        ctx, logPos.down(), EnumFacing.UP, stack -> JobBlockSupport.matchesOreDict(stack, "treeSapling"));
            }
        }
    }

    private void blacklistTarget(BlockPos pos, long now) {
        blacklistTracker.blacklist(pos, now);
    }

    private boolean isBlacklisted(BlockPos pos, long now) {
        return blacklistTracker.isBlacklisted(pos, now);
    }

    private boolean hasAnyUnblockedLog(WorldServer world, JobBlockSupport.AreaBounds bounds, long now) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.setPos(x, y, z);
                    if (!isBlacklisted(cursor, now) && world.isBlockLoaded(cursor) && isLogBlock(world.getBlockState(cursor))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    private BlockPos resolveOrFindLog(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds, long now) {
        if (cachedTarget != null && !isBlacklisted(cachedTarget, now) && isLogTargetValid(ctx, bounds, cachedTarget)) {
            return cachedTarget;
        }
        if (cachedTarget != null) {
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }
        long queued = treeQueue.pollNextValid(packed -> {
            scratchPos.setPos(PackedPos.unpackX(packed), PackedPos.unpackY(packed), PackedPos.unpackZ(packed));
            return !isBlacklisted(scratchPos, now) && isLogTargetValid(ctx, bounds, scratchPos);
        });
        if (queued != Long.MIN_VALUE) {
            cachedTarget = new BlockPos(
                    PackedPos.unpackX(queued),
                    PackedPos.unpackY(queued),
                    PackedPos.unpackZ(queued));
            return cachedTarget;
        }
        if (now < nextFullScanTick) {
            return null;
        }
        BlockPos found = findBestLogTarget(ctx, bounds, now);
        if (found == null) {
            nextFullScanTick = now + 20L;
        } else {
            enqueueConnectedTreeLogs(ctx, bounds, found, now);
        }
        cachedTarget = found;
        return found;
    }

    private void enqueueConnectedTreeLogs(
            AutomatonAIContext ctx,
            JobBlockSupport.AreaBounds bounds,
            BlockPos root,
            long now) {
        treeQueue.clear();
        WorldServer world = ctx.getWorld();
        LongOpenHashSet visited = new LongOpenHashSet();
        long rootPacked = PackedPos.pack(root.getX(), root.getY(), root.getZ());
        visited.add(rootPacked);
        long[] bfs = new long[128];
        int head = 0;
        int tail = 0;
        bfs[tail++] = rootPacked;
        while (head < tail && !treeQueue.isFull()) {
            long cur = bfs[head++];
            int cx = PackedPos.unpackX(cur);
            int cy = PackedPos.unpackY(cur);
            int cz = PackedPos.unpackZ(cur);
            for (int dy = -1; dy <= 1; dy++) {
                int ny = cy + dy;
                if (ny < bounds.minY() || ny > bounds.maxY()) {
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx;
                    if (nx < bounds.minX() || nx > bounds.maxX()) {
                        continue;
                    }
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        int nz = cz + dz;
                        if (nz < bounds.minZ() || nz > bounds.maxZ()) {
                            continue;
                        }
                        long nPacked = PackedPos.pack(nx, ny, nz);
                        if (!visited.add(nPacked)) {
                            continue;
                        }
                        scratchPos.setPos(nx, ny, nz);
                        if (isBlacklisted(scratchPos, now)
                                || !world.isBlockLoaded(scratchPos)
                                || !isLogBlock(world.getBlockState(scratchPos))) {
                            continue;
                        }
                        treeQueue.append(nPacked);
                        if (tail < bfs.length) {
                            bfs[tail++] = nPacked;
                        }
                    }
                }
            }
        }
    }

    private static boolean isLogTargetValid(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds, BlockPos pos) {
        if (pos.getX() < bounds.minX() || pos.getX() > bounds.maxX()
                || pos.getY() < bounds.minY() || pos.getY() > bounds.maxY()
                || pos.getZ() < bounds.minZ() || pos.getZ() > bounds.maxZ()) {
            return false;
        }
        WorldServer world = ctx.getWorld();
        if (!world.isBlockLoaded(pos) || world.isAirBlock(pos)) {
            return false;
        }
        if (!isLogBlock(world.getBlockState(pos))) {
            return false;
        }
        if (!com.x4yi.automata.compat.ClaimProtectionCompat.canModifyBlock(world, ctx.getAutomaton(), pos)) {
            return false;
        }
        return !ctx.getBlackboard().isReservedByOther(ctx.getAutomaton().getEntityId(), pos, ctx.getWorldTime())
                && JobBlockSupport.tryReserve(ctx, pos);
    }

    @Nullable
    private BlockPos findBestLogTarget(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds, long now) {
        EntityAutomaton bot = ctx.getAutomaton();
        WorldServer world = ctx.getWorld();

        BlockPos lowest = findLowestLog(ctx, world, bounds, bot, now);
        if (lowest == null) {
            activeTreeX = Integer.MIN_VALUE;
            activeTreeZ = Integer.MIN_VALUE;
            return null;
        }
        activeTreeX = lowest.getX();
        activeTreeZ = lowest.getZ();

        BlockPos nw2x2 = detect2x2Base(world, lowest);
        if (nw2x2 != null) {
            BlockPos spiralTarget = select2x2SpiralOrTopDownTarget(ctx, world, bounds, nw2x2, bot);
            if (spiralTarget != null && !isBlacklisted(spiralTarget, now) && JobBlockSupport.tryReserve(ctx, spiralTarget)) {
                return spiralTarget;
            }
        }

        BlockPos upperAboveBase = findHighestReachableInColumnOrCluster(ctx, world, bounds, lowest, bot);
        if (upperAboveBase != null && !isBlacklisted(upperAboveBase, now) && JobBlockSupport.tryReserve(ctx, upperAboveBase)) {
            return upperAboveBase;
        }

        return JobBlockSupport.tryReserve(ctx, lowest) ? lowest : null;
    }

    @Nullable
    private BlockPos findLowestLog(
            AutomatonAIContext ctx,
            WorldServer world,
            JobBlockSupport.AreaBounds bounds,
            EntityAutomaton bot,
            long now) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        lastScanSawUnblockedLog = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.setPos(x, y, z);
                    if (isBlacklisted(cursor, now) || !world.isBlockLoaded(cursor) || world.isAirBlock(cursor)) {
                        continue;
                    }
                    if (!isLogBlock(world.getBlockState(cursor))) {
                        continue;
                    }
                    lastScanSawUnblockedLog = true;
                    double score = y * 4.0D + Math.sqrt(bot.getDistanceSqToCenter(cursor));
                    if (activeTreeX != Integer.MIN_VALUE
                            && Math.abs(x - activeTreeX) <= 2
                            && Math.abs(z - activeTreeZ) <= 2) {
                        score -= 60.0D;
                    }
                    if (score < bestScore && !ctx.getBlackboard().isReservedByOther(bot.getEntityId(), cursor, now)) {
                        bestScore = score;
                        best = cursor.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    @Nullable
    private static BlockPos detect2x2Base(WorldServer world, BlockPos sample) {
        int y = sample.getY();
        int[] offsets = {0, -1};
        for (int ox : offsets) {
            for (int oz : offsets) {
                int nx = sample.getX() + ox;
                int nz = sample.getZ() + oz;
                if (is2x2LayerSolidLogs(world, nx, y, nz) && is2x2LayerSolidLogs(world, nx, y + 1, nz)) {
                    return new BlockPos(nx, y, nz);
                }
            }
        }
        return null;
    }

    private static boolean is2x2LayerSolidLogs(WorldServer world, int nwX, int y, int nwZ) {
        for (int q = 0; q < 4; q++) {
            BlockPos p = new BlockPos(nwX + QUAD_DX[q], y, nwZ + QUAD_DZ[q]);
            if (!world.isBlockLoaded(p) || !isLogBlock(world.getBlockState(p))) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static BlockPos select2x2SpiralOrTopDownTarget(
            AutomatonAIContext ctx,
            WorldServer world,
            JobBlockSupport.AreaBounds bounds,
            BlockPos nwBase,
            EntityAutomaton bot) {
        int baseY = nwBase.getY();
        int topY = baseY;
        for (int y = Math.min(bounds.maxY(), baseY + 36); y >= baseY; y--) {
            for (int q = 0; q < 4; q++) {
                BlockPos p = new BlockPos(nwBase.getX() + QUAD_DX[q], y, nwBase.getZ() + QUAD_DZ[q]);
                if (world.isBlockLoaded(p) && isLogBlock(world.getBlockState(p))) {
                    topY = y;
                    break;
                }
            }
            if (topY > baseY) {
                break;
            }
        }

        boolean hasLogsAboveReach = topY > bot.posY + 4.0D;
        if (!hasLogsAboveReach) {
            for (int y = topY; y >= baseY; y--) {
                for (int q = 0; q < 4; q++) {
                    BlockPos p = new BlockPos(nwBase.getX() + QUAD_DX[q], y, nwBase.getZ() + QUAD_DZ[q]);
                    if (world.isBlockLoaded(p) && isLogBlock(world.getBlockState(p))
                            && !ctx.getBlackboard().isReservedByOther(bot.getEntityId(), p, ctx.getWorldTime())) {
                        return p;
                    }
                }
            }
            return null;
        }

        int maxClimbY = Math.min(topY, (int) Math.floor(bot.posY) + 4);
        for (int y = baseY; y <= maxClimbY; y++) {
            int stepQ = spiralQuadrantForOffset(y - baseY);
            int nextQ = spiralQuadrantForOffset(y - baseY + 1);
            for (int q = 0; q < 4; q++) {
                if (q == stepQ) {
                    continue;
                }
                if (q == nextQ || world.isBlockLoaded(new BlockPos(nwBase.getX() + QUAD_DX[q], y, nwBase.getZ() + QUAD_DZ[q]))) {
                    BlockPos candidate = new BlockPos(nwBase.getX() + QUAD_DX[q], y, nwBase.getZ() + QUAD_DZ[q]);
                    if (isLogBlock(world.getBlockState(candidate))
                            && !ctx.getBlackboard().isReservedByOther(bot.getEntityId(), candidate, ctx.getWorldTime())) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    public static int spiralQuadrantForOffset(int yOffset) {
        return Math.floorMod(yOffset, 4);
    }

    @Nullable
    private static BlockPos findHighestReachableInColumnOrCluster(
            AutomatonAIContext ctx,
            WorldServer world,
            JobBlockSupport.AreaBounds bounds,
            BlockPos base,
            EntityAutomaton bot) {
        int maxReachY = Math.min(bounds.maxY(), (int) Math.floor(bot.posY) + 4);
        boolean hasAboveBase = false;
        for (int y = Math.min(bounds.maxY(), base.getY() + 32); y > base.getY(); y--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
                    if (world.isBlockLoaded(p) && isLogBlock(world.getBlockState(p))) {
                        hasAboveBase = true;
                        if (y <= maxReachY
                                && !ctx.getBlackboard().isReservedByOther(bot.getEntityId(), p, ctx.getWorldTime())) {
                            return p;
                        }
                    }
                }
            }
        }
        return hasAboveBase ? null : base;
    }

    private static boolean hasHighLogsUnreachable(
            WorldServer world,
            JobBlockSupport.AreaBounds bounds,
            EntityAutomaton bot) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = bounds.maxY(); y > (int) Math.floor(bot.posY) + 4; y--) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.setPos(x, y, z);
                    if (world.isBlockLoaded(cursor) && isLogBlock(world.getBlockState(cursor))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isStandingOnLog(WorldServer world, EntityAutomaton bot) {
        BlockPos below = new BlockPos(bot.posX, bot.posY - 0.5D, bot.posZ);
        return world.isBlockLoaded(below) && isLogBlock(world.getBlockState(below));
    }

    private static boolean tryPillarUpWithLog(AutomatonAIContext ctx, EntityAutomaton bot, WorldServer world) {
        BlockPos feet = new BlockPos(bot.posX, bot.posY, bot.posZ);
        if (!world.isAirBlock(feet.up(2))) {
            return false;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (candidate.isEmpty() || !(candidate.getItem() instanceof ItemBlock)) {
                continue;
            }
            if (!JobBlockSupport.matchesOreDict(candidate, "logWood")) {
                continue;
            }
            if (bot.onGround) {
                bot.getJumpHelper().setJumping();
                return true;
            }
            if (bot.posY >= feet.getY() + 1.01D && world.isAirBlock(feet) && world.getBlockState(feet.down()).getMaterial().isSolid()) {
                ItemStack prev = bot.getHeldItemMainhand().copy();
                ItemStack logStack = inv.extractItem(i, 1, false);
                bot.setHeldItem(EnumHand.MAIN_HAND, logStack);
                ctx.getMovement().lookAtBlockFace(feet.down(), EnumFacing.UP);
                bot.swingArm(EnumHand.MAIN_HAND);
                EnumActionResult res = FakePlayerService.tryUseItemOnBlock(
                        world, bot, EnumHand.MAIN_HAND, feet.down(), EnumFacing.UP, 0.5F, 1.0F, 0.5F);
                ItemStack rem = bot.getHeldItemMainhand().copy();
                bot.setHeldItem(EnumHand.MAIN_HAND, prev);
                if (!rem.isEmpty()) {
                    inv.insertItem(i, rem, false);
                }
                return res == EnumActionResult.SUCCESS;
            }
        }
        return false;
    }

    private static boolean isInsideBounds(JobBlockSupport.AreaBounds bounds, BlockPos pos) {
        return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }

    @Nullable
    private static BlockPos findNearbyObstructingLeaf(WorldServer world, EntityAutomaton bot) {
        BlockPos feet = new BlockPos(bot.posX, bot.posY, bot.posZ);
        for (int dy = 1; dy >= 0; dy--) {
            for (EnumFacing face : EnumFacing.HORIZONTALS) {
                BlockPos check = feet.up(dy).offset(face);
                if (JobBlockSupport.isLeafBlock(world, check)) {
                    return check;
                }
            }
        }
        return null;
    }

    private static boolean isLogBlock(IBlockState state) {
        if (JobBlockSupport.isBlockBlacklisted(state)) {
            return false;
        }
        Block block = state.getBlock();
        Boolean cached = LOG_BLOCK_CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        ItemStack stack = new ItemStack(block, 1, block.getMetaFromState(state));
        boolean isLog = JobBlockSupport.matchesOreDict(stack, "logWood");
        if (LOG_BLOCK_CACHE.size() < 1024) {
            LOG_BLOCK_CACHE.putIfAbsent(block, isLog);
        }
        return isLog;
    }
}
