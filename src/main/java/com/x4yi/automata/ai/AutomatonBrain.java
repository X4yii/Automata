package com.x4yi.automata.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.ai.UtilityBrainArbiter.BrainBehavior;
import com.x4yi.automata.ai.UtilityBrainArbiter.UtilityState;
import com.x4yi.automata.ai.jobs.CombatJobSupport;
import com.x4yi.automata.ai.jobs.TacticalUtilityController;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.api.JobDescriptor;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.movement.MovementPace;
import com.x4yi.automata.world.MiningProgressController;

public class AutomatonBrain {

    private static final int FAST_DECISION_INTERVAL_TICKS = 4;
    private static final int STANDARD_DECISION_INTERVAL_TICKS = 6;
    private static final int INVENTORY_MANAGE_INTERVAL_TICKS = 20;
    private static final long SQUAD_SUPPLY_SCAN_INTERVAL_TICKS = 6L;
    private static final int BLACKBOARD_PRUNE_INTERVAL = 100;
    private static final double MINING_REACH_SQ = 20.25D;
    private static final int OVERRIDE_FAILURE_THRESHOLD = 3;
    private static final long OVERRIDE_BACKOFF_TICKS = 60L;

    private final AutomatonAIContext context;
    private final Map<ResourceLocation, IAutomatonJob> jobs = new HashMap<>();
    private final int[] overrideFailures = new int[BrainBehavior.values().length];
    private final long[] overrideBackoffUntil = new long[BrainBehavior.values().length];
    private final double[] separationScratch = new double[2];
    private ResourceLocation activeJobId = AutomatonBuiltInJobs.IDLE;
    private BrainBehavior activeBehavior = BrainBehavior.ACTIVE_JOB;
    private int decisionCooldownTicks;
    private float cachedOccupancy = -1.0F;
    private long lastSquadSupplyScanTick = Long.MIN_VALUE;

    public AutomatonBrain(EntityAutomaton automaton) {
        this.context = new AutomatonAIContext(automaton);
        this.decisionCooldownTicks = Math.abs(automaton.getEntityId()) % FAST_DECISION_INTERVAL_TICKS;
        for (JobDescriptor descriptor : AutomataAPI.getAllJobs()) {
            jobs.put(descriptor.getId(), descriptor.createJob());
        }
    }

    public AutomatonAIContext getContext() {
        return context;
    }

    @Nullable
    public IAutomatonJob getActiveJob() {
        return jobs.get(activeJobId);
    }

    public int computeDecisionIntervalTicks() {
        JobDescriptor descriptor = AutomataAPI.getJob(activeJobId);
        int base;
        if (descriptor != null && descriptor.getDecisionIntervalTicks() > 0) {
            base = descriptor.getDecisionIntervalTicks();
        } else if (isCombatJob(activeJobId)
                || context.isUrgentMovement()
                || context.getMiningController().isMining()
                || context.getMovement().isActionLookLocked()) {
            base = FAST_DECISION_INTERVAL_TICKS;
        } else {
            base = STANDARD_DECISION_INTERVAL_TICKS;
        }
        double mspt = context.getAutomaton().world != null
                ? BudgetedTickScheduler.getServerMspt(context.getAutomaton().world.getMinecraftServer())
                : 0.0D;
        return BudgetedTickScheduler.scaleInterval(base, mspt);
    }

    private boolean isCombatJob(ResourceLocation jobId) {
        JobDescriptor descriptor = AutomataAPI.getJob(jobId);
        return descriptor != null && descriptor.isCombat();
    }

    enum OwnerAvailability {
        AVAILABLE,
        OWNER_NULL,
        OWNER_OFFLINE,
        OWNER_DEAD
    }

    static OwnerAvailability evaluateOwnerAvailability(
            @Nullable UUID ownerUuid,
            @Nullable MinecraftServer server) {
        if (ownerUuid == null) {
            return OwnerAvailability.OWNER_NULL;
        }
        if (server == null) {
            return OwnerAvailability.OWNER_OFFLINE;
        }
        EntityPlayerMP owner = server.getPlayerList().getPlayerByUUID(ownerUuid);
        if (owner == null) {
            return OwnerAvailability.OWNER_OFFLINE;
        }
        if (!owner.isEntityAlive()) {
            return OwnerAvailability.OWNER_DEAD;
        }
        return OwnerAvailability.AVAILABLE;
    }

    public void tickServer() {
        long benchBrainStart = com.x4yi.automata.bench.InGameBenchmarkProfiler.isActive() ? System.nanoTime() : 0L;
        try {
        EntityAutomaton bot = context.getAutomaton();
        if (bot.isDowned()) {
            bot.getChunkLoader().releaseAll();
            return;
        }
        if (!com.x4yi.automata.config.AutomataConfig.workWhenOwnerOffline) {
            OwnerAvailability ownerState = evaluateOwnerAvailability(
                    bot.getOwnerUuid(),
                    bot.world.getMinecraftServer());
            if (ownerState != OwnerAvailability.AVAILABLE) {
                suspendForUnavailableOwner(bot);
                return;
            }
        }

        context.pollPendingPath();

        long worldTime = context.getWorldTime();
        int entityId = bot.getEntityId();
        net.minecraft.server.MinecraftServer server = bot.world.getMinecraftServer();

        if (BudgetedTickScheduler.shouldRun(worldTime, entityId, BLACKBOARD_PRUNE_INTERVAL, server)) {
            context.getBlackboard().pruneExpired(worldTime);
        }
        if (BudgetedTickScheduler.shouldRun(worldTime, entityId, 10, server)) {
            OffhandPolicy.applyOffhand(bot, false, worldTime);
        }

        boolean refreshOccupancy = cachedOccupancy < 0.0F || activeBehavior == BrainBehavior.LOGISTICS;
        if (BudgetedTickScheduler.shouldRun(worldTime, entityId, INVENTORY_MANAGE_INTERVAL_TICKS, server)) {
            InventoryManagerService.tickInventoryManagement(context);
            refreshOccupancy = true;
        }
        if (refreshOccupancy) {
            cachedOccupancy = computeInventoryOccupancy(bot);
        }

        long benchSurvivalStart = benchBrainStart != 0L ? System.nanoTime() : 0L;
        TacticalUtilityController.tickWaterDropClutch20Hz(context);
        com.x4yi.automata.ai.jobs.VengeanceBookController.tickAirClutch20Hz(context);
        TacticalUtilityController.tryEmergencyPearlRescue(context);
        TacticalUtilityController.tryDrinkConsumable(context);
        if (SurvivalEvaluator.tickVoidRescue20Hz(context)) {
            if (benchSurvivalStart != 0L) {
                com.x4yi.automata.bench.InGameBenchmarkProfiler.record(
                        com.x4yi.automata.bench.InGameBenchmarkProfiler.Phase.BRAIN_SURVIVAL,
                        System.nanoTime() - benchSurvivalStart);
            }
            return;
        }
        SurvivalEvaluator.tickLavaEscape20Hz(context);
        boolean dodgingWave = com.x4yi.automata.ai.jobs.WavePredictionDodgeController.tickWaveAndAoeDodge20Hz(context);

        boolean immediateHazard = SurvivalEvaluator.isImmediateHazard(context);
        int activeTargetId = context.getCombatTacticsState().activeTargetId;
        boolean targetLost = activeTargetId >= 0
                && !isEntityAliveById(bot.world, activeTargetId);
        boolean freshNonCombatHit = !isCombatJob(activeJobId)
                && bot.hurtTime > 0
                && bot.hurtTime == bot.maxHurtTime;
        if (immediateHazard || targetLost || freshNonCombatHit) {
            decisionCooldownTicks = 0;
        }
        if (benchSurvivalStart != 0L) {
            com.x4yi.automata.bench.InGameBenchmarkProfiler.record(
                    com.x4yi.automata.bench.InGameBenchmarkProfiler.Phase.BRAIN_SURVIVAL,
                    System.nanoTime() - benchSurvivalStart);
        }

        boolean ranDecisionThisTick = false;
        long benchDecisionStart = benchBrainStart != 0L ? System.nanoTime() : 0L;
        if (!dodgingWave && --decisionCooldownTicks <= 0) {
            ranDecisionThisTick = true;
            syncDesiredJob(bot);
            decisionCooldownTicks = computeDecisionIntervalTicks();
            IAutomatonJob activeJob = jobs.get(activeJobId);
            boolean combatWithTargets = isCombatJob(activeJobId) && hasCombatTargetsInRange(bot);
            boolean committedWork = (activeJob != null && activeJob.hasCommittedWork(context))
                    || combatWithTargets
                    || context.getMiningController().isMining();
            float occupancy = cachedOccupancy;
            boolean lowHealthOrWither = SurvivalEvaluator.hasActionableLowHealthSurvival(context);
            boolean activeRangedCombat = isCombatJob(activeJobId) && hasActiveRangedEngagement(bot);
            boolean hasUnclaimedDowned = context.getBlackboard().findNearestDowned(entityId, bot.getPosition(), worldTime) != null;
            boolean isDesignatedMedic = context.getRevivingTargetId() >= 0;
            boolean suppressReviveForRanged = activeRangedCombat && !hasUnclaimedDowned && !isDesignatedMedic;
            boolean allyNeedsRevive = !suppressReviveForRanged
                    && context.getTaskConfig().isSquadCooperation()
                    && (hasUnclaimedDowned || isDesignatedMedic);
            boolean reactiveThreat = !isCombatJob(activeJobId)
                    && bot.getRevengeTarget() != null
                    && bot.getRevengeTarget().isEntityAlive();
            boolean ownerFar = isOwnerFarAway(bot, 256.0D);
            UtilityState utilityState = UtilityState.builder()
                    .inHazardOrExplosive(immediateHazard)
                    .lowHealthNeedsSurvival(lowHealthOrWither)
                    .allyNeedsRevive(allyNeedsRevive)
                    .hasThreatOrRevenge(reactiveThreat)
                    .safeLootAvailable(!activeRangedCombat
                            && !combatWithTargets
                            && !ownerFar
                            && context.getTaskConfig().isCanPickUpLoot()
                            && context.getTaskConfig().isSearchLoot())
                    .logisticsReady(LogisticsService.needsLogistics(context))
                    .inventoryOccupancy(occupancy)
                    .hasActiveCommittedJob(committedWork)
                    .build();

            BrainBehavior selected = UtilityBrainArbiter.selectBestBehavior(utilityState, activeBehavior);
            if (selected != BrainBehavior.SURVIVAL && isOverrideOnBackoff(selected, worldTime)) {
                selected = BrainBehavior.ACTIVE_JOB;
            }
            boolean overridden = executeSelectedOverride(selected, bot);
            if (!overridden) {
                recordOverrideFailure(selected, worldTime);
                activeBehavior = BrainBehavior.ACTIVE_JOB;
                boolean ownerTooFarForSupply = isOwnerFarAway(bot, 256.0D);
                boolean canCooperate = !combatWithTargets
                        && !immediateHazard
                        && !ownerTooFarForSupply
                        && context.getTaskConfig().isSquadCooperation();
                boolean handlingExchange = canCooperate && SquadSupplyService.tickActiveExchange(context);
                if (!handlingExchange) {
                    IAutomatonJob current = jobs.get(activeJobId);
                    if (current != null) {
                        current.tick((IAutomatonContext) context);
                    }
                    if (canCooperate && worldTime - lastSquadSupplyScanTick >= SQUAD_SUPPLY_SCAN_INTERVAL_TICKS) {
                        lastSquadSupplyScanTick = worldTime;
                        SquadSupplyService.tickSquadSupply(context);
                    }
                }
            } else {
                overrideFailures[selected.ordinal()] = 0;
                activeBehavior = selected;
                if (context.getCombatTacticsState().lastEngagedTick != worldTime) {
                    CombatJobSupport.cancelBowDraw(bot);
                }
            }
        }
        if (benchDecisionStart != 0L && ranDecisionThisTick) {
            com.x4yi.automata.bench.InGameBenchmarkProfiler.record(
                    com.x4yi.automata.bench.InGameBenchmarkProfiler.Phase.BRAIN_DECISION_AND_JOB,
                    System.nanoTime() - benchDecisionStart);
        }

        if (!dodgingWave
                && !ranDecisionThisTick
                && activeBehavior == BrainBehavior.ACTIVE_JOB
                && !immediateHazard
                && context.getTaskConfig().isSquadCooperation()
                && !(isCombatJob(activeJobId) && hasCombatTargetsInRange(bot))) {
            SquadSupplyService.tickActiveExchange(context);
        }

        BlockPos activeMiningPos = context.getMiningController().getActivePos();
        if (!ranDecisionThisTick && activeMiningPos != null && !context.getMovement().hasActivePath()) {
            if (bot.getDistanceSqToCenter(activeMiningPos) <= MINING_REACH_SQ) {
                context.getMovement().lookSmoothlyAt(
                        activeMiningPos.getX() + 0.5D,
                        activeMiningPos.getY() + 0.5D,
                        activeMiningPos.getZ() + 0.5D);
                if (!MiningProgressController.shouldDeferMiningInAir(bot.onGround, bot.isInWater(), bot.isOnLadder())
                        && context.getMiningController().stepBreakBlock(context.getWorld(), bot, activeMiningPos)) {
                    context.getBlackboard().releaseBlock(entityId, activeMiningPos);
                    decisionCooldownTicks = 0;
                }
            } else {
                context.resetMining();
            }
        }

        if ((bot.onGround || bot.isInWater())
                && !bot.isSneaking()
                && !bot.isOnLadder()) {
            boolean isBuilding = context.getBlackboard().isBuilderClaiming(entityId, worldTime);
            double[] sep = separationScratch;
            if (!isBuilding && context.getBlackboard().computeYieldVector(
                    entityId, bot.posX, bot.posY, bot.posZ, 7.84D, worldTime, sep)) {
                double mag = Math.sqrt(sep[0] * sep[0] + sep[1] * sep[1]);
                if (mag > 1e-4D) {
                    double push = 0.065D;
                    bot.motionX += (sep[0] / mag) * push;
                    bot.motionZ += (sep[1] / mag) * push;
                }
            } else if (!isBuilding && AutomataConfig.allySeparationGap > 0.0D) {
                context.computeAllySeparation(AutomataConfig.allySeparationGap, sep);
                if (sep[0] != 0.0D || sep[1] != 0.0D) {
                    double mag = Math.sqrt(sep[0] * sep[0] + sep[1] * sep[1]);
                    double push = Math.min(0.055D, 0.035D * mag);
                    bot.motionX += (sep[0] / mag) * push;
                    bot.motionZ += (sep[1] / mag) * push;
                }
            }
        }

        boolean survivalLockout = dodgingWave
                || immediateHazard
                || activeBehavior == BrainBehavior.SURVIVAL
                || context.getRevivingTargetId() >= 0;
        boolean combatBehaviorActive = activeBehavior == BrainBehavior.ACTIVE_JOB
                || activeBehavior == BrainBehavior.SELF_DEFENSE
                || (worldTime - context.getCombatTacticsState().lastEngagedTick <= 10L);
        if (!survivalLockout
                && (bot.isEating()
                        || bot.getRevengeTarget() != null
                        || (combatBehaviorActive && (isCombatJob(activeJobId) || context.getCombatTacticsState().activeTargetId >= 0)))) {
            long benchCombatStart = benchBrainStart != 0L ? System.nanoTime() : 0L;
            CombatJobSupport.tickActiveCombat20Hz(context, AutomatonBuiltInJobs.AGGRESSIVE_GUARD.equals(activeJobId));
            if (benchCombatStart != 0L) {
                com.x4yi.automata.bench.InGameBenchmarkProfiler.record(
                        com.x4yi.automata.bench.InGameBenchmarkProfiler.Phase.BRAIN_COMBAT_20HZ,
                        System.nanoTime() - benchCombatStart);
            }
        }

        JobDescriptor activeDescriptor = AutomataAPI.getJob(activeJobId);
        MovementPace descriptorPace = activeDescriptor == null ? MovementPace.NORMAL : activeDescriptor.getMovementPace();
        boolean fastPace = context.isUrgentMovement()
                || bot.getRevengeTarget() != null
                || bot.isEating()
                || descriptorPace == MovementPace.FAST;
        long benchMoveStart = benchBrainStart != 0L ? System.nanoTime() : 0L;
        context.getMovement().setPace(fastPace ? MovementPace.FAST : MovementPace.NORMAL);
        context.getMovement().tick(context.isUrgentMovement());
        if (benchMoveStart != 0L) {
            com.x4yi.automata.bench.InGameBenchmarkProfiler.record(
                    com.x4yi.automata.bench.InGameBenchmarkProfiler.Phase.BRAIN_MOVEMENT_20HZ,
                    System.nanoTime() - benchMoveStart);
        }
        } finally {
            if (benchBrainStart != 0L) {
                com.x4yi.automata.bench.InGameBenchmarkProfiler.record(
                        com.x4yi.automata.bench.InGameBenchmarkProfiler.Phase.BRAIN_TOTAL,
                        System.nanoTime() - benchBrainStart);
            }
        }
    }

    private static boolean isEntityAliveById(net.minecraft.world.World world, int entityId) {
        net.minecraft.entity.Entity target = world.getEntityByID(entityId);
        return target != null && target.isEntityAlive();
    }

    private boolean isOwnerFarAway(EntityAutomaton bot, double thresholdSq) {
        if (bot.getOwnerUuid() == null) {
            return false;
        }
        net.minecraft.entity.player.EntityPlayer owner = context.getWorld().getPlayerEntityByUUID(bot.getOwnerUuid());
        return owner != null && bot.getDistanceSq(owner) > thresholdSq;
    }

    private void syncDesiredJob(EntityAutomaton bot) {
        ResourceLocation desired = context.getTaskConfig().getJobId();
        if (!jobs.containsKey(desired)) {
            desired = AutomatonBuiltInJobs.IDLE;
        }
        if (!desired.equals(activeJobId)) {
            CombatJobSupport.cancelBowDraw(bot);
            bot.clearNeededTool();
            IAutomatonJob previous = jobs.get(activeJobId);
            if (previous != null) {
                previous.onStop((IAutomatonContext) context);
            }
            context.resetMining();
            bot.getChunkLoader().setExtraWorkChunk(null);
            activeJobId = desired;
        }
    }

    private boolean hasCombatTargetsInRange(EntityAutomaton bot) {
        if (bot.getRevengeTarget() != null && bot.getRevengeTarget().isEntityAlive()) {
            return true;
        }
        double radius = Math.max(16.0D, context.getTaskConfig().getCombatRadius());
        return CombatJobSupport.findNearestTarget(context, bot.getPosition(), radius, e -> true) != null;
    }

    private boolean hasActiveRangedEngagement(EntityAutomaton bot) {
        if (!com.x4yi.automata.ai.jobs.RangedCombatController.hasReadyRangedWeapon(bot)) {
            return false;
        }
        if (bot.getRevengeTarget() != null && bot.getRevengeTarget().isEntityAlive()) {
            return true;
        }
        int targetId = context.getCombatTacticsState().activeTargetId;
        if (targetId >= 0) {
            net.minecraft.entity.Entity target = context.getWorld().getEntityByID(targetId);
            if (target instanceof net.minecraft.entity.EntityLivingBase living
                    && living.isEntityAlive()
                    && bot.canEntityBeSeen(living)) {
                return true;
            }
        }
        double radius = Math.max(24.0D, context.getTaskConfig().getCombatRadius());
        return CombatJobSupport.findNearestTarget(context, bot.getPosition(), radius, e -> true) != null;
    }

    boolean isOverrideOnBackoff(BrainBehavior behavior, long now) {
        return behavior != BrainBehavior.ACTIVE_JOB && overrideBackoffUntil[behavior.ordinal()] > now;
    }

    void recordOverrideFailure(BrainBehavior behavior, long now) {
        if (behavior == BrainBehavior.ACTIVE_JOB || behavior == BrainBehavior.SURVIVAL) {
            return;
        }
        int idx = behavior.ordinal();
        if (++overrideFailures[idx] >= OVERRIDE_FAILURE_THRESHOLD) {
            overrideBackoffUntil[idx] = now + OVERRIDE_BACKOFF_TICKS;
            overrideFailures[idx] = 0;
        }
    }

    private static float computeInventoryOccupancy(EntityAutomaton bot) {
        net.minecraftforge.items.IItemHandlerModifiable mainInv = bot.getInventory().getMainInventory();
        int occupiedSlots = 0;
        for (int i = 0; i < mainInv.getSlots(); i++) {
            if (!mainInv.getStackInSlot(i).isEmpty()) {
                occupiedSlots++;
            }
        }
        return (float) LogisticsService.computeOccupancyRatio(occupiedSlots, mainInv.getSlots());
    }

    private void suspendForUnavailableOwner(EntityAutomaton bot) {
        CombatJobSupport.cancelBowDraw(bot);
        bot.clearNeededTool();
        context.stopNavigation();
        context.resetMining();
        if (!AutomatonBuiltInJobs.IDLE.equals(activeJobId)) {
            IAutomatonJob previous = jobs.get(activeJobId);
            if (previous != null) {
                previous.onStop((IAutomatonContext) context);
            }
            activeJobId = AutomatonBuiltInJobs.IDLE;
        }
        activeBehavior = BrainBehavior.ACTIVE_JOB;
        bot.getChunkLoader().releaseAll();
    }

    private boolean executeSelectedOverride(BrainBehavior selected, EntityAutomaton bot) {
        if (SurvivalEvaluator.handleImmediateSelfSurvival(context)) {
            return true;
        }
        return switch (selected) {
            case SURVIVAL -> SurvivalEvaluator.handleLowHealthSurvival(context)
                    || SurvivalEvaluator.handleSquadRevive(context);
            case SELF_DEFENSE -> handleReactiveSelfDefense(bot);
            case LOGISTICS -> LogisticsService.handleLogistics(context);
            case SEEK_LOOT -> InventoryManagerService.trySeekNearbyUsefulDrop(context);
            case ACTIVE_JOB -> false;
        };
    }

    private boolean handleReactiveSelfDefense(EntityAutomaton bot) {
        if (isCombatJob(activeJobId)) {
            return false;
        }
        net.minecraft.entity.EntityLivingBase revenge = bot.getRevengeTarget();
        if (revenge != null && CombatJobSupport.isValidCombatCandidate(context, revenge)) {
            CombatJobSupport.engageTarget(context, revenge, true);
            return true;
        }
        return false;
    }

    public void nudgeUnstick() {
        EntityAutomaton bot = context.getAutomaton();
        if (bot.isDowned()) {
            return;
        }
        CombatJobSupport.cancelBowDraw(bot);
        context.resetMining();
        context.nudgeUnstick();
        IAutomatonJob current = jobs.get(activeJobId);
        if (current != null) {
            current.onStop((IAutomatonContext) context);
            current.tick((IAutomatonContext) context);
        }
    }
}
