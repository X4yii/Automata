package com.x4yi.automata.ai.jobs;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.compat.ModCompatBridge;
import com.x4yi.automata.coordination.SquadBlackboard;
import com.x4yi.automata.coordination.SquadBlackboard.HazardZoneAlert;
import com.x4yi.automata.entity.EntityAutomaton;

public final class WavePredictionDodgeController {

    private static final double WAVE_SCAN_RADIUS = 12.0D;
    private static final double WINDUP_SCAN_RADIUS_SQ = 64.0D;
    private static final double LOOKAHEAD_DISTANCE = 14.0D;

    private WavePredictionDodgeController() {}

    public static boolean tickWaveAndAoeDodge20Hz(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (bot.isDowned() || bot.isDead) {
            return false;
        }
        long now = ctx.getWorldTime();
        SquadBlackboard board = ctx.getBlackboard();
        List<Entity> nearby = ctx.getNearbyHazardAndProjectileEntities(WAVE_SCAN_RADIUS);

        Entity bestWave = null;
        double[] bestEscape = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int i = 0, n = nearby.size(); i < n; i++) {
            Entity entity = nearby.get(i);
            if (!entity.isEntityAlive()) {
                continue;
            }
            if (ModCompatBridge.isDirectionalGroundWave(entity)) {
                double dSq = bot.getDistanceSq(entity);
                double[] dir = resolveHorizontalDirection(entity, bot);
                double corridorRadius = Math.max(1.8D, entity.width * 0.5D + bot.width * 0.5D + 1.25D);
                board.reportHazardZone(
                        entity.getEntityId(),
                        entity.posX,
                        entity.posZ,
                        dir[0],
                        dir[1],
                        corridorRadius,
                        now,
                        15L);

                double[] escape = computeWaveEscapeVector(
                        bot.posX,
                        bot.posZ,
                        entity.posX,
                        entity.posZ,
                        dir[0],
                        dir[1],
                        corridorRadius,
                        bot.getEntityId());
                if (escape != null && dSq < bestDistSq) {
                    bestDistSq = dSq;
                    bestWave = entity;
                    bestEscape = escape;
                }
            }
        }

        if (bestWave != null && bestEscape != null) {
            applySprintEscape(ctx, bot, bestEscape[0], bestEscape[1]);
            return true;
        }

        for (HazardZoneAlert alert : board.getActiveHazardZones(now)) {
            double[] escape = computeWaveEscapeVector(
                    bot.posX,
                    bot.posZ,
                    alert.posX(),
                    alert.posZ(),
                    alert.dirX(),
                    alert.dirZ(),
                    alert.radius(),
                    bot.getEntityId());
            if (escape != null) {
                applySprintEscape(ctx, bot, escape[0], escape[1]);
                return true;
            }
        }

        for (int i = 0, n = nearby.size(); i < n; i++) {
            Entity entity = nearby.get(i);
            if (!entity.isEntityAlive() || !ModCompatBridge.isCustomAoeAttacker(entity)) {
                continue;
            }
            double dSq = bot.getDistanceSq(entity);
            if (dSq <= WINDUP_SCAN_RADIUS_SQ && ModCompatBridge.getCustomAoeWindupProgress(entity) > 0.0F) {
                Vec3d look = entity.getLookVec();
                double[] escape = computeWaveEscapeVector(
                        bot.posX,
                        bot.posZ,
                        entity.posX,
                        entity.posZ,
                        look.x,
                        look.z,
                        2.6D,
                        bot.getEntityId());
                if (escape != null) {
                    applySprintEscape(ctx, bot, escape[0], escape[1]);
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    public static double[] computeWaveEscapeVector(
            double botX,
            double botZ,
            double waveX,
            double waveZ,
            double dirX,
            double dirZ,
            double corridorRadius,
            int botEntityId) {
        double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLen < 1e-4D) {
            return null;
        }
        double nx = dirX / dirLen;
        double nz = dirZ / dirLen;

        double toBotX = botX - waveX;
        double toBotZ = botZ - waveZ;
        double forwardProj = toBotX * nx + toBotZ * nz;
        if (forwardProj < -1.5D || forwardProj > LOOKAHEAD_DISTANCE) {
            return null;
        }

        double closestX = waveX + nx * Math.max(0.0D, forwardProj);
        double closestZ = waveZ + nz * Math.max(0.0D, forwardProj);
        double offX = botX - closestX;
        double offZ = botZ - closestZ;
        double perpDistSq = offX * offX + offZ * offZ;
        if (perpDistSq > corridorRadius * corridorRadius) {
            return null;
        }

        double perpX = -nz;
        double perpZ = nx;
        double sideSign = offX * perpX + offZ * perpZ;
        if (Math.abs(sideSign) < 0.20D) {
            sideSign = (botEntityId & 1) == 0 ? 1.0D : -1.0D;
        }
        if (sideSign < 0.0D) {
            perpX = -perpX;
            perpZ = -perpZ;
        }

        double escX = perpX * 0.92D - nx * 0.39D;
        double escZ = perpZ * 0.92D - nz * 0.39D;
        double escLen = Math.sqrt(escX * escX + escZ * escZ);
        return escLen > 1e-4D ? new double[] {escX / escLen, escZ / escLen} : new double[] {perpX, perpZ};
    }

    private static double[] resolveHorizontalDirection(Entity wave, EntityAutomaton bot) {
        double mx = wave.motionX;
        double mz = wave.motionZ;
        if (mx * mx + mz * mz > 9e-4D) {
            return new double[] {mx, mz};
        }
        double dx = wave.posX - wave.prevPosX;
        double dz = wave.posZ - wave.prevPosZ;
        if (dx * dx + dz * dz > 9e-4D) {
            return new double[] {dx, dz};
        }
        Vec3d look = wave.getLookVec();
        if (look.x * look.x + look.z * look.z > 1e-4D) {
            return new double[] {look.x, look.z};
        }
        return new double[] {bot.posX - wave.posX, bot.posZ - wave.posZ};
    }

    private static void applySprintEscape(AutomatonAIContext ctx, EntityAutomaton bot, double dirX, double dirZ) {
        double nx = dirX;
        double nz = dirZ;
        if (!isStepSafe(bot, bot.posX + nx * 2.0D, bot.posZ + nz * 2.0D)
                && isStepSafe(bot, bot.posX - nx * 2.0D, bot.posZ - nz * 2.0D)) {
            nx = -nx;
            nz = -nz;
        }
        ctx.stopNavigation();
        ctx.setUrgentMovement(true);
        if (bot.isEating()) {
            bot.cancelEating();
        } else if (bot.isHandActive()) {
            bot.resetActiveHand();
        }
        ctx.getMovement().moveDirectlyToward(bot.posX + nx * 3.5D, bot.posZ + nz * 3.5D, 1.30F, true);
    }

    private static boolean isStepSafe(EntityAutomaton bot, double x, double z) {
        BlockPos feet = new BlockPos(x, bot.posY, z);
        BlockPos below = feet.down();
        if (bot.world.getBlockState(below).getMaterial() == Material.LAVA
                || bot.world.getBlockState(feet).getMaterial() == Material.LAVA) {
            return false;
        }
        return bot.world.getBlockState(below).getMaterial().isSolid()
                || bot.world.getBlockState(below.down()).getMaterial().isSolid();
    }
}

