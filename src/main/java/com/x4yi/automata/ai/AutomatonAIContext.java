
package com.x4yi.automata.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
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
import com.x4yi.automata.movement.MovementPace;
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
    @Nullable
    private BlockPos activeWaterDropPos;
    private long waterDropPlacedTick = -1L;
    private String statusMessage = "";
    private long cachedLivingTick = Long.MIN_VALUE;
    private double cachedLivingRadius;
    private final List<EntityLivingBase> cachedLivingEntities = new ArrayList<>(32);
    private long cachedEntityTick = Long.MIN_VALUE;
    private double cachedEntityRadius;
    private List<Entity> cachedAllEntities = Collections.emptyList();
    private long cachedHazardScanTick = Long.MIN_VALUE;
    private double cachedHazardScanRadius;
    private final List<Entity> cachedHazardEntities = new ArrayList<>(8);
    private long cachedFriendliesTick = Long.MIN_VALUE;
    private double cachedFriendliesRadius;
    private final List<EntityLivingBase> cachedNearbyFriendlies = new ArrayList<>();
    private final Int2LongOpenHashMap visibilityCache = new Int2LongOpenHashMap(32);
    private long cachedPrimaryTargetTick = Long.MIN_VALUE;
    private double cachedPrimaryTargetRadius;
    @Nullable
    private EntityLivingBase cachedPrimaryTarget;
    private final List<EntityAutomaton> scratchVulnerableAllies = new ArrayList<>(8);
    private final List<EntityAutomaton> scratchHealthyAllies = new ArrayList<>(16);
    @Nullable
    private com.x4yi.automata.ai.jobs.ThreatFieldEvaluator.ThreatAssessment cachedThreatAssessment;
    private long cachedThreatTick = Long.MIN_VALUE;
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
    @Nullable
    private BotLoadoutProfile cachedLoadoutProfile;
    @Nullable
    private Item cachedLoadoutMainItem;
    private long cachedLoadoutTick = Long.MIN_VALUE;

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

    public BotLoadoutProfile getLoadoutProfile() {
        long now = getWorldTime();
        Item currentMain = automaton.getHeldItemMainhand().getItem();
        if (cachedLoadoutProfile != null
                && now - cachedLoadoutTick < 10L
                && currentMain == cachedLoadoutMainItem) {
            return cachedLoadoutProfile;
        }
        cachedLoadoutProfile = BotLoadoutProfile.scan(automaton);
        cachedLoadoutMainItem = currentMain;
        cachedLoadoutTick = now;
        return cachedLoadoutProfile;
    }

    public void invalidateLoadoutProfile() {
        cachedLoadoutTick = Long.MIN_VALUE;
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
        List<EntityLivingBase> nearby = getNearbyFriendlies(16.0D);
        if (nearby.isEmpty()) {
            return;
        }
        double selfX = automaton.posX;
        double selfY = automaton.posY;
        double selfZ = automaton.posZ;
        double selfHalfW = automaton.width * 0.5D;
        int selfId = automaton.getEntityId();
        for (int i = 0, n = nearby.size(); i < n; i++) {
            EntityLivingBase other = nearby.get(i);
            if (!(other instanceof EntityAutomaton ally)
                    || ally == automaton
                    || !ally.isEntityAlive()
                    || ally.isDowned()
                    || Math.abs(ally.posY - selfY) > 1.8D) {
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
        double scanRadius = Math.max(16.0D, Math.min(48.0D, radius));
        if (radius > 48.0D) {
            List<EntityLivingBase> out = new ArrayList<>(32);
            WorldEntitySpatialIndex.queryLiving(
                    automaton.world, automaton.posX, automaton.posY, automaton.posZ, radius, 8.0D, out);
            return out;
        }
        if (!isSnapshotReusable(cachedLivingTick, now, cachedLivingRadius, scanRadius)) {
            WorldEntitySpatialIndex.queryLiving(
                    automaton.world, automaton.posX, automaton.posY, automaton.posZ, scanRadius, 6.0D, cachedLivingEntities);
            cachedLivingTick = now;
            cachedLivingRadius = scanRadius;
        }
        return cachedLivingEntities;
    }

    public List<Entity> getNearbyEntities(double radius) {
        long now = getWorldTime();
        double scanRadius = Math.max(12.0D, radius);
        if (!isSnapshotReusable(cachedEntityTick, now, cachedEntityRadius, scanRadius)) {
            AxisAlignedBB box = automaton.getEntityBoundingBox().grow(scanRadius, 6.0D, scanRadius);
            cachedAllEntities = automaton.world.getEntitiesWithinAABBExcludingEntity(automaton, box);
            cachedEntityTick = now;
            cachedEntityRadius = scanRadius;
        }
        return cachedAllEntities;
    }

    public List<Entity> getNearbyHazardAndProjectileEntities(double radius) {
        long now = getWorldTime();
        double scanRadius = Math.max(12.0D, radius);
        if (!isSnapshotReusable(cachedHazardScanTick, now, cachedHazardScanRadius, scanRadius)) {
            WorldEntitySpatialIndex.queryHazards(
                    automaton.world, automaton, automaton.posX, automaton.posY, automaton.posZ, scanRadius, 6.0D, cachedHazardEntities);
            cachedHazardScanTick = now;
            cachedHazardScanRadius = scanRadius;
        }
        return cachedHazardEntities;
    }

    public List<EntityLivingBase> getNearbyFriendlies(double radius) {
        long now = getWorldTime();
        double scanRadius = Math.max(16.0D, radius);
        if (cachedFriendliesTick == Long.MIN_VALUE
                || now < cachedFriendliesTick
                || now - cachedFriendliesTick >= 10L
                || scanRadius > cachedFriendliesRadius) {
            cachedNearbyFriendlies.clear();
            List<EntityLivingBase> dimFriendlies = WorldEntitySpatialIndex.getFriendlies(automaton.world);
            UUID ownerUuid = automaton.getOwnerUuid();
            double maxDistSq = scanRadius * scanRadius;
            for (int i = 0, n = dimFriendlies.size(); i < n; i++) {
                EntityLivingBase entity = dimFriendlies.get(i);
                if (entity == automaton || !entity.isEntityAlive() || automaton.getDistanceSq(entity) > maxDistSq) {
                    continue;
                }
                if (entity instanceof EntityAutomaton ally) {
                    if (!ally.isDowned() && automaton.isAlliedWith(ally)) {
                        cachedNearbyFriendlies.add(ally);
                    }
                } else if (ownerUuid != null && entity instanceof EntityPlayer player && ownerUuid.equals(player.getUniqueID())) {
                    cachedNearbyFriendlies.add(player);
                }
            }
            cachedFriendliesTick = now;
            cachedFriendliesRadius = scanRadius;
        }
        return cachedNearbyFriendlies;
    }

    public boolean canSeeEntityCached(EntityLivingBase target) {
        if (target == null || !target.isEntityAlive()) {
            return false;
        }
        long now = getWorldTime();
        int targetId = target.getEntityId();
        long packed = visibilityCache.getOrDefault(targetId, Long.MIN_VALUE);
        if (packed != Long.MIN_VALUE) {
            long cachedTick = packed >> 1;
            if (now >= cachedTick && now - cachedTick < 4L) {
                return (packed & 1L) != 0L;
            }
        }
        if (visibilityCache.size() > 128) {
            visibilityCache.clear();
        }
        boolean visible = automaton.canEntityBeSeen(target);
        visibilityCache.put(targetId, (now << 1) | (visible ? 1L : 0L));
        return visible;
    }

    @Nullable
    public EntityLivingBase getCachedPrimaryTarget(long worldTime, double radius) {
        if (cachedPrimaryTargetTick == worldTime && cachedPrimaryTargetRadius >= radius) {
            if (cachedPrimaryTarget != null && !cachedPrimaryTarget.isEntityAlive()) {
                return null;
            }
            return cachedPrimaryTarget;
        }
        return null;
    }

    public boolean hasCachedPrimaryTargetForTick(long worldTime, double radius) {
        return cachedPrimaryTargetTick == worldTime && cachedPrimaryTargetRadius >= radius;
    }

    public void cachePrimaryTarget(long worldTime, double radius, @Nullable EntityLivingBase target) {
        this.cachedPrimaryTargetTick = worldTime;
        this.cachedPrimaryTargetRadius = radius;
        this.cachedPrimaryTarget = target;
    }

    public List<EntityAutomaton> getScratchVulnerableAllies() {
        scratchVulnerableAllies.clear();
        return scratchVulnerableAllies;
    }

    public List<EntityAutomaton> getScratchHealthyAllies() {
        scratchHealthyAllies.clear();
        return scratchHealthyAllies;
    }

    public com.x4yi.automata.ai.jobs.ThreatFieldEvaluator.ThreatAssessment getCachedThreatAssessment() {
        long now = getWorldTime();
        if (cachedThreatAssessment != null && now - cachedThreatTick < 4L) {
            return cachedThreatAssessment;
        }
        cachedThreatAssessment = com.x4yi.automata.ai.jobs.ThreatFieldEvaluator.assessLocalThreats(this, automaton);
        cachedThreatTick = now;
        return cachedThreatAssessment;
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

    private long cachedHazardTick = Long.MIN_VALUE;
    private boolean cachedExplosiveHazard;

    public boolean hasCachedExplosiveHazard(double radius) {
        long now = getWorldTime();
        if (cachedHazardTick != now) {
            cachedHazardTick = now;
            cachedExplosiveHazard = ExplosiveHazardDetector.findNearestExplosive(this, radius) != null;
        }
        return cachedExplosiveHazard;
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

    private final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap failurePenalties =
            new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap failureExpiry =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    public void recordMovementFailure(long packedPos, long now) {
        if (packedPos == Long.MIN_VALUE) {
            return;
        }
        float updated = Math.min(140.0F, failurePenalties.get(packedPos) + 35.0F);
        failurePenalties.put(packedPos, updated);
        failureExpiry.put(packedPos, now + 200L);
    }

    @Nullable
    it.unimi.dsi.fastutil.longs.Long2FloatMap snapshotActivePenalties(long now) {
        it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap map = null;
        if (!failurePenalties.isEmpty()) {
            it.unimi.dsi.fastutil.longs.LongIterator it = failureExpiry.keySet().iterator();
            while (it.hasNext()) {
                long k = it.nextLong();
                if (now >= failureExpiry.get(k)) {
                    it.remove();
                    failurePenalties.remove(k);
                }
            }
            if (!failurePenalties.isEmpty()) {
                map = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap(failurePenalties);
            }
        }
        List<Entity> entities = getNearbyHazardAndProjectileEntities(12.0D);
        double botHalfW = automaton.width * 0.5D;
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity entity = entities.get(i);
            if (!ExplosiveHazardDetector.isStaticAreaHazard(entity)) {
                continue;
            }
            if (map == null) {
                map = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();
            }
            double minClearance = (entity.width * 0.5D) + botHalfW + ExplosiveHazardDetector.STATIC_HAZARD_MARGIN_BLOCKS;
            int r = MathHelper.ceil(minClearance);
            double rSq = minClearance * minClearance;
            int cx = MathHelper.floor(entity.posX);
            int cy = MathHelper.floor(entity.posY);
            int cz = MathHelper.floor(entity.posZ);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > rSq) {
                        continue;
                    }
                    for (int dy = 0; dy <= 1; dy++) {
                        long packed = PackedPos.pack(cx + dx, cy + dy, cz + dz);
                        float prev = map.get(packed);
                        if (prev < 90.0F) {
                            map.put(packed, 90.0F);
                        }
                    }
                }
            }
        }
        return map;
    }

    private void requestPathInternal(
            BlockPos goal,
            boolean urgent,
            int maxNodes,
            @Nullable PathfindingSettings explicitSettings) {
        boolean wasUrgent = this.urgentMovement;
        this.urgentMovement = urgent;
        long now = getWorldTime();
        long stuckWaypoint = getMovement().consumeLastStuckWaypoint();
        if (stuckWaypoint != Long.MIN_VALUE) {
            recordMovementFailure(stuckWaypoint, now);
        }
        long stuckKey = getMovement().getLastStuckGoal();
        if (stuckKey != Long.MIN_VALUE) {
            getMovement().clearLastStuckGoal();
            BlockPos stuckGoal = new BlockPos(
                    PackedPos.unpackX(stuckKey),
                    PackedPos.unpackY(stuckKey),
                    PackedPos.unpackZ(stuckKey));
            if (!isGoalOnCooldown(stuckGoal, now)) {
                recordFailedGoal(stuckGoal, now);
                lastPathRequestTick = 0L;
            }
        }
        if (activeGoal != null
                && (activeGoal.equals(goal) || activeGoal.distanceSq(goal) <= 2.25D)
                && (getMovement().hasActivePath() || pendingPath != null)) {
            return;
        }
        if (isGoalOnCooldown(goal, now)) {
            return;
        }
        if (pendingPath != null && (now - pendingPathStartTick) < 15L) {
            return;
        }
        boolean preemptiveUrgent = urgent && (!wasUrgent || activeGoal == null || activeGoal.distanceSq(goal) > 16.0D);
        if (!preemptiveUrgent && now - lastPathRequestTick < 10L && pendingPath != null) {
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
        long maxDurationNanos = PathfindingSettings.DEFAULT_MAX_DURATION_NANOS;
        if (explicitSettings == null && dist <= 24.0D && combatTacticsState.activeTargetId >= 0) {
            effectiveMaxNodes = Math.min(effectiveMaxNodes, 2048);
            maxDurationNanos = PathfindingSettings.TACTICAL_MAX_DURATION_NANOS;
        }
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
                    Math.min(scaffoldCount, explicitSettings.availableScaffoldBlocks()),
                    explicitSettings.maxDurationNanos());
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
                    scaffoldCount,
                    maxDurationNanos);
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
                cancelFlag,
                snapshotActivePenalties(now));
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
        automaton.getNavigator().clearPath();
    }

    public void pauseNavigationForDirectControl() {
        getMovement().pausePathForDirectControl();
    }

    @Override
    public void pauseMovement() {
        pauseNavigationForDirectControl();
    }

    @Override
    public MovementPace getMovementPace() {
        return getMovement().getPace();
    }

    @Override
    public void setMovementPace(MovementPace pace) {
        getMovement().setPace(pace);
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

    public void setActiveWaterDrop(@Nullable BlockPos pos, long tick) {
        this.activeWaterDropPos = pos == null ? null : pos.toImmutable();
        this.waterDropPlacedTick = tick;
    }

    @Nullable
    public BlockPos getActiveWaterDropPos() {
        return activeWaterDropPos;
    }

    public long getWaterDropPlacedTick() {
        return waterDropPlacedTick;
    }

    public void clearActiveWaterDrop() {
        this.activeWaterDropPos = null;
        this.waterDropPlacedTick = -1L;
    }
}

