
package com.x4yi.automata.ai;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.ai.jobs.CombatJobSupport;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.coordination.SquadBlackboard;
import com.x4yi.automata.coordination.SquadRegistry;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.movement.HumanMovementController;
import com.x4yi.automata.navigation.PackedPos;
import com.x4yi.automata.navigation.PathResult;
import com.x4yi.automata.navigation.PathfindingExecutor;
import com.x4yi.automata.navigation.PathfindingSettings;
import com.x4yi.automata.world.MiningProgressController;

public class AutomatonAIContext implements IAutomatonContext {

    private static final int PATH_CACHE_CHUNK_RADIUS = 2;
    private static final long FAILED_GOAL_BASE_COOLDOWN_TICKS = 30L;
    private static final int FAILED_MEMORY_CAPACITY = 16;
    static final long PENDING_PATH_TIMEOUT_TICKS = 200L;

    private final EntityAutomaton automaton;
    private final MiningProgressController miningController = new MiningProgressController();
    private final long[] failedGoalKeys = new long[FAILED_MEMORY_CAPACITY];
    private final long[] failedUntilTicks = new long[FAILED_MEMORY_CAPACITY];
    private final int[] failedCounts = new int[FAILED_MEMORY_CAPACITY];
    @Nullable
    private CombatJobSupport.CombatTacticsState combatTacticsState;

    @Nullable
    private CompletableFuture<PathResult> pendingPath;
    @Nullable
    private AtomicBoolean pendingCancel;
    private int pendingPathDimension = Integer.MIN_VALUE;
    private long pendingPathStartTick;
    @Nullable
    private BlockPos activeGoal;
    private int failedCursor;
    private long lastPathRequestTick;
    private boolean urgentMovement;
    private long reviveChannelStartTick = -1L;
    private int revivingTargetId = -1;
    private long logisticsBackoffUntilTick;
    private long lastPearlUseTick = -100L;
    private String statusMessage = "";
    private long cachedLivingTick = Long.MIN_VALUE;
    private double cachedLivingRadius;
    private List<EntityLivingBase> cachedLivingEntities = Collections.emptyList();
    @Nullable
    private BlockPos lastSafePos;
    private int lastSafeDimension = Integer.MIN_VALUE;
    @Nullable
    private SquadBlackboard cachedBoard;
    private int cachedBoardDimension = Integer.MIN_VALUE;
    @Nullable
    private UUID cachedBoardOwner;
    @Nullable
    private String cachedBoardChannel;

    public AutomatonAIContext(EntityAutomaton automaton) {
        this.automaton = automaton;
    }

    public EntityAutomaton getAutomaton() {
        return automaton;
    }

    public void recordSafePos(BlockPos pos) {
        if (pos != null && pos.getY() >= 1) {
            this.lastSafePos = pos.toImmutable();
            this.lastSafeDimension = automaton.world.provider.getDimension();
        }
    }

    @Nullable
    public BlockPos getLastSafePos() {
        if (lastSafePos != null && lastSafeDimension == automaton.world.provider.getDimension()) {
            return lastSafePos;
        }
        return null;
    }

    public WorldServer getWorld() {
        return (WorldServer) automaton.world;
    }

    public AutomatonTaskConfig getTaskConfig() {
        return automaton.getTaskConfig();
    }

    public HumanMovementController getMovement() {
        return automaton.getMovementController();
    }

    public MiningProgressController getMiningController() {
        return miningController;
    }

    public CombatJobSupport.CombatTacticsState getCombatTacticsState() {
        if (combatTacticsState == null) {
            combatTacticsState = CombatJobSupport.createTacticsState(automaton.getEntityId());
        }
        return combatTacticsState;
    }

    public SquadBlackboard getBlackboard() {
        int dim = automaton.world.provider.getDimension();
        UUID owner = automaton.getOwnerUuid();
        String channel = automaton.getTaskConfig().getSquadChannel();
        if (cachedBoard != null
                && dim == cachedBoardDimension
                && java.util.Objects.equals(owner, cachedBoardOwner)
                && java.util.Objects.equals(channel, cachedBoardChannel)) {
            return cachedBoard;
        }
        SquadBlackboard board = SquadRegistry.getBoard(dim, owner, channel);
        cachedBoard = board;
        cachedBoardDimension = dim;
        cachedBoardOwner = owner;
        cachedBoardChannel = channel;
        return board;
    }

    public void computeAllySeparation(double minGapBlocks, double[] outXz) {
        outXz[0] = 0.0D;
        outXz[1] = 0.0D;
        if (minGapBlocks <= 0.0D) {
            return;
        }
        List<EntityLivingBase> nearby = getNearbyLivingEntities(4.0D);
        if (nearby.isEmpty()) {
            return;
        }
        double selfX = automaton.posX;
        double selfY = automaton.posY;
        double selfZ = automaton.posZ;
        double selfHalfW = automaton.width * 0.5D;
        int selfId = automaton.getEntityId();
        for (EntityLivingBase other : nearby) {
            if (!(other instanceof EntityAutomaton ally)
                    || ally == automaton
                    || !ally.isEntityAlive()
                    || ally.isDowned()
                    || Math.abs(ally.posY - selfY) > 1.8D
                    || !automaton.isAlliedWith(ally)) {
                continue;
            }
            accumulateSeparationStep(
                    selfX,
                    selfZ,
                    selfHalfW,
                    selfId,
                    ally.posX,
                    ally.posZ,
                    ally.width * 0.5D,
                    ally.getEntityId(),
                    minGapBlocks,
                    outXz);
        }
    }

    static void accumulateSeparationStep(
            double selfX,
            double selfZ,
            double selfHalfW,
            int selfId,
            double otherX,
            double otherZ,
            double otherHalfW,
            int otherId,
            double minGapBlocks,
            double[] outXz) {
        double minCenterDist = selfHalfW + otherHalfW + minGapBlocks;
        double dx = selfX - otherX;
        double dz = selfZ - otherZ;
        double distSq = dx * dx + dz * dz;
        if (distSq >= minCenterDist * minCenterDist) {
            return;
        }
        if (distSq < 1.0E-6D) {
            double angle = ((selfId ^ otherId) & 7) * (Math.PI * 0.25D);
            outXz[0] += Math.cos(angle);
            outXz[1] += Math.sin(angle);
            return;
        }
        double dist = Math.sqrt(distSq);
        double weight = (minCenterDist - dist) / minCenterDist;
        outXz[0] += (dx / dist) * weight;
        outXz[1] += (dz / dist) * weight;
    }

    public long getWorldTime() {
        return automaton.world.getTotalWorldTime();
    }

    public List<EntityLivingBase> getNearbyLivingEntities(double radius) {
        long now = getWorldTime();
        double scanRadius = Math.max(16.0D, radius);
        if (!isSnapshotReusable(cachedLivingTick, now, cachedLivingRadius, scanRadius)) {
            AxisAlignedBB box = automaton.getEntityBoundingBox().grow(scanRadius, 6.0D, scanRadius);
            cachedLivingEntities = automaton.world.getEntitiesWithinAABB(EntityLivingBase.class, box);
            cachedLivingTick = now;
            cachedLivingRadius = scanRadius;
        }
        return cachedLivingEntities;
    }

    static boolean isSnapshotReusable(long cachedTick, long currentTick, double cachedRadius, double requestedRadius) {
        return cachedTick == currentTick && cachedRadius >= requestedRadius;
    }

    @Nullable
    public BlockPos getActiveGoal() {
        return activeGoal;
    }

    public void setUrgentMovement(boolean urgentMovement) {
        this.urgentMovement = urgentMovement;
    }

    public boolean isUrgentMovement() {
        return urgentMovement;
    }

    public long advanceReviveChannel(int targetEntityId) {
        long now = getWorldTime();
        if (revivingTargetId != targetEntityId || reviveChannelStartTick < 0L) {
            revivingTargetId = targetEntityId;
            reviveChannelStartTick = now;
            return 0L;
        }
        return Math.max(0L, now - reviveChannelStartTick);
    }

    public void resetReviveChannel() {
        revivingTargetId = -1;
        reviveChannelStartTick = -1L;
    }

    public int getRevivingTargetId() {
        return revivingTargetId;
    }

    public void pollPendingPath() {
        if (pendingPath == null) {
            return;
        }
        int currentDim = automaton.world.provider.getDimension();
        if (currentDim != pendingPathDimension) {
            cancelPendingPath();
            activeGoal = null;
            return;
        }
        long now = getWorldTime();
        if (!pendingPath.isDone()) {
            if (now - pendingPathStartTick > PENDING_PATH_TIMEOUT_TICKS) {
                AutomataMod.LOGGER.warn(
                        "Pathfinding timed out after {} ticks for automaton {} (#{}) toward {}",
                        now - pendingPathStartTick,
                        automaton.getName(),
                        automaton.getEntityId(),
                        activeGoal);
                if (activeGoal != null) {
                    recordFailedGoal(activeGoal, now);
                }
                cancelPendingPath();
            }
            return;
        }

        CompletableFuture<PathResult> finished = pendingPath;
        boolean wasCancelled = pendingCancel != null && pendingCancel.get();
        pendingPath = null;
        pendingCancel = null;
        PathResult result = resolvePendingPathResult(finished);
        if (!result.isEmpty() && isPathPrefixValid(result, 4)) {
            if (activeGoal != null) {
                if (result.isComplete() || result.length() > 3) {
                    clearFailedGoal(activeGoal);
                } else {
                    recordFailedGoal(activeGoal, now);
                }
            }
            getMovement().setPath(result);
        } else if (activeGoal != null && !wasCancelled) {
            recordFailedGoal(activeGoal, now);
        }
    }

    private boolean isPathPrefixValid(PathResult result, int maxSteps) {
        int limit = Math.min(maxSteps, result.length());
        for (int i = 0; i < limit; i++) {
            if (result.getAction(i) != com.x4yi.automata.navigation.PathNodeAction.WALK) {
                continue;
            }
            long packed = result.getStep(i);
            BlockPos pos = new BlockPos(
                    PackedPos.unpackX(packed),
                    PackedPos.unpackY(packed),
                    PackedPos.unpackZ(packed));
            if (automaton.world.isBlockLoaded(pos)
                    && automaton.world.getBlockState(pos).getMaterial().blocksMovement()) {
                return false;
            }
        }
        return true;
    }

    static PathResult resolvePendingPathResult(CompletableFuture<PathResult> future) {
        try {
            PathResult result = future.getNow(PathResult.EMPTY);
            return result != null ? result : PathResult.EMPTY;
        } catch (CompletionException | CancellationException e) {
            AutomataMod.LOGGER.warn("Async pathfinding task failed or was cancelled", e);
            return PathResult.EMPTY;
        }
    }

    public boolean isGoalOnCooldown(BlockPos goal, long now) {
        long key = PackedPos.pack(goal.getX(), goal.getY(), goal.getZ());
        for (int i = 0; i < FAILED_MEMORY_CAPACITY; i++) {
            if (failedGoalKeys[i] == key && failedUntilTicks[i] > now) {
                return true;
            }
        }
        return false;
    }

    public void recordFailedGoal(BlockPos goal, long now) {
        long key = PackedPos.pack(goal.getX(), goal.getY(), goal.getZ());
        for (int i = 0; i < FAILED_MEMORY_CAPACITY; i++) {
            if (failedGoalKeys[i] == key) {
                int count = Math.min(3, failedCounts[i] + 1);
                failedCounts[i] = count;
                failedUntilTicks[i] = now + (FAILED_GOAL_BASE_COOLDOWN_TICKS << count);
                return;
            }
        }
        failedGoalKeys[failedCursor] = key;
        failedCounts[failedCursor] = 0;
        failedUntilTicks[failedCursor] = now + FAILED_GOAL_BASE_COOLDOWN_TICKS;
        failedCursor = (failedCursor + 1) % FAILED_MEMORY_CAPACITY;
    }

    public void clearFailedGoal(BlockPos goal) {
        long key = PackedPos.pack(goal.getX(), goal.getY(), goal.getZ());
        for (int i = 0; i < FAILED_MEMORY_CAPACITY; i++) {
            if (failedGoalKeys[i] == key) {
                failedUntilTicks[i] = 0L;
                failedCounts[i] = 0;
            }
        }
    }

    public void clearAllFailedGoals() {
        for (int i = 0; i < FAILED_MEMORY_CAPACITY; i++) {
            failedUntilTicks[i] = 0L;
            failedCounts[i] = 0;
        }
    }

    public boolean isLogisticsOnBackoff(long now) {
        return now < logisticsBackoffUntilTick;
    }

    public void triggerLogisticsBackoff(long now, long durationTicks) {
        this.logisticsBackoffUntilTick = Math.max(this.logisticsBackoffUntilTick, now + durationTicks);
    }

    public boolean canUseEnderPearl(long now) {
        return now - lastPearlUseTick >= 40L;
    }

    public void markEnderPearlUsed(long now) {
        this.lastPearlUseTick = now;
    }

    public void requestPathTo(BlockPos goal, boolean urgent) {
        requestPathTo(goal, urgent, PathfindingSettings.DEFAULT_MAX_NODES);
    }

    public void requestPathTo(BlockPos goal, boolean urgent, int maxNodes) {
        requestPathInternal(goal, urgent, maxNodes, null);
    }

    private void requestPathInternal(
            BlockPos goal,
            boolean urgent,
            int maxNodes,
            @Nullable PathfindingSettings explicitSettings) {
        this.urgentMovement = urgent;
        long now = getWorldTime();
        long stuckKey = getMovement().getLastStuckGoal();
        if (stuckKey != Long.MIN_VALUE) {
            BlockPos stuckGoal = new BlockPos(
                    PackedPos.unpackX(stuckKey),
                    PackedPos.unpackY(stuckKey),
                    PackedPos.unpackZ(stuckKey));
            if (!isGoalOnCooldown(stuckGoal, now)) {
                recordFailedGoal(stuckGoal, now);
                lastPathRequestTick = 0L;
            }
        }
        if (activeGoal != null && activeGoal.equals(goal) && (getMovement().hasActivePath() || pendingPath != null)) {
            return;
        }
        if (isGoalOnCooldown(goal, now)) {
            return;
        }
        if (now - lastPathRequestTick < 10L && pendingPath != null) {
            return;
        }

        cancelPendingPath();
        activeGoal = goal.toImmutable();
        lastPathRequestTick = now;

        BlockPos startPos = new BlockPos(
                MathHelper.floor(automaton.posX),
                MathHelper.floor(automaton.posY + 0.1D),
                MathHelper.floor(automaton.posZ));
        double dist = Math.sqrt(startPos.distanceSq(goal));
        int chunkRadius = computePathChunkRadius(dist);
        int effectiveMaxNodes = computePathBudget(dist, maxNodes);
        automaton.getChunkLoader().setExtraWorkChunk(goal);

        int scaffoldCount = EquipmentSelector.countScaffoldBlocks(automaton);
        PathfindingSettings settings;
        if (explicitSettings != null) {
            settings = new PathfindingSettings(
                    explicitSettings.allowBreakThrough(),
                    explicitSettings.allowSprintJump(),
                    explicitSettings.allowScaffolding() && scaffoldCount > 0,
                    Math.max(effectiveMaxNodes, explicitSettings.maxExpandedNodes()),
                    explicitSettings.maxFallDistance(),
                    Math.min(scaffoldCount, explicitSettings.availableScaffoldBlocks()));
        } else {
            boolean allowBreak = AutomataConfig.allowTerrainModificationInPathing && getTaskConfig().isAllowBreakThrough();
            boolean allowScaffold = AutomataConfig.allowTerrainModificationInPathing
                    && getTaskConfig().isAllowScaffolding()
                    && scaffoldCount > 0;
            settings = new PathfindingSettings(
                    allowBreak,
                    getTaskConfig().isAllowParkour(),
                    allowScaffold,
                    effectiveMaxNodes,
                    PathfindingSettings.DEFAULT_MAX_FALL,
                    scaffoldCount);
        }

        if (!com.x4yi.automata.navigation.WorldCacheManager.ensureCorridorCached(
                automaton.world, startPos, goal, chunkRadius)) {
            return;
        }
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        pendingCancel = cancelFlag;
        pendingPathDimension = automaton.world.provider.getDimension();
        pendingPathStartTick = now;
        pendingPath = PathfindingExecutor.submitServerPath(
                automaton.world,
                startPos,
                goal,
                chunkRadius,
                settings,
                cancelFlag);
    }

    static int computePathChunkRadius(double distanceBlocks) {
        int needed = ((int) Math.ceil(distanceBlocks / 16.0D)) + 1;
        return Math.max(PATH_CACHE_CHUNK_RADIUS, Math.min(8, needed));
    }

    static int computePathBudget(double distanceBlocks, int requestedMaxNodes) {
        int base = Math.max(PathfindingSettings.DEFAULT_MAX_NODES, requestedMaxNodes);
        if (distanceBlocks <= 32.0D) {
            return base;
        }
        int scaled = (int) Math.ceil(distanceBlocks * 320.0D);
        return Math.min(32768, Math.max(base, scaled));
    }

    public void stopNavigation() {
        cancelPendingPath();
        activeGoal = null;
        urgentMovement = false;
        getMovement().clearPath();
    }

    public void nudgeUnstick() {
        cancelPendingPath();
        activeGoal = null;
        clearAllFailedGoals();
        logisticsBackoffUntilTick = 0L;
        urgentMovement = false;
        getMovement().nudgeUnstick();
    }

    public void resetMining() {
        if (automaton.world instanceof WorldServer worldServer) {
            miningController.reset(worldServer, automaton);
        }
    }

    public void cancelPendingPath() {
        if (pendingCancel != null) {
            pendingCancel.set(true);
            pendingCancel = null;
        }
        pendingPath = null;
    }

    @Override
    public void requestPath(BlockPos goal, PathfindingSettings settings, boolean allowCooldownBypass) {
        if (allowCooldownBypass) {
            clearFailedGoal(goal);
        }
        int requestedNodes = settings != null ? settings.maxExpandedNodes() : PathfindingSettings.DEFAULT_MAX_NODES;
        requestPathInternal(goal, false, requestedNodes, settings);
    }

    @Override
    public void cancelMovement() {
        stopNavigation();
    }

    @Override
    public void setStatusMessage(String status) {
        this.statusMessage = status == null ? "" : status;
    }

    @Override
    public String getStatusMessage() {
        return statusMessage;
    }

    @Override
    public IItemHandlerModifiable getCombinedInventory() {
        return automaton.getInventory().getCombinedInventory();
    }

    @Override
    public boolean tryReserveBlock(BlockPos pos, long ttlTicks) {
        return getBlackboard().tryReserveBlock(automaton.getEntityId(), pos, getWorldTime(), ttlTicks);
    }

    @Override
    public void releaseBlock(BlockPos pos) {
        getBlackboard().releaseBlock(automaton.getEntityId(), pos);
    }

    @Override
    public boolean isBlockReservedByOther(BlockPos pos) {
        return getBlackboard().isReservedByOther(automaton.getEntityId(), pos, getWorldTime());
    }

    @Override
    public boolean stepBreakBlock(BlockPos pos) {
        return miningController.stepBreakBlock(getWorld(), automaton, pos);
    }

    @Override
    public boolean isWithinReach(BlockPos pos) {
        return automaton.getDistanceSqToCenter(pos) <= 20.25D;
    }

    @Override
    public long consumeStuckGoal() {
        long stuck = getMovement().getLastStuckGoal();
        if (stuck != Long.MIN_VALUE) {
            getMovement().clearLastStuckGoal();
        }
        return stuck;
    }
}

