package com.x4yi.automata.ai.jobs;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.coordination.SquadBlackboard;
import com.x4yi.automata.entity.EntityAutomaton;

public class AggressiveGuardJob implements IAutomatonJob {

    @Override
    public ResourceLocation getJobId() {
        return AutomatonBuiltInJobs.AGGRESSIVE_GUARD;
    }

    @Override
    public boolean hasCommittedWork(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        EntityAutomaton bot = ctx.getAutomaton();
        if (ctx.getCombatTacticsState().activeTargetId >= 0
                || (ctx.getWorldTime() - ctx.getCombatTacticsState().lastEngagedTick <= 40L)
                || bot.getRevengeTarget() != null) {
            return true;
        }
        if (bot.getOwnerUuid() != null) {
            EntityPlayer owner = ctx.getWorld().getPlayerEntityByUUID(bot.getOwnerUuid());
            if (owner != null && bot.getDistanceSq(owner) > 144.0D) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void tick(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        EntityAutomaton bot = ctx.getAutomaton();
        EntityPlayer owner = bot.getOwnerUuid() != null
                ? ctx.getWorld().getPlayerEntityByUUID(bot.getOwnerUuid())
                : null;

        BlockPos anchor = owner != null ? owner.getPosition() : bot.getPosition();
        double radius = ctx.getTaskConfig().getCombatRadius();

        EntityLivingBase ownerAttacker = owner != null ? owner.getRevengeTarget() : null;
        if (ownerAttacker != null && CombatJobSupport.isValidCombatCandidate(ctx, ownerAttacker)
                && bot.getDistanceSq(ownerAttacker) <= radius * radius) {
            if (ctx.getTaskConfig().isSquadCooperation()) {
                ctx.getBlackboard().reportPriorityTarget(
                        bot.getEntityId(), ownerAttacker.getEntityId(), 0.25D, ctx.getWorldTime(), 40L);
            }
            CombatJobSupport.engageTarget(ctx, ownerAttacker, true);
            return;
        }

        EntityLivingBase target = CombatJobSupport.findNearestTarget(ctx, anchor, radius, candidate -> true);
        if (target != null) {
            CombatJobSupport.engageTarget(ctx, target, true);
        } else {
            SquadBlackboard.ReviveAlert reviveAlert = ctx.getTaskConfig().isSquadCooperation()
                    ? ctx.getBlackboard().findNearestActiveReviveAlert(
                            bot.getEntityId(), bot.getPosition(), 2304.0D, ctx.getWorldTime())
                    : null;
            if (reviveAlert != null) {
                CombatJobSupport.escortOwnerOrAnchor(ctx, null, reviveAlert.pos(), true);
            } else {
                CombatJobSupport.escortOwnerOrAnchor(ctx, owner, ctx.getTaskConfig().getPos1(), true);
            }
        }
    }
}
