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
import com.x4yi.automata.compat.ModCompatBridge;
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
        long now = ctx.getWorldTime();
        boolean centeredOnBot = center.distanceSq(bot.posX, bot.posY, bot.posZ) <= 4.0D;
        if (centeredOnBot && ctx.hasCachedPrimaryTargetForTick(now, radius)) {
            EntityLivingBase cached = ctx.getCachedPrimaryTarget(now, radius);
            if (cached == null || (isValidCombatCandidate(ctx, cached) && extraFilter.test(cached))) {
                return cached;
            }
        }
        List<EntityLivingBase> candidates;
        if (centeredOnBot) {
            candidates = ctx.getNearbyLivingEntities(radius);
        } else {
            AxisAlignedBB box = new AxisAlignedBB(center).grow(radius);
            candidates = bot.world.getEntitiesWithinAABB(EntityLivingBase.class, box);
        }
        boolean squadCoop = ctx.getTaskConfig().isSquadCooperation();

        List<EntityAutomaton> vulnerableAllies = ctx.getScratchVulnerableAllies();
        List<EntityAutomaton> healthyAllies = ctx.getScratchHealthyAllies();
        double squadSumX = bot.posX;
        double squadSumZ = bot.posZ;
        int squadCount = 1;
        if (squadCoop) {
            for (EntityLivingBase candidate : candidates) {
                if (candidate instanceof EntityAutomaton ally
                        && ally != bot
                        && ally.isEntityAlive()
                        && !ally.isDowned()
                        && bot.isAlliedWith(ally)) {
                    squadSumX += ally.posX;
                    squadSumZ += ally.posZ;
                    squadCount++;
                    if (ally.isEating() || ally.getHealthRatio() < 0.50F) {
                        vulnerableAllies.add(ally);
                    } else {
                        healthyAllies.add(ally);
                    }
                }
            }
        }
        double squadCenterX = squadSumX / squadCount;
        double squadCenterZ = squadSumZ / squadCount;
        com.x4yi.automata.ai.BotLoadoutProfile loadout = ctx.getLoadoutProfile();
        WeaponCombatProfile botWeaponProfile = loadout.mainHandProfile();
        boolean botIsRanged = loadout.hasReadyRangedWeapon()
                && botWeaponProfile.getRole() == WeaponCombatProfile.CombatRole.RANGED;
        boolean botIsVengeance = loadout.hasBookOfVengeance()
                && botWeaponProfile.getRole() == WeaponCombatProfile.CombatRole.SPECIAL_CASTER;
        CombatJobSupport.CombatTacticsState tacticsState = ctx.getCombatTacticsState();
        int currentTargetId = tacticsState.activeTargetId;
        boolean targetLocked = currentTargetId >= 0 && now < tacticsState.committedTargetUntilTick;
        com.x4yi.automata.coordination.SquadBlackboard.ReviveAlert activeReviveAlert = squadCoop
                ? ctx.getBlackboard().findNearestActiveReviveAlert(bot.getEntityId(), bot.getPosition(), 1024.0D, now)
                : null;

        EntityLivingBase bestFree = null;
        double bestFreeScore = radius * radius;
        EntityLivingBase bestContested = null;
        double bestContestedScore = radius * radius;

        for (EntityLivingBase candidate : candidates) {
            if (!isValidCombatCandidate(ctx, candidate) || !extraFilter.test(candidate)) {
                continue;
            }
            if (candidate.getEntityId() == tacticsState.unreachableEntityId && now < tacticsState.unreachableUntilTick) {
                continue;
            }
            double distSq = bot.getDistanceSq(candidate);
            boolean isRecentRevenge = candidate == bot.getRevengeTarget();
            boolean aggroOnTeam = isTargetingTeam(candidate, bot);
            boolean goalOnCooldown = ctx.isGoalOnCooldown(candidate.getPosition(), now);
            double dyAbs = Math.abs(candidate.posY - bot.posY);
            if (!shouldConsiderVisibilityAndAccess(
                    true,
                    aggroOnTeam,
                    isRecentRevenge,
                    distSq,
                    dyAbs,
                    goalOnCooldown)) {
                continue;
            }
            boolean primedCreeper = ((candidate instanceof EntityCreeper creeper && creeper.getCreeperState() > 0)
                    || ModCompatBridge.isImminentSwellingExplosive(candidate))
                    && distSq <= CREEPER_PRIORITY_RADIUS_SQ;
            boolean threatensVulnerable = !vulnerableAllies.isEmpty()
                    && isThreateningAnyAlly(candidate, vulnerableAllies, ALLY_PROTECT_RADIUS_SQ);
            if (activeReviveAlert != null
                    && !threatensVulnerable
                    && !primedCreeper
                    && candidate.getDistanceSqToCenter(activeReviveAlert.pos()) > 256.0D) {
                continue;
            }
            boolean threatensHealthy = !healthyAllies.isEmpty()
                    && isThreateningAnyAlly(candidate, healthyAllies, HEALTHY_ALLY_PROTECT_RADIUS_SQ);
            boolean isRangedOrSpecial = AdaptiveThreatAnalyzer.isRangedOrSpecialThreat(candidate);
            AdaptiveThreatAnalyzer.ThreatProfile threatProfile = squadCoop
                    ? ctx.getBlackboard().getOrComputeThreatProfile(
                            candidate.getEntityId(), now, 10L, () -> AdaptiveThreatAnalyzer.analyzeThreat(bot, candidate))
                    : AdaptiveThreatAnalyzer.analyzeThreat(bot, candidate);
            ModCompatBridge.TacticalDirective directive = ModCompatBridge.resolveTacticalDirective(candidate);
            double baseScore = computeTacticalTargetScore(
                    distSq, primedCreeper, threatensVulnerable, threatensHealthy, false)
                    * threatProfile.priorityMultiplier()
                    * directive.threatWeightMultiplier()
                    * botWeaponProfile.computeRoleAffinityMultiplier(threatProfile, candidate.getHealth());
            if (loadout.isUnarmedOrSupportOnly() && candidate != bot.getRevengeTarget()) {
                baseScore *= 3.5D;
            }
            if (squadCoop) {
                baseScore *= ctx.getBlackboard().getPriorityMultiplier(candidate.getEntityId(), now);
                if (threatensVulnerable) {
                    ctx.getBlackboard().reportPriorityTarget(bot.getEntityId(), candidate.getEntityId(), 0.35D, now, 30L);
                }
                if (activeReviveAlert != null && candidate.getDistanceSqToCenter(activeReviveAlert.pos()) <= 64.0D) {
                    baseScore *= 0.20D;
                }
            }
            if (squadCount > 1) {
                double dxSquad = candidate.posX - squadCenterX;
                double dzSquad = candidate.posZ - squadCenterZ;
                double distSquadSq = dxSquad * dxSquad + dzSquad * dzSquad;
                if (distSquadSq > 100.0D && !directive.highPriorityFocus() && !threatProfile.has(AdaptiveThreatAnalyzer.ThreatTrait.BOSS_TIER)) {
                    baseScore *= 4.0D;
                }
            }
            if (botIsVengeance
                    && squadCoop
                    && candidate.getHealth() <= 55.0F
                    && !threatProfile.has(AdaptiveThreatAnalyzer.ThreatTrait.BOSS_TIER)
                    && ctx.getBlackboard().isVengeanceSlamClaimedByOther(bot.getEntityId(), candidate.getEntityId(), now)) {
                baseScore *= 6.0D;
            }
            if (candidate.getEntityId() == currentTargetId && !primedCreeper) {
                baseScore *= targetLocked ? 0.45D : 0.72D;
            }

            int meleeOnTarget = squadCoop
                    ? ctx.getBlackboard().getAssignedMeleeCount(candidate.getEntityId(), bot.getEntityId(), botIsRanged ? 0.0D : distSq, now)
                    : 0;
            boolean rangedYieldsToMelee = botIsRanged
                    && meleeOnTarget > 0
                    && !threatProfile.has(AdaptiveThreatAnalyzer.ThreatTrait.BOSS_TIER)
                    && !threatProfile.has(AdaptiveThreatAnalyzer.ThreatTrait.AERIAL)
                    && candidate.getHealth() <= 40.0F;
            if (rangedYieldsToMelee) {
                baseScore *= 2.2D;
            }
            boolean slotSaturated = !botIsRanged && meleeOnTarget >= threatProfile.recommendedMeleeSlots();
            boolean turnBusy = !botIsRanged && ctx.getBlackboard().isTurnHeldByOther(bot.getEntityId(), candidate.getEntityId(), now);
            boolean contested = squadCoop
                    && !primedCreeper
                    && !threatensVulnerable
                    && !threatProfile.has(AdaptiveThreatAnalyzer.ThreatTrait.BOSS_TIER)
                    && !directive.highPriorityFocus()
                    && (slotSaturated || turnBusy || rangedYieldsToMelee);

            double optimisticScore = isRangedOrSpecial ? baseScore * RANGED_THREAT_WEIGHT : baseScore;
            if (contested) {
                if (bestFree != null || optimisticScore >= bestContestedScore) {
                    continue;
                }
            } else if (optimisticScore >= bestFreeScore) {
                continue;
            }

            boolean canSee = ctx.canSeeEntityCached(candidate);
            if (!shouldConsiderVisibilityAndAccess(
                    canSee,
                    aggroOnTeam,
                    isRecentRevenge,
                    distSq,
                    dyAbs,
                    goalOnCooldown)) {
                continue;
            }
            double score = (isRangedOrSpecial && canSee) ? baseScore * RANGED_THREAT_WEIGHT : baseScore;
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
        EntityLivingBase result = bestFree != null ? bestFree : bestContested;
        if (centeredOnBot) {
            ctx.cachePrimaryTarget(now, radius, result);
        }
        return result;
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

    static boolean shouldConsiderVisibilityAndAccess(
            boolean canBotSee,
            boolean isAggroOnTeam,
            boolean isRecentRevenge,
            double distSq,
            double absDy,
            boolean targetGoalOnCooldown) {
        if (targetGoalOnCooldown) {
            return false;
        }
        if (canBotSee) {
            return true;
        }
        if (isRecentRevenge || isAggroOnTeam) {
            return distSq <= 256.0D && absDy <= 6.0D;
        }
        return distSq <= 25.0D && absDy <= 2.5D;
    }

    private static boolean isTargetingTeam(EntityLivingBase candidate, EntityAutomaton bot) {
        if (!(candidate instanceof EntityLiving living)) {
            return false;
        }
        EntityLivingBase attackTarget = living.getAttackTarget();
        if (attackTarget == null) {
            return false;
        }
        if (attackTarget == bot) {
            return true;
        }
        if (attackTarget instanceof EntityAutomaton ally && bot.isAlliedWith(ally)) {
            return true;
        }
        return bot.getOwnerUuid() != null && bot.getOwnerUuid().equals(attackTarget.getUniqueID());
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
        if (ModCompatBridge.isNonTargetableHazard(candidate)) {
            return false;
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

