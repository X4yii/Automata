
package com.x4yi.automata.ai.jobs;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.AutomatonJobType;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.entity.EntityAutomaton;

public class DefendJob implements IAutomatonJob {

    @Override
    public AutomatonJobType getType() {
        return AutomatonJobType.DEFEND;
    }

    @Override
    public void tick(AutomatonAIContext ctx) {
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

