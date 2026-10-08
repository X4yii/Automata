package com.x4yi.automata.ai;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.ai.UtilityBrainArbiter.BrainBehavior;
import com.x4yi.automata.ai.UtilityBrainArbiter.UtilityState;
import com.x4yi.automata.ai.jobs.CombatJobSupport;
import com.x4yi.automata.ai.jobs.MineJob;
import com.x4yi.automata.ai.jobs.TacticalUtilityController;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.api.JobDescriptor;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.movement.HumanMovementController.MovementMood;
import com.x4yi.automata.world.MiningProgressController;

public class AutomatonBrain {

    private static final int FAST_DECISION_INTERVAL_TICKS = 2;
    private static final int STANDARD_DECISION_INTERVAL_TICKS = 3;
    private static final int INVENTORY_MANAGE_INTERVAL_TICKS = 20;
    private static final int BLACKBOARD_PRUNE_INTERVAL = 100;
    private static final double MINING_REACH_SQ = 20.25D;

    private final AutomatonAIContext context;
    private final Map<ResourceLocation, IAutomatonJob> jobs = new HashMap<>();
    private ResourceLocation activeJobId = AutomatonBuiltInJobs.IDLE;
    private BrainBehavior activeBehavior = BrainBehavior.ACTIVE_JOB;
    private int decisionCooldownTicks;

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
        if (isCombatJob(activeJobId)
                || context.isUrgentMovement()
                || context.getMiningController().isMining()) {
            return FAST_DECISION_INTERVAL_TICKS;
        }
        return STANDARD_DECISION_INTERVAL_TICKS;
    }

    private boolean isCombatJob(ResourceLocation jobId) {
        return AutomatonBuiltInJobs.DEFEND.equals(jobId)
                || AutomatonBuiltInJobs.CRAZY_DEFEND.equals(jobId);
    }

    public void tickServer() {
        EntityAutomaton bot = context.getAutomaton();
        if (bot.isDowned() || bot.getOwnerUuid() == null) {
            return;
        }

        context.pollPendingPath();

        long worldTime = context.getWorldTime();
        int entityId = bot.getEntityId();

        if (BudgetedTickScheduler.shouldRun(worldTime, entityId, BLACKBOARD_PRUNE_INTERVAL)) {
            context.getBlackboard().pruneExpired(worldTime);
        }

        if (BudgetedTickScheduler.shouldRun(worldTime, entityId, INVENTORY_MANAGE_INTERVAL_TICKS)) {
            InventoryManagerService.tickInventoryManagement(context);
        }

        TacticalUtilityController.tryEmergencyPearlRescue(context);
        SurvivalEvaluator.tickLavaEscape20Hz(context);

        boolean ranDecisionThisTick = false;
        if (--decisionCooldownTicks <= 0) {
            ranDecisionThisTick = true;
            decisionCooldownTicks = computeDecisionIntervalTicks();
            boolean committedWork = (jobs.get(activeJobId) instanceof MineJob mineJob && mineJob.hasActiveCommittedWork())
                    || context.getMiningController().isMining();
            net.minecraftforge.items.IItemHandlerModifiable mainInv = bot.getInventory().getMainInventory();
            int occupiedSlots = 0;
            for (int i = 0; i < mainInv.getSlots(); i++) {
                if (!mainInv.getStackInSlot(i).isEmpty()) {
                    occupiedSlots++;
                }
            }
            float occupancy = (float) LogisticsService.computeOccupancyRatio(occupiedSlots, mainInv.getSlots());
            UtilityState utilityState = new UtilityState(
                    bot.isInLava() || bot.isBurning(),
                    bot.getHealth() / Math.max(1.0F, bot.getMaxHealth()) < 0.35F,
                    context.getTaskConfig().isSquadCooperation()
                            && context.getBlackboard().findNearestDowned(entityId, bot.getPosition()) != null,
                    bot.getRevengeTarget() != null && bot.getRevengeTarget().isEntityAlive(),
                    context.getTaskConfig().isCanPickUpLoot() && context.getTaskConfig().isSearchLoot(),
                    context.getTaskConfig().getDepositChest() != null && occupancy >= 0.85F,
                    occupancy,
                    committedWork);

            BrainBehavior selected = UtilityBrainArbiter.selectBestBehavior(utilityState, activeBehavior);
            boolean overridden = executeSelectedOverride(selected, bot);
            if (!overridden) {
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

        if (bot.isEating()
                || bot.getRevengeTarget() != null
                || isCombatJob(activeJobId)) {
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
