
package com.x4yi.automata.ai.jobs;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.AutomatonJobType;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.entity.EntityAutomaton;

public class CrazyDefendJob implements IAutomatonJob {

    @Override
    public AutomatonJobType getType() {
        return AutomatonJobType.CRAZY_DEFEND;
    }

    @Override
    public void tick(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        EntityPlayer owner = bot.getOwnerUuid() != null
                ? ctx.getWorld().getPlayerEntityByUUID(bot.getOwnerUuid())
                : null;

        BlockPos anchor = owner != null ? owner.getPosition() : bot.getPosition();
        double radius = ctx.getTaskConfig().getCombatRadius();

        EntityLivingBase target = CombatJobSupport.findNearestTarget(ctx, anchor, radius, candidate -> true);
        if (target != null) {
            CombatJobSupport.engageTarget(ctx, target, true);
        } else {
            CombatJobSupport.escortOwnerOrAnchor(ctx, owner, ctx.getTaskConfig().getPos1(), true);
        }
    }
}

