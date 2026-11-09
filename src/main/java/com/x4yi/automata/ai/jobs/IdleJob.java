
package com.x4yi.automata.ai.jobs;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.ai.TaskCompletionBehavior;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.entity.EntityAutomaton;

public class IdleJob implements IAutomatonJob {

    private static final double FOLLOW_START_SQ = 9.0D;
    private static final double GOAL_DRIFT_REPLAN_SQ = 9.0D;
    private static final double TOO_CLOSE_SQ = 1.69D;

    private long nextRoamTick;

    @Override
    public ResourceLocation getJobId() {
        return AutomatonBuiltInJobs.IDLE;
    }

    @Override
    public void tick(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        EntityAutomaton bot = ctx.getAutomaton();
        bot.getChunkLoader().setExtraWorkChunk(null);
        if (ctx.getTaskConfig().getCompletionBehavior() != TaskCompletionBehavior.FOLLOW_OWNER) {
            ctx.stopNavigation();
            return;
        }
        if (bot.getOwnerUuid() == null) {
            return;
        }
        EntityPlayer owner = ctx.getWorld().getPlayerEntityByUUID(bot.getOwnerUuid());
        if (owner == null) {
            return;
        }
        double distSq = bot.getDistanceSq(owner);
        long now = ctx.getWorldTime();
        if (ctx.getMovement().hasActivePath()) {
            BlockPos currentGoal = ctx.getActiveGoal();
            if (currentGoal != null
                    && bot.onGround
                    && owner.getDistanceSqToCenter(currentGoal) > GOAL_DRIFT_REPLAN_SQ) {
                BlockPos sideTarget = pickSideSpotNearOwner(ctx, owner, now);
                if (sideTarget != null) {
                    ctx.requestPathTo(sideTarget, false);
                }
            }
            return;
        }

        if (distSq > FOLLOW_START_SQ) {
            int nodeBudget = Math.abs(owner.posY - bot.posY) >= 4.0D ? 16384 : 8192;
            BlockPos sideTarget = pickSideSpotNearOwner(ctx, owner, now);
            if (sideTarget != null) {
                ctx.requestPathTo(sideTarget, false, nodeBudget);
            }
            return;
        }

        ctx.getMovement().lookSmoothlyAt(owner.posX, owner.posY + owner.getEyeHeight(), owner.posZ);
        if (distSq < TOO_CLOSE_SQ || now >= nextRoamTick) {
            BlockPos roamTarget = pickRoamSpotAround(ctx.getWorld(), bot, owner);
            if (roamTarget != null) {
                ctx.requestPathTo(roamTarget, false);
            }
            nextRoamTick = now + 50L + bot.getRNG().nextInt(36);
        }
    }

    private static BlockPos pickSideSpotNearOwner(AutomatonAIContext ctx, EntityPlayer owner, long now) {
        WorldServer world = ctx.getWorld();
        EntityAutomaton bot = ctx.getAutomaton();
        BlockPos center = owner.getPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int horizSq = dx * dx + dz * dz;
                if (horizSq < 2 || horizSq > 9) {
                    continue;
                }
                for (int dy = 2; dy >= -3; dy--) {
                    BlockPos feet = center.add(dx, dy, dz);
                    if (!isStandable(world, feet) || ctx.isGoalOnCooldown(feet, now)) {
                        continue;
                    }
                    double ownerDist = Math.abs(Math.sqrt(horizSq) - 2.0D);
                    double botDist = bot.getDistanceSqToCenter(feet);
                    double score = ownerDist * 6.0D + botDist * 0.25D + Math.abs(dy) * 1.5D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = feet;
                    }
                    break;
                }
            }
        }
        if (best != null) {
            return best;
        }
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos feet = center.up(dy);
            if (isStandable(world, feet) && !ctx.isGoalOnCooldown(feet, now)) {
                return feet;
            }
        }
        return center;
    }

    private static boolean isStandable(WorldServer world, BlockPos feet) {
        return world.isBlockLoaded(feet)
                && world.getBlockState(feet.down()).getMaterial().isSolid()
                && world.isAirBlock(feet)
                && world.isAirBlock(feet.up());
    }

    private static BlockPos pickRoamSpotAround(WorldServer world, EntityAutomaton bot, EntityPlayer owner) {
        BlockPos center = owner.getPosition();
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = bot.getRNG().nextDouble() * Math.PI * 2.0D;
            double radius = 1.4D + bot.getRNG().nextDouble() * 1.5D;
            int tx = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int tz = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos feet = new BlockPos(tx, center.getY() + dy, tz);
                if (isStandable(world, feet)) {
                    return feet;
                }
            }
        }
        return null;
    }
}

