package com.x4yi.automata.ai;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityMinecartTNT;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityShulkerBullet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;

import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Detects imminent explosive hazards across vanilla and modded entities
 * (primed TNT, TNT minecarts, swelling creepers, fireballs/wither skulls, and modded bombs).
 */
public final class ExplosiveHazardDetector {

    private ExplosiveHazardDetector() {}

    @Nullable
    public static Entity findNearestExplosive(EntityAutomaton bot, double radius) {
        AxisAlignedBB box = bot.getEntityBoundingBox().grow(radius);
        List<Entity> nearby = bot.world.getEntitiesWithinAABBExcludingEntity(bot, box);
        Entity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity entity : nearby) {
            if (!isExplosiveHazard(entity)) {
                continue;
            }
            double distSq = bot.getDistanceSq(entity);
            if (distSq < bestDistSq) {
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
        if (entity instanceof EntityTNTPrimed) {
            return true;
        }
        if (entity instanceof EntityMinecartTNT minecart && minecart.isIgnited()) {
            return true;
        }
        if (entity instanceof EntityCreeper creeper) {
            return creeper.getCreeperState() > 0;
        }
        if (entity instanceof EntityFireball || entity instanceof EntityShulkerBullet) {
            return true;
        }
        if (entity instanceof EntityItem || entity instanceof EntityLivingBase) {
            return false;
        }
        ResourceLocation key = EntityList.getKey(entity);
        return isModdedExplosiveId(key);
    }

    static boolean isModdedExplosiveId(@Nullable ResourceLocation key) {
        if (key == null) {
            return false;
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

