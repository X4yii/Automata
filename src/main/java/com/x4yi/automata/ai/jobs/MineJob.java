package com.x4yi.automata.ai.jobs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;

public class MineJob implements IAutomatonJob {

    public static final String REJECT_ZONE_MESSAGE = "Hey, no me limites el terreno, no encontraré linduras como tu";

    private static final long UNREACHABLE_TTL_TICKS = 80L;
    private static final int MIN_SCAN_RADIUS = 4;
    private static final int MAX_SCAN_RADIUS = 48;
    private static final int MAX_VEIN_BLOCKS = 64;
    private static final int MAX_VEIN_STALL_TICKS = 12;
    private static final double OWNER_FOLLOW_BEFORE_SCAN_SQ = 100.0D;
    private static final double OWNER_IDLE_FOLLOW_SQ = 25.0D;

    private final AreaBlacklistTracker blacklistTracker = new AreaBlacklistTracker(UNREACHABLE_TTL_TICKS);
    private final Deque<BlockPos> activeVeinQueue = new ArrayDeque<>();

    @Nullable
    private BlockPos cachedTarget;
    private boolean commandedVeinActive;
    private int veinStallTicks;
    private long nextFullScanTick = Long.MIN_VALUE;

    @Override
    public ResourceLocation getJobId() {
        return AutomatonBuiltInJobs.MINE;
    }

    @Override
    public boolean hasCommittedWork(IAutomatonContext ctx) {
        return hasActiveCommittedWork();
    }

    @Override
    public void onStop(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        if (cachedTarget != null) {
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }
        activeVeinQueue.clear();
        commandedVeinActive = false;
        veinStallTicks = 0;
        nextFullScanTick = Long.MIN_VALUE;
        blacklistTracker.clear();
    }

    public boolean hasActiveCommittedWork() {
        return commandedVeinActive || cachedTarget != null || !activeVeinQueue.isEmpty();
    }

    public int commandVein(WorldServer world, BlockPos origin) {
        List<BlockPos> vein = collectContiguousVein(world, origin, MAX_VEIN_BLOCKS);
        activeVeinQueue.clear();
        cachedTarget = null;
        veinStallTicks = 0;
        nextFullScanTick = Long.MIN_VALUE;
        if (vein.isEmpty()) {
            commandedVeinActive = false;
            return 0;
        }
        activeVeinQueue.addAll(vein);
        commandedVeinActive = true;
        return vein.size();
    }

    @Override
    public void tick(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        EntityAutomaton bot = ctx.getAutomaton();
        WorldServer world = ctx.getWorld();
        long now = ctx.getWorldTime();
        if (cachedTarget != null && ctx.getMovement().getLastStuckGoal() != Long.MIN_VALUE) {
            ctx.getMovement().clearLastStuckGoal();
            blacklistTracker.blacklist(cachedTarget, now);
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }

        EntityPlayer owner = resolveNearbyOwner(world, bot);
        boolean hasActiveWork = commandedVeinActive || cachedTarget != null || !activeVeinQueue.isEmpty();
        if (owner != null && shouldFollowOwnerFirst(true, bot.getDistanceSq(owner), hasActiveWork)) {
            bot.getChunkLoader().setExtraWorkChunk(null);
            ctx.requestPathTo(owner.getPosition(), false);
            return;
        }

        BlockPos scanCenter = owner != null ? owner.getPosition() : bot.getPosition();
        JobBlockSupport.AreaBounds bounds = resolveMineBounds(scanCenter, AutomataConfig.mineSearchRadius);

        BlockPos target = resolveOrFindExposedOre(ctx, bounds, now);
        if (target == null) {
            if (!activeVeinQueue.isEmpty() && shouldKeepVeinAliveOnStall(veinStallTicks)) {
                veinStallTicks++;
                return;
            }
            activeVeinQueue.clear();
            commandedVeinActive = false;
            veinStallTicks = 0;
            bot.getChunkLoader().setExtraWorkChunk(null);
            if (owner != null && bot.getDistanceSq(owner) > OWNER_IDLE_FOLLOW_SQ) {
                ctx.requestPathTo(owner.getPosition(), false);
            }
            return;
        }
        veinStallTicks = 0;

        bot.getChunkLoader().setExtraWorkChunk(target);
        if (!JobBlockSupport.isWithinReach(bot, target)) {
            BlockPos stand = JobBlockSupport.findNearestStandingSpot(world, target);
            if (stand == null && ctx.getTaskConfig().isAllowScaffolding()) {
                stand = JobBlockSupport.findScaffoldApproachSpot(world, bot, target);
            }
            if (stand == null
                    || ctx.isGoalOnCooldown(stand, now)
                    || (bot.getDistanceSqToCenter(stand) <= 2.25D && !ctx.getMovement().hasActivePath())) {
                blacklistTracker.blacklist(target, now);
                JobBlockSupport.release(ctx, target);
                cachedTarget = null;
                return;
            }
            ctx.requestPathTo(stand, true);
            return;
        }

        BlockPos sightBlocker = JobBlockSupport.findBlockingBlockOnSight(bot, world, target);
        if (sightBlocker != null) {
            if (ModdedOreCatalog.isOreBlock(world, sightBlocker, world.getBlockState(sightBlocker))) {
                JobBlockSupport.release(ctx, target);
                if (!activeVeinQueue.contains(target)) {
                    activeVeinQueue.addLast(target.toImmutable());
                }
                cachedTarget = sightBlocker.toImmutable();
                return;
            }
            blacklistTracker.blacklist(target, now);
            JobBlockSupport.release(ctx, target);
            cachedTarget = null;
            return;
        }

        ctx.stopNavigation();
        if (JobBlockSupport.breakBlockWithBestTool(ctx, target)) {
            cachedTarget = null;
            veinStallTicks = 0;
            if (activeVeinQueue.isEmpty()) {
                commandedVeinActive = false;
            }
        }
    }

    static boolean shouldKeepVeinAliveOnStall(int stallTicks) {
        return stallTicks < MAX_VEIN_STALL_TICKS;
    }

    public static boolean rejectAreaIfMiner(EntityPlayer player, EntityAutomaton bot) {
        if (!AutomatonBuiltInJobs.MINE.equals(bot.getTaskConfig().getJobId())) {
            return false;
        }
        player.sendMessage(new net.minecraft.util.text.TextComponentTranslation("message.automata.miner_no_area"));
        return true;
    }

    static JobBlockSupport.AreaBounds resolveMineBounds(BlockPos center, int configuredRadius) {
        int radius = Math.max(MIN_SCAN_RADIUS, Math.min(MAX_SCAN_RADIUS, configuredRadius));
        return new JobBlockSupport.AreaBounds(
                center.getX() - radius,
                Math.max(0, center.getY() - radius),
                center.getZ() - radius,
                center.getX() + radius,
                Math.min(255, center.getY() + radius),
                center.getZ() + radius);
    }

    static boolean shouldFollowOwnerFirst(boolean hasOwner, double distSqToOwner, boolean hasActiveVein) {
        return hasOwner && !hasActiveVein && distSqToOwner > OWNER_FOLLOW_BEFORE_SCAN_SQ;
    }

    @Nullable
    private static EntityPlayer resolveNearbyOwner(WorldServer world, EntityAutomaton bot) {
        if (bot.getOwnerUuid() == null) {
            return null;
        }
        EntityPlayer owner = world.getPlayerEntityByUUID(bot.getOwnerUuid());
        if (owner == null || owner.isDead || owner.dimension != bot.dimension) {
            return null;
        }
        return owner;
    }

    @Nullable
    private BlockPos resolveOrFindExposedOre(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds, long now) {
        WorldServer world = ctx.getWorld();
        EntityAutomaton bot = ctx.getAutomaton();
        if (cachedTarget != null) {
            IBlockState cachedState = world.getBlockState(cachedTarget);
            if (isMineableExposedOre(world, cachedTarget, cachedState)
                    && !blacklistTracker.isBlacklisted(cachedTarget, now)
                    && !ctx.isGoalOnCooldown(cachedTarget, now)
                    && !ctx.getBlackboard().isReservedByOther(bot.getEntityId(), cachedTarget, now)
                    && com.x4yi.automata.compat.ClaimProtectionCompat.canModifyBlock(world, bot, cachedTarget)) {
                return cachedTarget;
            }
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }

        BlockPos veinNext = pollNextValidVeinOre(ctx, world, bot, bounds, now);
        if (veinNext != null) {
            cachedTarget = veinNext;
            return veinNext;
        }
        if (now < nextFullScanTick) {
            return null;
        }

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    mutable.setPos(x, y, z);
                    if (!world.isBlockLoaded(mutable)
                            || blacklistTracker.isBlacklisted(mutable, now)
                            || ctx.isGoalOnCooldown(mutable, now)
                            || ctx.getBlackboard().isReservedByOther(bot.getEntityId(), mutable, now)) {
                        continue;
                    }
                    IBlockState state = world.getBlockState(mutable);
                    if (!isMineableExposedOre(world, mutable, state)) {
                        continue;
                    }
                    if (!com.x4yi.automata.compat.ClaimProtectionCompat.canModifyBlock(world, bot, mutable)) {
                        blacklistTracker.blacklist(mutable, now);
                        continue;
                    }
                    double dSq = bot.getDistanceSqToCenter(mutable);
                    if (dSq < bestDistSq && hasReachableApproach(world, bot, ctx.getTaskConfig().isAllowScaffolding(), mutable)) {
                        bestDistSq = dSq;
                        best = mutable.toImmutable();
                    }
                }
            }
        }

        if (best != null && JobBlockSupport.tryReserve(ctx, best)) {
            cachedTarget = best;
            enqueueContiguousVein(world, best);
            return best;
        }
        nextFullScanTick = now + 20L;
        return null;
    }

    @Nullable
    private BlockPos pollNextValidVeinOre(
            AutomatonAIContext ctx,
            WorldServer world,
            EntityAutomaton bot,
            JobBlockSupport.AreaBounds bounds,
            long now) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        Iterator<BlockPos> iterator = activeVeinQueue.iterator();
        while (iterator.hasNext()) {
            BlockPos candidate = iterator.next();
            if (!world.isBlockLoaded(candidate)
                    || blacklistTracker.isBlacklisted(candidate, now)
                    || ctx.isGoalOnCooldown(candidate, now)
                    || ctx.getBlackboard().isReservedByOther(bot.getEntityId(), candidate, now)) {
                iterator.remove();
                continue;
            }
            IBlockState state = world.getBlockState(candidate);
            if (!ModdedOreCatalog.isOreBlock(world, candidate, state)) {
                iterator.remove();
                continue;
            }
            if (!commandedVeinActive && !isWithinBounds(bounds, candidate)) {
                iterator.remove();
                continue;
            }
            if (!hasExposedFace(world, candidate)
                    || !hasReachableApproach(world, bot, ctx.getTaskConfig().isAllowScaffolding(), candidate)) {
                continue;
            }
            double distSq = bot.getDistanceSqToCenter(candidate);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = candidate;
            }
        }
        if (best != null && JobBlockSupport.tryReserve(ctx, best)) {
            activeVeinQueue.remove(best);
            return best;
        }
        return null;
    }

    private static boolean hasReachableApproach(WorldServer world, EntityAutomaton bot, boolean allowScaffolding, BlockPos ore) {
        if (JobBlockSupport.isWithinReach(bot, ore)) {
            return true;
        }
        if (JobBlockSupport.findNearestStandingSpot(world, ore) != null) {
            return true;
        }
        return allowScaffolding && JobBlockSupport.findScaffoldApproachSpot(world, bot, ore) != null;
    }

    private void enqueueContiguousVein(WorldServer world, BlockPos origin) {
        for (BlockPos veinBlock : collectContiguousVein(world, origin, MAX_VEIN_BLOCKS)) {
            if (!veinBlock.equals(origin) && !activeVeinQueue.contains(veinBlock)) {
                activeVeinQueue.addLast(veinBlock);
            }
        }
    }

    static List<BlockPos> collectContiguousVein(WorldServer world, BlockPos origin, int maxBlocks) {
        List<BlockPos> result = new ArrayList<>();
        if (!world.isBlockLoaded(origin) || !ModdedOreCatalog.isOreBlock(world, origin, world.getBlockState(origin))) {
            return result;
        }
        ArrayDeque<BlockPos> bfs = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos start = origin.toImmutable();
        bfs.add(start);
        visited.add(start);
        while (!bfs.isEmpty() && result.size() < maxBlocks) {
            BlockPos current = bfs.pollFirst();
            result.add(current);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.add(dx, dy, dz);
                        if (visited.contains(neighbor) || !world.isBlockLoaded(neighbor)) {
                            continue;
                        }
                        if (ModdedOreCatalog.isOreBlock(world, neighbor, world.getBlockState(neighbor))) {
                            BlockPos immutable = neighbor.toImmutable();
                            visited.add(immutable);
                            bfs.addLast(immutable);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean isWithinBounds(JobBlockSupport.AreaBounds bounds, BlockPos pos) {
        return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }

    static boolean isAdjacentInVein(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return (dx <= 1 && dy <= 1 && dz <= 1) && (dx + dy + dz > 0);
    }

    static boolean isMineableExposedOre(WorldServer world, BlockPos pos, IBlockState state) {
        if (!ModdedOreCatalog.isOreBlock(world, pos, state)) {
            return false;
        }
        return hasExposedFace(world, pos);
    }

    static boolean hasExposedFace(WorldServer world, BlockPos pos) {
        for (EnumFacing face : EnumFacing.VALUES) {
            BlockPos neighbor = pos.offset(face);
            if (!world.isBlockLoaded(neighbor)) {
                continue;
            }
            IBlockState neighborState = world.getBlockState(neighbor);
            if (world.isAirBlock(neighbor) || neighborState.getCollisionBoundingBox(world, neighbor) == null) {
                return true;
            }
        }
        return false;
    }
}

