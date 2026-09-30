
package com.x4yi.automata.ai;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;

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

    private final EntityAutomaton automaton;
    private final MiningProgressController miningController = new MiningProgressController();
    private final long[] failedGoalKeys = new long[FAILED_MEMORY_CAPACITY];
    private final long[] failedUntilTicks = new long[FAILED_MEMORY_CAPACITY];
    private final int[] failedCounts = new int[FAILED_MEMORY_CAPACITY];

    @Nullable
    private CompletableFuture<PathResult> pendingPath;
    @Nullable
    private AtomicBoolean pendingCancel;
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

    public AutomatonAIContext(EntityAutomaton automaton) {
        this.automaton = automaton;
    }

    public EntityAutomaton getAutomaton() {
        return automaton;
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

    public SquadBlackboard getBlackboard() {
        return SquadRegistry.getBoard(
                automaton.world.provider.getDimension(),
                automaton.getOwnerUuid(),
                automaton.getTaskConfig().getSquadChannel());
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
        if (pendingPath != null && pendingPath.isDone()) {
            PathResult result = pendingPath.getNow(PathResult.EMPTY);
            pendingPath = null;
            pendingCancel = null;
            if (!result.isEmpty()) {
                if (activeGoal != null) {
                    if (result.isComplete() || result.length() > 3) {
                        clearFailedGoal(activeGoal);
                    } else {
                        recordFailedGoal(activeGoal, getWorldTime());
                    }
                }
                getMovement().setPath(result);
            } else if (activeGoal != null) {
                recordFailedGoal(activeGoal, getWorldTime());
            }
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

        boolean allowBreak = AutomataConfig.allowTerrainModificationInPathing && getTaskConfig().isAllowBreakThrough();
        boolean allowScaffold = AutomataConfig.allowTerrainModificationInPathing
                && getTaskConfig().isAllowScaffolding()
                && EquipmentSelector.hasBuildingBlocks(automaton);
        PathfindingSettings settings = new PathfindingSettings(
                allowBreak,
                getTaskConfig().isAllowParkour(),
                allowScaffold,
                effectiveMaxNodes,
                PathfindingSettings.DEFAULT_MAX_FALL);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        pendingCancel = cancelFlag;
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
        requestPathTo(goal, false);
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

