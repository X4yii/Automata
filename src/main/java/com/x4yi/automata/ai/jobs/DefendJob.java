
package com.x4yi.automata.ai.jobs;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.entity.EntityAutomaton;

public class DefendJob implements IAutomatonJob {

    @Override
    public ResourceLocation getJobId() {
        return AutomatonBuiltInJobs.DEFEND;
    }

    @Override
    public void tick(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        EntityAutomaton bot = ctx.getAutomaton();
        EntityPlayer owner = bot.getOwnerUuid() != null
                ? ctx.getWorld().getPlayerEntityByUUID(bot.getOwnerUuid())
                : null;

        BlockPos anchor = owner != null
                ? owner.getPosition()
                : (ctx.getTaskConfig().getPos1() != null ? ctx.getTaskConfig().getPos1() : bot.getPosition());
        double radius = ctx.getTaskConfig().getCombatRadius();

        EntityLivingBase ownerAttacker = owner != null ? owner.getRevengeTarget() : null;
        if (ownerAttacker != null && CombatJobSupport.isValidCombatCandidate(ctx, ownerAttacker)
                && bot.getDistanceSq(ownerAttacker) <= radius * radius) {
            CombatJobSupport.engageTarget(ctx, ownerAttacker, false);
            return;
        }

        EntityLivingBase target = CombatJobSupport.findNearestTarget(
                ctx, anchor, radius, candidate -> candidate instanceof IMob || candidate == bot.getRevengeTarget());
        if (target != null) {
            CombatJobSupport.engageTarget(ctx, target, false);
        } else {
            CombatJobSupport.escortOwnerOrAnchor(ctx, owner, ctx.getTaskConfig().getPos1(), false);
        }
    }
}

