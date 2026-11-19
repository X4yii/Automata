package com.x4yi.automata.ai.jobs;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.compat.SrpCompat;
import com.x4yi.automata.entity.EntityAutomaton;

public final class VengeanceBookController {

    private static final double SLAM_MIN_RANGE_SQ = 4.0D;
    private static final double SLAM_MAX_RANGE_SQ = 324.0D;
    private static final double AERIAL_SLAM_MAX_RANGE_SQ = 400.0D;
    private static final double IMPACT_TRIGGER_DIST_SQ = 6.25D;
    private static final double KB_RADIUS_XZ = 5.0D;
    private static final double KB_RADIUS_Y = 1.5D;
    private static final double KB_STRENGTH = 1.35D;
    private static final double KB_UP = 0.42D;
    private static final float SLAM_HIT_DAMAGE = 24.0F;
    private static final long SLAM_COOLDOWN_TICKS = 45L;
    private static final long KB_COOLDOWN_TICKS = 30L;
    private static final double AIR_CLUTCH_HEIGHT = 4.0D;
    private static final double MAX_NON_AERIAL_DROP_DY = -3.5D;
    private static final double MAX_NON_AERIAL_CLIMB_DY = 4.5D;

    private VengeanceBookController() {}

    public static boolean isVengeanceProtected(EntityAutomaton bot, @Nullable EntityLivingBase target) {
        if (target == null || target == bot) {
            return true;
        }
        if (bot.getOwnerUuid() != null
                && target instanceof EntityPlayer player
                && bot.getOwnerUuid().equals(player.getUniqueID())) {
            return true;
        }
        return target instanceof EntityAutomaton other && bot.isInSameSquadWith(other);
    }

    public static boolean hasBookEquippedOrInInventory(EntityAutomaton bot) {
        if (SrpCompat.isBookOfVengeance(bot.getHeldItemMainhand())) {
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (SrpCompat.isBookOfVengeance(inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidSlamGeometry(
            double botY,
            double targetY,
            boolean targetAerial,
            boolean botAirborne) {
        if (targetAerial || botAirborne) {
            return true;
        }
        double dy = targetY - botY;
        return dy >= MAX_NON_AERIAL_DROP_DY && dy <= MAX_NON_AERIAL_CLIMB_DY;
    }

    public static boolean isValidSlamGeometry(EntityAutomaton bot, EntityLivingBase target) {
        boolean aerial = AdaptiveThreatAnalyzer.isAerialThreat(bot, target);
        return isValidSlamGeometry(bot.posY, target.posY, aerial, !bot.onGround && !bot.isInWater());
    }

    public static boolean shouldConserveBookAgainstEasyTarget(
            float targetHealth,
            boolean bossOrHighThreat,
            boolean aerialThreat,
            boolean hasAlternativeWeapon,
            double bestAlternativeDamage) {
        if (!hasAlternativeWeapon || bossOrHighThreat || aerialThreat) {
            return false;
        }
        float easyThreshold = (float) Math.max(20.0D, bestAlternativeDamage * 2.0D);
        return targetHealth <= easyThreshold;
    }

    public static boolean shouldConserveBookAgainstEasyTarget(EntityAutomaton bot, EntityLivingBase target) {
        return shouldConserveBookAgainstEasyTarget(
                target.getHealth(),
                AdaptiveThreatAnalyzer.isBossOrHighThreat(target),
                AdaptiveThreatAnalyzer.isAerialThreat(bot, target),
                EquipmentSelector.hasAlternativeCombatWeapon(bot),
                EquipmentSelector.estimateBestNonBookWeaponDamage(bot));
    }

    public static boolean hasClearLinearDashCorridor(
            WorldServer world,
            EntityAutomaton bot,
            EntityLivingBase target) {
        double[] botHeights = {0.35D, 0.95D, 1.55D};
        double[] targetHeights = {
                Math.min(0.35D, target.height * 0.25D),
                target.height * 0.50D,
                Math.max(0.65D, target.height - 0.15D)
        };
        for (int i = 0; i < 3; i++) {
            Vec3d start = new Vec3d(bot.posX, bot.posY + botHeights[i], bot.posZ);
            Vec3d end = new Vec3d(target.posX, target.posY + targetHeights[i], target.posZ);
            RayTraceResult hit = world.rayTraceBlocks(start, end, false, true, false);
            if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
                double hitDistSq = start.squareDistanceTo(hit.hitVec);
                double targetDistSq = start.squareDistanceTo(end);
                if (hitDistSq + 0.25D < targetDistSq) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double getMaxSlamRange() {
        return SrpCompat.getVengeanceBookRange();
    }

    private static double getMaxSlamRangeSq() {
        double r = getMaxSlamRange();
        return r * r;
    }

    @Nullable
    private static EntityLivingBase findBestLinearSlamTarget(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase excludedPrimary) {
        WorldServer world = ctx.getWorld();
        double maxRange = getMaxSlamRange();
        double maxRangeSq = maxRange * maxRange;
        boolean coop = ctx.getTaskConfig().isSquadCooperation();
        long now = ctx.getWorldTime();
        EntityLivingBase best = null;
        double bestDistSq = maxRangeSq;
        for (EntityLivingBase candidate : ctx.getNearbyLivingEntities(maxRange)) {
            if (candidate == excludedPrimary
                    || isVengeanceProtected(bot, candidate)
                    || !CombatJobSupport.isValidCombatCandidate(ctx, candidate)) {
                continue;
            }
            double dSq = bot.getDistanceSq(candidate);
            if (dSq < SLAM_MIN_RANGE_SQ || dSq > maxRangeSq) {
                continue;
            }
            if (!isValidSlamGeometry(bot, candidate)
                    || shouldConserveBookAgainstEasyTarget(bot, candidate)
                    || !hasClearLinearDashCorridor(world, bot, candidate)) {
                continue;
            }
            if (coop
                    && candidate.getHealth() <= 55.0F
                    && !AdaptiveThreatAnalyzer.isBossOrHighThreat(candidate)
                    && ctx.getBlackboard().isVengeanceSlamClaimedByOther(bot.getEntityId(), candidate.getEntityId(), now)) {
                continue;
            }
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = candidate;
            }
        }
        return best;
    }

    public static boolean tickAirClutch20Hz(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (bot.isDowned() || bot.onGround || bot.isInWater() || bot.isInLava()) {
            return false;
        }
        if (bot.fallDistance < 3.2F || bot.motionY >= -0.22D) {
            return false;
        }
        CombatJobSupport.CombatTacticsState state = ctx.getCombatTacticsState();
        long now = ctx.getWorldTime();
        if (now < state.vengeanceKbCooldownUntilTick || !hasBookEquippedOrInInventory(bot)) {
            return false;
        }
        if (!isGroundImminentBelow(ctx.getWorld(), bot, AIR_CLUTCH_HEIGHT)) {
            return false;
        }
        if (!EquipmentSelector.equipBookOfVengeance(bot, true)) {
            return false;
        }
        return tryDefensiveKnockbackBurst(ctx, true);
    }

    private static boolean isGroundImminentBelow(WorldServer world, EntityAutomaton bot, double maxHeight) {
        double dynamicLookahead = Math.min(5.0D, Math.max(maxHeight, Math.abs(bot.motionY) * 2.5D));
        int maxSteps = MathHelper.ceil(dynamicLookahead);
        int bx = MathHelper.floor(bot.posX);
        int by = MathHelper.floor(bot.posY);
        int bz = MathHelper.floor(bot.posZ);
        for (int dy = 1; dy <= maxSteps; dy++) {
            BlockPos check = new BlockPos(bx, by - dy, bz);
            if (!world.isBlockLoaded(check)) {
                continue;
            }
            if (world.getBlockState(check).getMaterial().blocksMovement()
                    || world.getBlockState(check).getMaterial().isLiquid()) {
                return (bot.posY - (check.getY() + 1.0D)) <= dynamicLookahead;
            }
        }
        return false;
    }

    public static boolean tickActiveGrapple20Hz(AutomatonAIContext ctx) {
        CombatJobSupport.CombatTacticsState state = ctx.getCombatTacticsState();
        if (state.vengeanceGrapplePhase == 0) {
            return false;
        }
        EntityAutomaton bot = ctx.getAutomaton();
        if (bot.isDowned() || !SrpCompat.isBookOfVengeance(bot.getHeldItemMainhand())) {
            resetGrapple(state);
            return false;
        }
        WorldServer world = ctx.getWorld();
        long now = ctx.getWorldTime();
        Entity rawTarget = world.getEntityByID(state.vengeanceGrappleTargetId);
        if (!(rawTarget instanceof EntityLivingBase target) || !target.isEntityAlive()) {
            resetGrapple(state);
            return false;
        }

        state.lastEngagedTick = now;
        ctx.stopNavigation();
        ctx.setUrgentMovement(true);
        ctx.getMovement().lookSmoothlyAt(target.posX, target.posY + target.height * 0.5D, target.posZ);

        if (state.vengeanceGrapplePhase == 1) {
            spawnTrailParticles(world, bot, target);
            if (--state.vengeanceGrappleTicksRemaining <= 0) {
                state.vengeanceGrapplePhase = 2;
                state.vengeanceGrappleTicksRemaining = Math.max(22, (int) Math.ceil(getMaxSlamRange()) + 6);
            }
            return true;
        }

        if (state.vengeanceGrapplePhase == 2) {
            bot.fallDistance = 0.0F;
            double distSq = bot.getDistanceSq(target);
            if (distSq <= IMPACT_TRIGGER_DIST_SQ) {
                performImpact1(world, bot, target);
                state.vengeanceGrapplePhase = 3;
                state.vengeanceGrappleTicksRemaining = 4;
                return true;
            }
            boolean aerial = AdaptiveThreatAnalyzer.isAerialThreat(bot, target) || !target.onGround;
            pullBotToward(bot, target, aerial ? 1.25D : 1.15D, aerial);
            spawnTrailParticles(world, bot, target);
            if (--state.vengeanceGrappleTicksRemaining <= 0) {
                ctx.getBlackboard().releaseVengeanceSlam(bot.getEntityId(), state.vengeanceGrappleTargetId);
                resetGrapple(state);
                state.vengeanceSlamCooldownUntilTick = now + 40L;
            }
            return true;
        }

        if (state.vengeanceGrapplePhase == 3) {
            bot.fallDistance = 0.0F;
            if (--state.vengeanceGrappleTicksRemaining <= 0) {
                performImpact2AndLightning(world, bot, target);
                bot.fallDistance = 0.0F;
                ctx.getBlackboard().releaseVengeanceSlam(bot.getEntityId(), state.vengeanceGrappleTargetId);
                resetGrapple(state);
                state.vengeanceSlamCooldownUntilTick = now + SLAM_COOLDOWN_TICKS;
            }
            return true;
        }

        resetGrapple(state);
        return false;
    }

    public static boolean engageWithVengeanceBook(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state,
            double distSq,
            long now) {
        if (state.vengeanceGrapplePhase != 0) {
            return true;
        }

        ThreatFieldEvaluator.ThreatAssessment threats = ThreatFieldEvaluator.assessLocalThreats(ctx, bot);
        if ((threats.cornered() || threats.enemyCount() >= 3)
                && distSq <= 25.0D
                && now >= state.vengeanceKbCooldownUntilTick
                && tryDefensiveKnockbackBurst(ctx, false)) {
            return true;
        }

        if (shouldConserveBookAgainstEasyTarget(bot, target) && EquipmentSelector.equipBestNonBookWeapon(bot)) {
            return false;
        }

        EntityLivingBase slamTarget = target;
        boolean primaryValid = !isVengeanceProtected(bot, target)
                && isValidSlamGeometry(bot, target)
                && hasClearLinearDashCorridor(ctx.getWorld(), bot, target);
        if (!primaryValid) {
            EntityLivingBase alt = findBestLinearSlamTarget(ctx, bot, target);
            if (alt != null) {
                slamTarget = alt;
                state.activeTargetId = alt.getEntityId();
                distSq = bot.getDistanceSq(alt);
                primaryValid = true;
            }
        }

        boolean aerial = AdaptiveThreatAnalyzer.isAerialThreat(bot, slamTarget);
        double maxRangeSq = getMaxSlamRangeSq();
        boolean coop = ctx.getTaskConfig().isSquadCooperation();
        boolean allowMultiSlam = AdaptiveThreatAnalyzer.isBossOrHighThreat(slamTarget) || slamTarget.getHealth() > 55.0F;

        if (primaryValid
                && distSq >= SLAM_MIN_RANGE_SQ
                && distSq <= maxRangeSq
                && bot.canEntityBeSeen(slamTarget)
                && now >= state.vengeanceSlamCooldownUntilTick
                && (!coop || ctx.getBlackboard().tryClaimVengeanceSlam(
                        bot.getEntityId(), slamTarget.getEntityId(), allowMultiSlam, now, 36L))) {
            state.vengeanceGrappleTargetId = slamTarget.getEntityId();
            state.vengeanceGrapplePhase = 1;
            state.vengeanceGrappleTicksRemaining = 2;
            SrpCompat.playVengeanceSound(ctx.getWorld(), bot.posX, bot.posY, bot.posZ, "paper", 1.5F, 1.0F);
            ctx.stopNavigation();
            ctx.getMovement().lookSmoothlyAt(slamTarget.posX, slamTarget.posY + slamTarget.height * 0.5D, slamTarget.posZ);
            return true;
        }

        if (!primaryValid || distSq > maxRangeSq) {
            if (!aerial && EquipmentSelector.hasAlternativeCombatWeapon(bot) && distSq <= 16.0D) {
                if (EquipmentSelector.equipBestNonBookWeapon(bot)) {
                    return false;
                }
            }
            ctx.requestPathTo(slamTarget.getPosition(), true);
            return true;
        }

        if (distSq <= 36.0D && EquipmentSelector.equipBestNonBookWeapon(bot)) {
            return false;
        }
        ctx.stopNavigation();
        ctx.getMovement().lookSmoothlyAt(slamTarget.posX, slamTarget.posY + slamTarget.getEyeHeight(), slamTarget.posZ);
        float fwd = distSq < 6.25D ? -0.35F : (distSq > 12.25D ? 0.95F : 0.35F);
        MeleeCombatController.applyCombatStrafe(ctx, bot, slamTarget, state, fwd, state.strafeDir * 0.85F, true, now);
        return true;
    }

    public static boolean tryDefensiveKnockbackBurst(AutomatonAIContext ctx, boolean fallingEmergency) {
        EntityAutomaton bot = ctx.getAutomaton();
        CombatJobSupport.CombatTacticsState state = ctx.getCombatTacticsState();
        long now = ctx.getWorldTime();
        if (!SrpCompat.isBookOfVengeance(bot.getHeldItemMainhand()) || now < state.vengeanceKbCooldownUntilTick) {
            return false;
        }
        WorldServer world = ctx.getWorld();
        SrpCompat.playVengeanceSound(world, bot.posX, bot.posY, bot.posZ, "chain", 1.0F, 1.0F);
        SrpCompat.playVengeanceSound(world, bot.posX, bot.posY, bot.posZ, "impact", 1.0F, 1.0F);
        world.spawnParticle(EnumParticleTypes.SPELL_WITCH, bot.posX, bot.posY + 0.9D, bot.posZ, 26, 0.8D, 0.4D, 0.8D, 0.15D);

        bot.fallDistance = 0.0F;
        if (fallingEmergency || bot.motionY < 0.0D) {
            bot.motionY = 0.55D;
            bot.velocityChanged = true;
        }

        AxisAlignedBB box = bot.getEntityBoundingBox().grow(KB_RADIUS_XZ, KB_RADIUS_Y, KB_RADIUS_XZ);
        List<EntityLivingBase> nearby = world.getEntitiesWithinAABB(EntityLivingBase.class, box);
        for (EntityLivingBase entity : nearby) {
            if (!entity.isEntityAlive() || isVengeanceProtected(bot, entity)) {
                continue;
            }
            double dx = entity.posX - bot.posX;
            double dz = entity.posZ - bot.posZ;
            double len = Math.hypot(dx, dz);
            if (len < 1.0E-4D) {
                dx = 1.0D;
                dz = 0.0D;
                len = 1.0D;
            }
            entity.motionX += (dx / len) * KB_STRENGTH;
            entity.motionY = Math.max(entity.motionY, KB_UP);
            entity.motionZ += (dz / len) * KB_STRENGTH;
            entity.velocityChanged = true;
        }

        bot.swingArm(EnumHand.MAIN_HAND);
        state.vengeanceKbCooldownUntilTick = now + KB_COOLDOWN_TICKS;
        return true;
    }

    private static void performImpact1(WorldServer world, EntityAutomaton bot, EntityLivingBase target) {
        SrpCompat.playVengeanceSound(world, target.posX, target.posY, target.posZ, "impact", 1.0F, 1.0F);
        SrpCompat.playVengeanceSound(world, target.posX, target.posY, target.posZ, "rock", 0.9F, 0.9F);
        world.spawnParticle(EnumParticleTypes.EXPLOSION_HUGE, target.posX, target.posY + target.height * 0.5D, target.posZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        bot.swingArm(EnumHand.MAIN_HAND);
        if (!isVengeanceProtected(bot, target)) {
            target.attackEntityFrom(DamageSource.causeMobDamage(bot), SLAM_HIT_DAMAGE);
            SrpCompat.applyVengeanceDebuffs(target);
        }
    }

    private static void performImpact2AndLightning(WorldServer world, EntityAutomaton bot, EntityLivingBase target) {
        world.playSound(null, target.posX, target.posY, target.posZ, SoundEvents.ENTITY_LIGHTNING_IMPACT, SoundCategory.PLAYERS, 0.7F, 1.2F);
        SrpCompat.playVengeanceSound(world, target.posX, target.posY, target.posZ, "rock", 0.9F, 0.9F);
        world.addWeatherEffect(new EntityLightningBolt(world, target.posX, target.posY, target.posZ, true));
        bot.swingArm(EnumHand.MAIN_HAND);
        if (!isVengeanceProtected(bot, target)) {
            target.attackEntityFrom(DamageSource.causeMobDamage(bot), SLAM_HIT_DAMAGE);
            SrpCompat.applyVengeanceDebuffs(target);
        }
    }

    private static void pullBotToward(EntityAutomaton bot, EntityLivingBase target, double speed, boolean aerial) {
        double dx = target.posX - bot.posX;
        double dy = (target.posY + target.height * 0.5D) - (bot.posY + bot.height * 0.5D);
        double dz = target.posZ - bot.posZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4D) {
            return;
        }
        bot.motionX = (dx / len) * speed;
        double minY = aerial ? -0.55D : -0.25D;
        double maxY = aerial ? 0.85D : 0.65D;
        bot.motionY = Math.max(minY, Math.min(maxY, (dy / len) * speed + 0.12D));
        bot.motionZ = (dz / len) * speed;
        bot.velocityChanged = true;
    }

    private static void spawnTrailParticles(WorldServer world, EntityAutomaton bot, EntityLivingBase target) {
        double mx = (bot.posX + target.posX) * 0.5D;
        double my = (bot.posY + bot.height * 0.5D + target.posY + target.height * 0.5D) * 0.5D;
        double mz = (bot.posZ + target.posZ) * 0.5D;
        world.spawnParticle(EnumParticleTypes.SPELL_WITCH, mx, my, mz, 4, 0.25D, 0.25D, 0.25D, 0.02D);
    }

    private static void resetGrapple(CombatJobSupport.CombatTacticsState state) {
        state.vengeanceGrapplePhase = 0;
        state.vengeanceGrappleTargetId = -1;
        state.vengeanceGrappleTicksRemaining = 0;
    }
}
