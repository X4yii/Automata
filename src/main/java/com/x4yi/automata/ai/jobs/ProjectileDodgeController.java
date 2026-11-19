package com.x4yi.automata.ai.jobs;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityShulkerBullet;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.item.ItemShield;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.compat.ModCompatBridge;
import com.x4yi.automata.entity.EntityAutomaton;

public final class ProjectileDodgeController {

    private static final double SCAN_RADIUS = 10.0D;
    private static final double DODGE_CORRIDOR_RADIUS_SQ = 0.7225D;
    private static final double VERTICAL_MARGIN = 0.40D;
    private static final double MAX_IMPACT_HORIZON_TICKS = 20.0D;
    private static final double SHIELD_BRACE_DIST_SQ = 12.25D;

    private ProjectileDodgeController() {}

    public static boolean tryDodgeIncomingProjectile(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            @Nullable EntityLivingBase focusTarget) {
        List<Entity> nearby = ctx.getNearbyHazardAndProjectileEntities(SCAN_RADIUS);

        Entity bestProj = null;
        double[] bestDodge = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity entity : nearby) {
            if (!isThreateningProjectile(entity, bot) || !bot.canEntityBeSeen(entity)) {
                continue;
            }
            double dSq = bot.getDistanceSq(entity);
            if (dSq >= bestDistSq) {
                continue;
            }
            double[] dodge = computeLateralDodgeVector3D(
                    bot.posX, bot.posY, bot.posZ,
                    bot.width * 0.5D, bot.height,
                    entity.posX, entity.posY + entity.height * 0.5D, entity.posZ,
                    entity.motionX, entity.motionY, entity.motionZ);
            if (dodge != null) {
                bestDistSq = dSq;
                bestProj = entity;
                bestDodge = dodge;
            }
        }

        if (bestProj == null || bestDodge == null) {
            return false;
        }

        if (bestDistSq <= SHIELD_BRACE_DIST_SQ
                && bot.getHeldItemOffhand().getItem() instanceof ItemShield
                && !bot.isHandActive()) {
            bot.setActiveHand(EnumHand.OFF_HAND);
        }

        double nx = bestDodge[0];
        double nz = bestDodge[1];
        if (!isStepSafe(bot, bot.posX + nx * 1.6D, bot.posZ + nz * 1.6D)
                && isStepSafe(bot, bot.posX - nx * 1.6D, bot.posZ - nz * 1.6D)) {
            nx = -nx;
            nz = -nz;
        }

        ctx.stopNavigation();
        if (focusTarget != null) {
            ctx.getMovement().lookSmoothlyAt(focusTarget.posX, focusTarget.posY + focusTarget.getEyeHeight(), focusTarget.posZ);
            float yawRad = bot.rotationYaw * 0.017453292F;
            double fwdX = -Math.sin(yawRad);
            double fwdZ = Math.cos(yawRad);
            double rightX = Math.cos(yawRad);
            double rightZ = Math.sin(yawRad);
            float forward = (float) (nx * fwdX + nz * fwdZ);
            float strafe = (float) (nx * rightX + nz * rightZ);
            bot.setSprinting(bot.getHunger() > 6.0F);
            ctx.getMovement().applyDirectStrafe(forward, strafe, 1.20F);
        } else {
            ctx.getMovement().moveDirectlyToward(bot.posX + nx * 2.2D, bot.posZ + nz * 2.2D, 1.20F, true);
        }
        return true;
    }

    @Nullable
    public static double[] computeLateralDodgeVector(
            double botX,
            double botZ,
            double projX,
            double projZ,
            double velX,
            double velZ) {
        return computeLateralDodgeVector3D(
                botX, 0.0D, botZ,
                0.30D, 1.8D,
                projX, 0.9D, projZ,
                velX, 0.0D, velZ);
    }

    @Nullable
    public static double[] computeLateralDodgeVector3D(
            double botX,
            double botY,
            double botZ,
            double botHalfWidth,
            double botHeight,
            double projX,
            double projY,
            double projZ,
            double velX,
            double velY,
            double velZ) {
        double speedSq3 = velX * velX + velY * velY + velZ * velZ;
        double horizSpeedSq = velX * velX + velZ * velZ;
        if (speedSq3 < 1e-4D || horizSpeedSq < 1e-5D) {
            return null;
        }
        double botCenterY = botY + botHeight * 0.5D;
        double toBotX = botX - projX;
        double toBotY = botCenterY - projY;
        double toBotZ = botZ - projZ;
        double dot3 = toBotX * velX + toBotY * velY + toBotZ * velZ;
        if (dot3 <= 0.0D) {
            return null;
        }
        double t = dot3 / speedSq3;
        if (t > MAX_IMPACT_HORIZON_TICKS) {
            return null;
        }
        double closestX = projX + velX * t;
        double closestY = projY + velY * t;
        double closestZ = projZ + velZ * t;
        if (closestY < botY - VERTICAL_MARGIN || closestY > botY + botHeight + VERTICAL_MARGIN) {
            return null;
        }
        double offX = botX - closestX;
        double offZ = botZ - closestZ;
        double perpDistSq = offX * offX + offZ * offZ;
        double hitRadius = Math.max(0.50D, botHalfWidth + 0.55D);
        if (perpDistSq > Math.min(DODGE_CORRIDOR_RADIUS_SQ, hitRadius * hitRadius)) {
            return null;
        }

        double horizSpeed = Math.sqrt(horizSpeedSq);
        double dirX = velX / horizSpeed;
        double dirZ = velZ / horizSpeed;
        double perpX = -dirZ;
        double perpZ = dirX;
        if (offX * perpX + offZ * perpZ < 0.0D) {
            perpX = -perpX;
            perpZ = -perpZ;
        }
        return new double[] {perpX, perpZ};
    }

    public static boolean isThreateningProjectile(@Nullable Entity entity, EntityAutomaton bot) {
        if (entity == null || !entity.isEntityAlive() || entity.onGround) {
            return false;
        }
        if (entity instanceof EntityArrow arrow) {
            return !isFriendlyShooter(arrow.shootingEntity, bot);
        }
        if (entity instanceof EntityFireball fireball) {
            return !isFriendlyShooter(fireball.shootingEntity, bot);
        }
        if (entity instanceof EntityThrowable throwable) {
            return !isFriendlyShooter(throwable.getThrower(), bot);
        }
        if (entity instanceof EntityShulkerBullet || entity instanceof IProjectile || ModCompatBridge.isModdedProjectile(entity)) {
            return true;
        }
        return false;
    }

    private static boolean isFriendlyShooter(@Nullable Entity shooter, EntityAutomaton bot) {
        if (shooter == null) {
            return false;
        }
        if (shooter == bot) {
            return true;
        }
        if (shooter instanceof EntityAutomaton ally && bot.isAlliedWith(ally)) {
            return true;
        }
        return bot.getOwnerUuid() != null && bot.getOwnerUuid().equals(shooter.getUniqueID());
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

