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
import com.x4yi.automata.movement.HumanMovementController.MovementMood;
import com.x4yi.automata.world.MiningProgressController;

public class AutomatonBrain {

    private static final int FAST_DECISION_INTERVAL_TICKS = 2;
    private static final int STANDARD_DECISION_INTERVAL_TICKS = 3;
    private static final int INVENTORY_MANAGE_INTERVAL_TICKS = 20;
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

    public AutomatonBrain(EntityAutomaton automaton) {
        this.context = new AutomatonAIContext(automaton);
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
        if (descriptor != null && descriptor.getDecisionIntervalTicks() > 0) {
            return descriptor.getDecisionIntervalTicks();
        }
        if (isCombatJob(activeJobId)
                || context.isUrgentMovement()
                || context.getMiningController().isMining()) {
            return FAST_DECISION_INTERVAL_TICKS;
        }
        return STANDARD_DECISION_INTERVAL_TICKS;
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

        boolean refreshOccupancy = cachedOccupancy < 0.0F || activeBehavior == BrainBehavior.LOGISTICS;
        if (BudgetedTickScheduler.shouldRun(worldTime, entityId, INVENTORY_MANAGE_INTERVAL_TICKS, server)) {
            InventoryManagerService.tickInventoryManagement(context);
            refreshOccupancy = true;
        }
        if (refreshOccupancy) {
            cachedOccupancy = computeInventoryOccupancy(bot);
        }

        TacticalUtilityController.tryEmergencyPearlRescue(context);
        TacticalUtilityController.tryDrinkConsumable(context);
        if (SurvivalEvaluator.tickVoidRescue20Hz(context)) {
            return;
        }
        SurvivalEvaluator.tickLavaEscape20Hz(context);

        boolean immediateHazard = SurvivalEvaluator.isImmediateHazard(bot);
        if (immediateHazard) {
            decisionCooldownTicks = 0;
        }

        boolean ranDecisionThisTick = false;
        if (--decisionCooldownTicks <= 0) {
            ranDecisionThisTick = true;
            decisionCooldownTicks = computeDecisionIntervalTicks();
            IAutomatonJob activeJob = jobs.get(activeJobId);
            boolean committedWork = (activeJob != null && activeJob.hasCommittedWork(context))
                    || context.getMiningController().isMining();
            float occupancy = cachedOccupancy;
            boolean lowHealthOrWither = bot.getHealth() / Math.max(1.0F, bot.getMaxHealth()) < 0.35F
                    || bot.isPotionActive(net.minecraft.init.MobEffects.WITHER);
            boolean activeRangedCombat = isCombatJob(activeJobId) && hasActiveRangedEngagement(bot);
            UtilityState utilityState = UtilityState.builder()
                    .inHazardOrExplosive(immediateHazard)
                    .lowHealthNeedsSurvival(lowHealthOrWither)
                    .allyNeedsRevive(!activeRangedCombat
                            && context.getTaskConfig().isSquadCooperation()
                            && context.getBlackboard().findNearestDowned(entityId, bot.getPosition(), worldTime) != null)
                    .hasThreatOrRevenge(bot.getRevengeTarget() != null && bot.getRevengeTarget().isEntityAlive())
                    .safeLootAvailable(!activeRangedCombat
                            && context.getTaskConfig().isCanPickUpLoot()
                            && context.getTaskConfig().isSearchLoot())
                    .logisticsReady(context.getTaskConfig().getDepositChest() != null && occupancy >= 0.85F)
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
                IAutomatonJob current = jobs.get(activeJobId);
                if (current != null) {
                    current.tick((IAutomatonContext) context);
                }
            } else {
                overrideFailures[selected.ordinal()] = 0;
                activeBehavior = selected;
                CombatJobSupport.cancelBowDraw(bot);
            }
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
                }
            } else {
                context.resetMining();
            }
        }

        if ((bot.onGround || bot.isInWater())
                && !bot.isSneaking()
                && !bot.isOnLadder()
                && AutomataConfig.allySeparationGap > 0.0D) {
            double[] sep = separationScratch;
            context.computeAllySeparation(AutomataConfig.allySeparationGap, sep);
            if (sep[0] != 0.0D || sep[1] != 0.0D) {
                double mag = Math.sqrt(sep[0] * sep[0] + sep[1] * sep[1]);
                double push = Math.min(0.055D, 0.035D * mag);
                bot.motionX += (sep[0] / mag) * push;
                bot.motionZ += (sep[1] / mag) * push;
            }
        }

        boolean combatBehaviorActive = activeBehavior == BrainBehavior.ACTIVE_JOB
                || activeBehavior == BrainBehavior.SELF_DEFENSE;
        if (bot.isEating()
                || bot.getRevengeTarget() != null
                || (combatBehaviorActive && isCombatJob(activeJobId))) {
            CombatJobSupport.tickActiveCombat20Hz(context, AutomatonBuiltInJobs.CRAZY_DEFEND.equals(activeJobId));
        }

        JobDescriptor activeDescriptor = AutomataAPI.getJob(activeJobId);
        MovementMood descriptorMood = activeDescriptor == null ? MovementMood.CHILL : activeDescriptor.getMovementMood();
        boolean tryhard = context.isUrgentMovement()
                || bot.getRevengeTarget() != null
                || bot.isEating()
                || descriptorMood == MovementMood.TRYHARD;
        context.getMovement().setMood(tryhard ? MovementMood.TRYHARD : MovementMood.CHILL);
        context.getMovement().tick(context.isUrgentMovement());
    }

    private boolean hasActiveRangedEngagement(EntityAutomaton bot) {
        net.minecraft.item.ItemStack held = bot.getHeldItemMainhand();
        boolean ranged = EquipmentSelector.isBowItem(held)
                || com.x4yi.automata.ai.jobs.WeaponCombatProfile.resolve(held).isRangedSkirmishStyle();
        if (!ranged) {
            return false;
        }
        int targetId = context.getCombatTacticsState().activeTargetId;
        if (targetId < 0) {
            return false;
        }
        net.minecraft.entity.Entity target = context.getWorld().getEntityByID(targetId);
        return target instanceof net.minecraft.entity.EntityLivingBase living
                && living.isEntityAlive()
                && bot.canEntityBeSeen(living);
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
        if (SurvivalEvaluator.handleSurvival(context)) {
            return true;
        }
        return switch (selected) {
            case SURVIVAL -> false;
            case SELF_DEFENSE -> handleReactiveSelfDefense(bot)
                    || InventoryManagerService.trySeekNearbyUsefulDrop(context);
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
