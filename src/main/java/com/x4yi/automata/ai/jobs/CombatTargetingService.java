package com.x4yi.automata.ai.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IRangedAttackMob;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.monster.EntityPolarBear;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.entity.EntityAutomaton;

final class CombatTargetingService {

    private static final double ALLY_PROTECT_RADIUS_SQ = 30.25D;
    private static final double HEALTHY_ALLY_PROTECT_RADIUS_SQ = 12.25D;
    private static final double CREEPER_PRIORITY_RADIUS_SQ = 64.0D;
    private static final double ALLY_PROTECT_WEIGHT = 0.35D;
    private static final double HEALTHY_ALLY_PROTECT_WEIGHT = 0.65D;
    private static final double RANGED_THREAT_WEIGHT = 0.60D;
    private static final double PRIMED_CREEPER_WEIGHT = 0.15D;

    private CombatTargetingService() {}

    @Nullable
    static EntityLivingBase findNearestTarget(
            AutomatonAIContext ctx,
            BlockPos center,
            double radius,
            Predicate<EntityLivingBase> extraFilter) {
        EntityAutomaton bot = ctx.getAutomaton();
        List<EntityLivingBase> candidates;
        if (radius <= 16.0D && center.distanceSq(bot.posX, bot.posY, bot.posZ) <= 4.0D) {
            candidates = ctx.getNearbyLivingEntities(radius);
        } else {
            AxisAlignedBB box = new AxisAlignedBB(center).grow(radius);
            candidates = bot.world.getEntitiesWithinAABB(EntityLivingBase.class, box);
        }
        boolean squadCoop = ctx.getTaskConfig().isSquadCooperation();
        long now = ctx.getWorldTime();

        List<EntityAutomaton> vulnerableAllies = new ArrayList<>();
        List<EntityAutomaton> healthyAllies = new ArrayList<>();
        if (squadCoop && bot.getOwnerUuid() != null) {
            for (EntityLivingBase candidate : candidates) {
                if (candidate instanceof EntityAutomaton ally
                        && ally != bot
                        && ally.isEntityAlive()
                        && !ally.isDowned()
                        && bot.getOwnerUuid().equals(ally.getOwnerUuid())) {
                    if (ally.isEating() || ally.getHealthRatio() < 0.50F) {
                        vulnerableAllies.add(ally);
                    } else {
                        healthyAllies.add(ally);
                    }
                }
            }
        }

        EntityLivingBase bestFree = null;
        double bestFreeScore = radius * radius;
        EntityLivingBase bestContested = null;
        double bestContestedScore = radius * radius;

        for (EntityLivingBase candidate : candidates) {
            if (!isValidCombatCandidate(ctx, candidate) || !extraFilter.test(candidate)) {
                continue;
            }
            double distSq = bot.getDistanceSq(candidate);
            boolean primedCreeper = candidate instanceof EntityCreeper creeper
                    && creeper.getCreeperState() > 0
                    && distSq <= CREEPER_PRIORITY_RADIUS_SQ;
            boolean threatensVulnerable = !vulnerableAllies.isEmpty()
                    && isThreateningAnyAlly(candidate, vulnerableAllies, ALLY_PROTECT_RADIUS_SQ);
            boolean threatensHealthy = !healthyAllies.isEmpty()
                    && isThreateningAnyAlly(candidate, healthyAllies, HEALTHY_ALLY_PROTECT_RADIUS_SQ);
            boolean rangedThreat = candidate instanceof IRangedAttackMob && bot.canEntityBeSeen(candidate);
            double score = computeTacticalTargetScore(
                    distSq, primedCreeper, threatensVulnerable, threatensHealthy, rangedThreat);

            boolean contested = squadCoop
                    && !primedCreeper
                    && !threatensVulnerable
                    && ctx.getBlackboard().isTurnHeldByOther(bot.getEntityId(), candidate.getEntityId(), now);
            if (contested) {
                if (score < bestContestedScore) {
                    bestContestedScore = score;
                    bestContested = candidate;
                }
            } else if (score < bestFreeScore) {
                bestFreeScore = score;
                bestFree = candidate;
            }
        }
        return bestFree != null ? bestFree : bestContested;
    }

    static double computeTacticalTargetScore(
            double distSq,
            boolean primedCreeper,
            boolean threatensVulnerableAlly,
            boolean threatensHealthyAlly,
            boolean rangedThreat) {
        double score = distSq;
        if (primedCreeper) {
            score *= PRIMED_CREEPER_WEIGHT;
        }
        if (threatensVulnerableAlly) {
            score *= ALLY_PROTECT_WEIGHT;
        } else if (threatensHealthyAlly) {
            score *= HEALTHY_ALLY_PROTECT_WEIGHT;
        }
        if (rangedThreat) {
            score *= RANGED_THREAT_WEIGHT;
        }
        return score;
    }

    private static boolean isThreateningAnyAlly(EntityLivingBase enemy, List<EntityAutomaton> allies, double radiusSq) {
        for (EntityAutomaton ally : allies) {
            if (enemy.getDistanceSq(ally) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    static boolean isValidCombatCandidate(AutomatonAIContext ctx, EntityLivingBase candidate) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (candidate == bot || !candidate.isEntityAlive()) {
            return false;
        }
        UUID ownerUuid = bot.getOwnerUuid();
        if (candidate instanceof EntityPlayer player) {
            if (ownerUuid != null && ownerUuid.equals(player.getUniqueID())) {
                return false;
            }
            if (player.isSpectator() || player.isCreative()) {
                return false;
            }
        }
        if (candidate instanceof EntityAutomaton ally) {
            if (ally.isDowned()) {
                return false;
            }
            if (ownerUuid != null && ownerUuid.equals(ally.getOwnerUuid())) {
                return false;
            }
        }
        AutomatonTaskConfig config = ctx.getTaskConfig();
        if (!passesEntityFilter(config, candidate)) {
            return false;
        }
        if (config.isWhitelistMode()) {
            return true;
        }
        boolean angry = isAngryOrTargetingTeam(candidate);
        if (config.isIgnoreCalmNeutrals() && isNeutralMob(candidate) && !angry) {
            return false;
        }
        if (config.isIgnoreAnimals() && candidate instanceof IAnimals && !(candidate instanceof IMob) && !angry) {
            return false;
        }
        return true;
    }

    private static boolean isNeutralMob(EntityLivingBase candidate) {
        return candidate instanceof EntityEnderman
                || candidate instanceof EntityPigZombie
                || candidate instanceof EntityWolf
                || candidate instanceof EntityPolarBear;
    }

    private static boolean isAngryOrTargetingTeam(EntityLivingBase candidate) {
        if (candidate instanceof EntityEnderman enderman && enderman.isScreaming()) {
            return true;
        }
        if (candidate instanceof EntityPigZombie pigman && pigman.isAngry()) {
            return true;
        }
        if (candidate instanceof EntityWolf wolf && wolf.isAngry()) {
            return true;
        }
        if (candidate instanceof EntityLiving living && living.getAttackTarget() != null) {
            return true;
        }
        return candidate.getRevengeTarget() != null;
    }

    static boolean passesEntityFilter(AutomatonTaskConfig config, Entity entity) {
        ResourceLocation key = EntityList.getKey(entity);
        String id = key == null ? "" : key.toString();
        boolean inSet = config.getEntityFilterIds().contains(id);
        return config.isWhitelistMode() ? inSet : !inSet;
    }
}

