package com.x4yi.automata.ai;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityMinecartTNT;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityShulkerBullet;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;

import com.x4yi.automata.compat.ModCompatBridge;
import com.x4yi.automata.entity.EntityAutomaton;

public final class ExplosiveHazardDetector {

    public static final double STATIC_HAZARD_MARGIN_BLOCKS = 1.0D;

    private ExplosiveHazardDetector() {}

    @Nullable
    public static Entity findNearestExplosive(AutomatonAIContext ctx, double radius) {
        return selectNearestExplosive(ctx.getAutomaton(), ctx.getNearbyHazardAndProjectileEntities(radius), radius);
    }

    public static boolean isHazardOrProjectileCandidate(@Nullable Entity entity) {
        if (entity == null || !entity.isEntityAlive()
                || entity instanceof EntityItem
                || entity instanceof net.minecraft.entity.item.EntityXPOrb) {
            return false;
        }
        if (entity instanceof net.minecraft.entity.IProjectile
                || entity instanceof EntityFireball
                || entity instanceof EntityShulkerBullet) {
            return true;
        }
        return isExplosiveHazard(entity)
                || isStaticAreaHazard(entity)
                || ModCompatBridge.isDirectionalGroundWave(entity)
                || ModCompatBridge.isCustomAoeAttacker(entity)
                || ModCompatBridge.isModdedProjectile(entity);
    }

    @Nullable
    public static Entity findNearestExplosive(EntityAutomaton bot, double radius) {
        AxisAlignedBB box = bot.getEntityBoundingBox().grow(radius);
        List<Entity> nearby = bot.world.getEntitiesWithinAABBExcludingEntity(bot, box);
        return selectNearestExplosive(bot, nearby, radius);
    }

    @Nullable
    private static Entity selectNearestExplosive(EntityAutomaton bot, List<Entity> candidates, double radius) {
        double maxDistSq = radius * radius;
        Entity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0, n = candidates.size(); i < n; i++) {
            Entity entity = candidates.get(i);
            if (!isExplosiveHazard(entity)) {
                continue;
            }
            double distSq = bot.getDistanceSq(entity);
            if (distSq <= maxDistSq && distSq < bestDistSq) {
                bestDistSq = distSq;
                best = entity;
            }
        }
        return best;
    }

    public static boolean isExplosiveHazard(@Nullable Entity entity) {
        if (entity == null || !entity.isEntityAlive()) {
            return false;
        }
        if (entity instanceof EntityItem || isStaticAreaHazard(entity)) {
            return false;
        }
        if (entity instanceof EntityTNTPrimed) {
            return true;
        }
        if (entity instanceof EntityMinecartTNT minecart && minecart.isIgnited()) {
            return true;
        }
        if (entity instanceof EntityCreeper creeper) {
            return creeper.getCreeperState() > 0;
        }
        if (ModCompatBridge.isDirectionalGroundWave(entity)
                || ModCompatBridge.isImminentSwellingExplosive(entity)) {
            return true;
        }
        if (entity instanceof EntityFireball || entity instanceof EntityShulkerBullet) {
            return true;
        }
        ResourceLocation key = EntityList.getKey(entity);
        return isModdedExplosiveId(key);
    }

    public static boolean isStaticAreaHazard(@Nullable Entity entity) {
        return ModCompatBridge.isStaticAreaHazard(entity);
    }

    public static boolean computeStaticHazardClearance(
            AutomatonAIContext ctx,
            double botX,
            double botY,
            double botZ,
            double[] outPushXz) {
        outPushXz[0] = 0.0D;
        outPushXz[1] = 0.0D;
        EntityAutomaton bot = ctx.getAutomaton();
        double botHalfW = bot.width * 0.5D;
        List<Entity> nearby = ctx.getNearbyHazardAndProjectileEntities(8.0D);
        boolean active = false;
        for (int i = 0, n = nearby.size(); i < n; i++) {
            Entity entity = nearby.get(i);
            if (!isStaticAreaHazard(entity)) {
                continue;
            }
            if (Math.abs(botY - entity.posY) > Math.max(2.5D, entity.height + 1.5D)) {
                continue;
            }
            double minClearance = (entity.width * 0.5D) + botHalfW + STATIC_HAZARD_MARGIN_BLOCKS;
            if (accumulateStaticHazardRepulsion(
                    botX, botZ, entity.posX, entity.posZ, minClearance, outPushXz)) {
                active = true;
            }
        }
        return active;
    }

    public static boolean accumulateStaticHazardRepulsion(
            double botX,
            double botZ,
            double hazardX,
            double hazardZ,
            double minClearance,
            double[] outPushXz) {
        double dx = botX - hazardX;
        double dz = botZ - hazardZ;
        double distSq = dx * dx + dz * dz;
        if (distSq >= minClearance * minClearance) {
            return false;
        }
        if (distSq < 1.0E-6D) {
            outPushXz[0] += 1.0D;
            return true;
        }
        double dist = Math.sqrt(distSq);
        double strength = Math.max(0.25D, (minClearance - dist) / minClearance + 0.35D);
        outPushXz[0] += (dx / dist) * strength;
        outPushXz[1] += (dz / dist) * strength;
        return true;
    }

    static boolean isModdedExplosiveId(@Nullable ResourceLocation key) {
        if (key == null) {
            return false;
        }
        if (ModCompatBridge.isModdedExplosiveId(key)) {
            return true;
        }
        String path = key.getPath().toLowerCase(Locale.ROOT);
        return path.contains("tnt")
                || path.contains("bomb")
                || path.contains("dynamite")
                || path.contains("grenade")
                || path.contains("c4")
                || path.contains("nuke")
                || path.contains("explosive");
    }
}
