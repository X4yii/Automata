package com.x4yi.automata.ai.jobs;

import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.compat.ModCompatBridge;
import com.x4yi.automata.entity.EntityAutomaton;

public final class CombatJobSupport {

    private static final double ATTACK_RANGE_SQ = 9.0D;
    private static final double LOW_GAP_ATTACK_RANGE_SQ = 10.89D;
    private static final double CRIT_APPROACH_RANGE_SQ = 14.0D;
    private static final double BOW_IDEAL_RANGE_SQ = 1024.0D;
    private static final double BOW_MAX_RANGE_SQ = 4096.0D;
    private static final double BOW_MAX_VERTICAL_RANGE = 72.0D;
    private static final double CREEPER_FLEE_RANGE_SQ = 22.0D;
    private static final double CLOSE_COMBAT_20HZ_SQ = 196.0D;
    private static final double EATING_RETREAT_RADIUS = 10.0D;
    private static final double ESCORT_DISTANCE_SQ = 64.0D;
    private static final long TURN_CLAIM_TTL_TICKS = 12L;

    private CombatJobSupport() {}

    public static CombatTacticsState createTacticsState(int entityId) {
        return new CombatTacticsState(computeInitialFlankSign(entityId));
    }

    public static void cancelBowDraw(AutomatonAIContext ctx) {
        ctx.getCombatTacticsState().bowChargeStartTick = -1L;
        cancelBowDraw(ctx.getAutomaton());
    }

    public static void cancelBowDraw(EntityAutomaton bot) {
        if (bot.getBrain() != null) {
            bot.getBrain().getContext().getCombatTacticsState().bowChargeStartTick = -1L;
        }
        if (bot.isHandActive() && EquipmentSelector.isBowItem(bot.getActiveItemStack())) {
            bot.resetActiveHand();
        }
    }

    public static void tickActiveCombat20Hz(AutomatonAIContext ctx, boolean urgent) {
        EntityAutomaton bot = ctx.getAutomaton();
        long now = ctx.getWorldTime();
        CombatTacticsState state = ctx.getCombatTacticsState();

        if (bot.isEating()) {
            cancelBowDraw(bot);
            retreatWhileEating(ctx, bot, state, now);
            return;
        }
        if (VengeanceBookController.tickActiveGrapple20Hz(ctx)) {
            return;
        }
        if (state.lastEngagedTick == now) {
            return;
        }
        EntityLivingBase revenge = bot.getRevengeTarget();
        if (state.activeTargetId < 0 && revenge != null && isValidCombatCandidate(ctx, revenge)) {
            engageTarget(ctx, revenge, true);
            return;
        }
        if (state.activeTargetId < 0) {
            cancelBowDraw(ctx);
            return;
        }
        Entity entity = bot.world.getEntityByID(state.activeTargetId);
        if (!(entity instanceof EntityLivingBase target) || !isValidCombatCandidate(ctx, target)) {
            clearActiveTarget(ctx, bot, state);
            return;
        }
        if (ctx.isGoalOnCooldown(target.getPosition(), now)) {
            markTargetUnreachable(ctx, bot, state, target.getEntityId(), now);
            return;
        }
        if (bot.canEntityBeSeen(target)) {
            state.unseenTicks = 0;
        } else if (bot.getDistanceSq(target) > 36.0D && ++state.unseenTicks > 30) {
            markTargetUnreachable(ctx, bot, state, target.getEntityId(), now);
            return;
        }
        boolean activeRanged = isWithinBowRange(bot, target)
                && (EquipmentSelector.isBowItem(bot.getHeldItemMainhand())
                        || WeaponCombatProfile.resolve(bot.getHeldItemMainhand()).isRangedSkirmishStyle()
                        || state.bowChargeStartTick >= 0L);
        if (bot.getDistanceSq(target) <= CLOSE_COMBAT_20HZ_SQ || activeRanged) {
            engageTarget(ctx, target, urgent);
        }
    }

    static boolean isWithinBowRange(EntityAutomaton bot, EntityLivingBase target) {
        double dx = target.posX - bot.posX;
        double dz = target.posZ - bot.posZ;
        double dy = Math.abs(target.posY - bot.posY);
        return (dx * dx + dz * dz <= BOW_MAX_RANGE_SQ) && dy <= BOW_MAX_VERTICAL_RANGE;
    }

    private static void retreatWhileEating(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            CombatTacticsState state,
            long now) {
        EntityLivingBase threat = findNearestTarget(ctx, bot.getPosition(), EATING_RETREAT_RADIUS, e -> true);
        if (threat == null) {
            return;
        }
        state.lastEngagedTick = now;
        ThreatFieldEvaluator.ThreatAssessment threats = ThreatFieldEvaluator.assessLocalThreats(ctx, bot);
        if (threats.hasEscapeVector() && (threats.cornered() || bot.collidedHorizontally)) {
            BlockPos escapeGoal = ThreatFieldEvaluator.findEscapeAnchor(ctx, bot, threats, 10.0D);
            if (escapeGoal != null) {
                ctx.requestPathTo(escapeGoal, true);
                return;
            }
        }
        ctx.stopNavigation();
        ctx.getMovement().lookSmoothlyAt(threat.posX, threat.posY + threat.getEyeHeight(), threat.posZ);
        MeleeCombatController.applyCombatStrafe(ctx, bot, threat, state, -1.0F, state.strafeDir * 0.45F, false, now);
    }

    @Nullable
    public static EntityLivingBase findNearestTarget(
            AutomatonAIContext ctx,
            BlockPos center,
            double radius,
            Predicate<EntityLivingBase> extraFilter) {
        return CombatTargetingService.findNearestTarget(ctx, center, radius, extraFilter);
    }

    public static boolean isValidCombatCandidate(AutomatonAIContext ctx, EntityLivingBase candidate) {
        return CombatTargetingService.isValidCombatCandidate(ctx, candidate);
    }

    static boolean passesEntityFilter(AutomatonTaskConfig config, Entity entity) {
        return CombatTargetingService.passesEntityFilter(config, entity);
    }

    public static void engageTarget(AutomatonAIContext ctx, EntityLivingBase target, boolean urgent) {
        EntityAutomaton bot = ctx.getAutomaton();
        long now = ctx.getWorldTime();
        CombatTacticsState state = ctx.getCombatTacticsState();
        if (state.activeTargetId >= 0 && state.activeTargetId != target.getEntityId()) {
            ctx.getBlackboard().releaseTarget(bot.getEntityId(), state.activeTargetId);
        }
        if (state.activeTargetId != target.getEntityId()) {
            state.committedTargetUntilTick = now + 30L;
            state.trackedHitTargetId = target.getEntityId();
            state.lowDamageStreak = 0;
            state.preferSecondaryWeaponUntilTick = -1L;
            state.unseenTicks = 0;
        }
        state.activeTargetId = target.getEntityId();
        state.lastEngagedTick = now;

        double distSq = bot.getDistanceSq(target);
        double hitboxDistSq = computeDistanceSqToHitbox(bot, target);
        if (ctx.getTaskConfig().isSquadCooperation()) {
            AdaptiveThreatAnalyzer.ThreatProfile threat = ctx.getBlackboard().getOrComputeThreatProfile(
                    target.getEntityId(), now, 10L, () -> AdaptiveThreatAnalyzer.analyzeThreat(bot, target));
            boolean isRangedRole = WeaponCombatProfile.resolve(bot.getHeldItemMainhand()).getRole()
                    == WeaponCombatProfile.CombatRole.RANGED;
            ctx.getBlackboard().tryClaimTarget(
                    bot.getEntityId(),
                    target.getEntityId(),
                    isRangedRole,
                    distSq,
                    threat.recommendedMeleeSlots(),
                    now,
                    25L);
        }

        if (bot.isEating()) {
            retreatWhileEating(ctx, bot, state, now);
            return;
        }
        if (state.vengeanceGrapplePhase != 0) {
            return;
        }

        ModCompatBridge.TacticalDirective directive = ModCompatBridge.resolveTacticalDirective(target);
        boolean creeperFlee = ((target instanceof EntityCreeper creeper && creeper.getCreeperState() > 0)
                || (directive.fleeWhenSwelling() && ModCompatBridge.isImminentSwellingExplosive(target)))
                && distSq < 36.0D;
        if (creeperFlee) {
            MeleeCombatController.handleCreeperEmergency(ctx, bot, target, state, distSq, now);
            return;
        }

        if (ProjectileDodgeController.tryDodgeIncomingProjectile(ctx, bot, target)) {
            if (!EquipmentSelector.isBowItem(bot.getHeldItemMainhand())) {
                WeaponCombatProfile dodgeProfile = WeaponCombatProfile.resolve(bot.getHeldItemMainhand());
                if (dodgeProfile.getRole() == WeaponCombatProfile.CombatRole.MELEE) {
                    double effectiveRangeSq = Math.max(ATTACK_RANGE_SQ, dodgeProfile.getAttackRangeSq());
                    boolean rapid = dodgeProfile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.RAPID_BRAWLER;
                    boolean invuln = target.hurtResistantTime > target.maxHurtResistantTime / 2;
                    if (hitboxDistSq <= effectiveRangeSq
                            && bot.isAttackReady()
                            && (rapid || !invuln)
                            && bot.canEntityBeSeen(target)) {
                        MeleeCombatController.performMeleeStrike(
                                ctx, bot, target, state, now, ctx.getTaskConfig().isSquadCooperation(), !rapid);
                    }
                }
            }
            return;
        }

        if (bot.isInWater()
                && AquaticCombatController.engageUnderwater(
                        ctx, bot, target, state, hitboxDistSq, now, ctx.getTaskConfig().isSquadCooperation())) {
            return;
        }

        if (ctx.getTaskConfig().isSquadCooperation() && distSq > 100.0D && !directive.highPriorityFocus()) {
            EntityAutomaton nearestAlly = findNearestActiveSquadAlly(ctx, bot);
            if (nearestAlly != null && bot.getDistanceSq(nearestAlly) > 256.0D) {
                cancelBowDraw(bot);
                ctx.requestPathTo(nearestAlly.getPosition(), true);
                return;
            }
        }

        ThreatFieldEvaluator.ThreatAssessment threats = ctx.getCachedThreatAssessment();
        if (bot.getHealthRatio() < 0.20F && distSq < 64.0D && (distSq < 36.0D || !RangedCombatController.equipBowIfReady(bot))) {
            cancelBowDraw(bot);
            if (TacticalUtilityController.tryCombatPearl(ctx, target, distSq, true)) {
                return;
            }
            if (threats.hasEscapeVector()) {
                BlockPos escapeGoal = ThreatFieldEvaluator.findEscapeAnchor(ctx, bot, threats, 11.0D);
                if (escapeGoal != null) {
                    ctx.requestPathTo(escapeGoal, true);
                    return;
                }
            }
            ctx.stopNavigation();
            ctx.getMovement().lookSmoothlyAt(target.posX, target.posY + target.getEyeHeight(), target.posZ);
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, -1.0F, state.strafeDir * 0.45F, true, now);
            return;
        }

        if (TacticalUtilityController.tryCombatPearl(ctx, target, distSq, false)) {
            return;
        }

        if (AerialCombatController.tickAerialCombat(ctx, bot, target, state)) {
            ctx.recordDecision("COMBAT", "Aerial wings maneuver vs " + target.getName());
        }

        if (!(target instanceof EntityEnderman)
                && (isWithinBowRange(bot, target) || AdaptiveThreatAnalyzer.isAerialThreat(bot, target))
                && !bot.isInWater()
                && ctx.canSeeEntityCached(target)) {
            if (RangedCombatController.equipBowIfReady(bot)) {
                RangedCombatController.engageWithBow(ctx, bot, target, state, distSq, now);
                return;
            }
            RangedCombatController.warnIfOnlyExplosiveArrows(ctx, bot, state, now);
        }
        state.bowChargeStartTick = -1L;

        MeleeCombatController.equipShieldIfAvailable(bot);
        TacticalUtilityController.updateShieldDefense(ctx, bot, target, distSq);

        if (now >= state.vengeanceSlamCooldownUntilTick
                && VengeanceBookController.hasBookEquippedOrInInventory(bot)
                && !VengeanceBookController.shouldConserveBookAgainstEasyTarget(bot, target)
                && VengeanceBookController.isValidSlamGeometry(bot, target)
                && VengeanceBookController.hasClearLinearDashCorridor(ctx.getWorld(), bot, target)) {
            EquipmentSelector.equipBookOfVengeance(bot, false);
        }
        WeaponCombatProfile profile = WeaponCombatProfile.resolve(bot.getHeldItemMainhand());
        if (profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.VENGEANCE_CASTER) {
            if (VengeanceBookController.engageWithVengeanceBook(ctx, bot, target, state, distSq, now)) {
                return;
            }
            profile = WeaponCombatProfile.resolve(bot.getHeldItemMainhand());
        }
        boolean coop = ctx.getTaskConfig().isSquadCooperation();
        double turnRangeSq = Math.max(ATTACK_RANGE_SQ, profile.getAttackRangeSq()) + 1.5D;
        boolean readySoon = bot.isAttackReady()
                || bot.getTicksSinceLastAttack() >= Math.max(0.0D, bot.getAttackCooldownPeriodTicks() - 3.0D);
        boolean canSeeTarget = ctx.canSeeEntityCached(target);
        boolean inTurnRange = distSq <= turnRangeSq && readySoon && canSeeTarget;
        boolean allowFocus = !inTurnRange
                || threats.enemyCount() >= 3
                || bot.hurtTime > 0
                || distSq <= 12.25D
                || profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.RAPID_BRAWLER
                || profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.DUELIST_AGGRESSIVE
                || directive.highPriorityFocus()
                || (target instanceof EntityCreeper creeper && creeper.getCreeperState() > 0);
        boolean waitingTurn = coop
                && inTurnRange
                && !ctx.getBlackboard().tryClaimAttackTurn(bot.getEntityId(), target.getEntityId(), now, TURN_CLAIM_TTL_TICKS, allowFocus);

        com.x4yi.automata.ai.BotLoadoutProfile loadout = ctx.getLoadoutProfile();
        if (now < state.preferSecondaryWeaponUntilTick && loadout.secondaryMeleeSlot() >= 0) {
            EquipmentSelector.equipSlotInMainHand(bot, loadout.secondaryMeleeSlot());
        } else {
            EquipmentSelector.equipBestWeapon(bot);
        }
        if (ModCompatBridge.isTwoHandedWeapon(bot.getHeldItemMainhand())) {
            MeleeCombatController.unequipOffhandForTwoHanded(bot);
        }
        if (directive.waitForLandingWhenMelee() && (target.posY - bot.posY) > 2.2D) {
            ctx.stopNavigation();
            ctx.getMovement().lookSmoothlyAt(target.posX, target.posY + target.getEyeHeight(), target.posZ);
            float fwd = distSq < directive.preferredMinRangeSq() ? -0.45F : (distSq > directive.preferredMaxRangeSq() ? 0.35F : 0.0F);
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, fwd, state.strafeDir * 0.95F, true, now);
            return;
        }
        boolean sTapActive = now < state.sTapUntilTick;
        boolean targetInvulnerable = target.hurtResistantTime > target.maxHurtResistantTime / 2;
        boolean lowCeilingGap = isLowCeilingGapTarget(ctx.getWorld(), target);
        double aimY = lowCeilingGap ? target.posY + target.height * 0.5D : target.posY + target.getEyeHeight();

        if (waitingTurn || sTapActive) {
            ctx.stopNavigation();
            bot.setSneaking(lowCeilingGap);
            ctx.getMovement().lookSmoothlyAt(target.posX, aimY, target.posZ);
            float[] input = computeMeleeSpacingInput(distSq, bot.isAttackReady(), targetInvulnerable, sTapActive, waitingTurn, state.strafeDir);
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, input[0], input[1], !sTapActive && !lowCeilingGap, now);
            return;
        }

        boolean rapidBrawler = profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.RAPID_BRAWLER;
        boolean critMeleeStyle = !rapidBrawler
                && (profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.HEAVY_BREAKER
                        || profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.STANDARD_MELEE
                        || MeleeCombatController.isAxeOrSlowCritWeapon(bot)
                        || (directive.prioritizeCritOnLanding() && !directive.fleeWhenSwelling()));
        if (!lowCeilingGap
                && critMeleeStyle
                && canSeeTarget
                && hitboxDistSq <= Math.max(CRIT_APPROACH_RANGE_SQ, profile.getAttackRangeSq() + 4.0D)) {
            if (directive.avoidMeleeRush()
                    && !bot.isAttackReady()
                    && hitboxDistSq < directive.preferredMinRangeSq()) {
                ctx.stopNavigation();
                ctx.getMovement().lookSmoothlyAt(target.posX, aimY, target.posZ);
                MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, -0.90F, state.strafeDir * 0.85F, true, now);
                return;
            }
            MeleeCombatController.engageWithAxeCrit(ctx, bot, target, state, hitboxDistSq, now, targetInvulnerable, coop);
            return;
        }

        boolean canHitLowGap = lowCeilingGap && hitboxDistSq <= LOW_GAP_ATTACK_RANGE_SQ && canSeeTarget;
        if ((hitboxDistSq <= CLOSE_COMBAT_20HZ_SQ && canSeeTarget) || canHitLowGap) {
            if (bot.isHandActive() && bot.isAttackReady()) {
                bot.resetActiveHand();
            }
            ctx.stopNavigation();
            bot.setSneaking(lowCeilingGap);
            ctx.getMovement().lookSmoothlyAt(target.posX, aimY, target.posZ);
            float[] input;
            if (lowCeilingGap) {
                float fwd = hitboxDistSq < 4.41D ? -0.75F : (hitboxDistSq > 9.0D ? 0.55F : 0.0F);
                input = new float[] {fwd, state.strafeDir * 0.55F};
            } else if (rapidBrawler) {
                float fwd = hitboxDistSq < 2.25D ? -0.45F : (hitboxDistSq > 6.25D ? 1.0F : 0.35F);
                input = new float[] {fwd, state.strafeDir * 1.0F};
                if ((bot.hurtTime > 0 || (now % 16L == 0)) && hitboxDistSq <= 16.0D) {
                    MeleeCombatController.tryContextualStrafeJump(ctx, bot, target, state);
                }
            } else if (profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.POLEARM_REACH) {
                float fwd = hitboxDistSq < profile.getIdealMinRangeSq()
                        ? -0.85F
                        : (hitboxDistSq > profile.getAttackRangeSq() ? 0.90F : 0.10F);
                input = new float[] {fwd, state.strafeDir * 0.85F};
            } else if (profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.DUELIST_AGGRESSIVE) {
                float fwd = bot.isAttackReady() ? 1.0F : (hitboxDistSq < 5.0D ? -0.75F : 0.25F);
                input = new float[] {fwd, state.strafeDir * 0.95F};
            } else {
                input = computeMeleeSpacingInput(hitboxDistSq, bot.isAttackReady(), targetInvulnerable, false, false, state.strafeDir);
            }
            boolean allowSprint = !lowCeilingGap && (rapidBrawler || profile.prefersSprintHits() || bot.isAttackReady());
            if ((rapidBrawler || profile.prefersSprintHits()) && bot.isAttackReady()) {
                bot.setSprinting(true);
            }
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, input[0], input[1], allowSprint, now);
            double effectiveRangeSq = lowCeilingGap ? LOW_GAP_ATTACK_RANGE_SQ : Math.max(ATTACK_RANGE_SQ, profile.getAttackRangeSq());
            boolean canStrikeThroughIFrames = rapidBrawler || !targetInvulnerable;
            if (hitboxDistSq <= effectiveRangeSq && bot.isAttackReady() && canStrikeThroughIFrames) {
                MeleeCombatController.performMeleeStrike(ctx, bot, target, state, now, coop, !rapidBrawler);
            }
        } else if (hitboxDistSq <= 36.0D
                && Math.abs(target.posY - bot.posY) <= 1.5D
                && bot.onGround
                && !bot.collidedHorizontally
                && canSeeTarget) {
            ctx.pauseNavigationForDirectControl();
            ctx.getMovement().lookSmoothlyAt(target.posX, aimY, target.posZ);
            bot.setSprinting(bot.getHunger() > 6.0F);
            ctx.getMovement().applyDirectStrafe(1.0F, 0.0F, 1.25F);
        } else {
            ctx.requestPathTo(target.getPosition(), true);
        }
    }

    @Nullable
    private static EntityAutomaton findNearestActiveSquadAlly(AutomatonAIContext ctx, EntityAutomaton bot) {
        EntityAutomaton nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityLivingBase e : ctx.getNearbyLivingEntities(20.0D)) {
            if (e instanceof EntityAutomaton ally
                    && ally != bot
                    && ally.isEntityAlive()
                    && !ally.isDowned()
                    && bot.isAlliedWith(ally)) {
                double dSq = bot.getDistanceSq(ally);
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    nearest = ally;
                }
            }
        }
        return nearest;
    }

    public static int computeInitialFlankSign(int entityId) {
        return (entityId & 1) == 0 ? 1 : -1;
    }

    public static float[] computeCombatStrafeInput(
            double distSq,
            boolean sTapActive,
            boolean creeperFlee,
            boolean waitingTurn,
            int flankSign) {
        int sign = flankSign >= 0 ? 1 : -1;
        if (creeperFlee) {
            return new float[] {-1.0F, sign * 0.45F};
        }
        if (sTapActive) {
            return new float[] {-0.85F, sign * 0.65F};
        }
        if (waitingTurn) {
            float forward = distSq < 7.84D ? -0.45F : (distSq > 14.44D ? 0.85F : 0.15F);
            return new float[] {forward, sign * 0.92F};
        }
        float forward = distSq < 4.0D ? -0.35F : (distSq > 9.0D ? 0.95F : 0.35F);
        return new float[] {forward, sign * 0.9F};
    }

    public static float[] computeMeleeSpacingInput(
            double distSq,
            boolean attackReady,
            boolean targetInvulnerable,
            boolean sTapActive,
            boolean waitingTurn,
            int flankSign) {
        int sign = flankSign >= 0 ? 1 : -1;
        if (sTapActive) {
            return new float[] {-0.90F, sign * 0.65F};
        }
        if (waitingTurn) {
            float forward = distSq < 9.0D ? -0.75F : (distSq > 16.0D ? 0.85F : 0.05F);
            return new float[] {forward, sign * 0.92F};
        }
        if (!attackReady || targetInvulnerable) {
            float forward = distSq < 11.56D ? -0.85F : (distSq > 16.0D ? 0.55F : -0.15F);
            return new float[] {forward, sign * 0.92F};
        }
        float forward = distSq < 5.76D ? -0.45F : (distSq > 8.41D ? 0.95F : 0.25F);
        return new float[] {forward, sign * 0.85F};
    }

    static boolean isLowCeilingGapTarget(WorldServer world, EntityLivingBase target) {
        if (target.height > 1.05F) {
            return false;
        }
        BlockPos above = target.getPosition().up();
        if (!world.isBlockLoaded(above) || world.isAirBlock(above)) {
            return false;
        }
        return world.getBlockState(above).getCollisionBoundingBox(world, above) != null;
    }

    public static float[] computeBowAimAngles(
            double startX, double startY, double startZ,
            double targetX, double targetY, double targetZ,
            double velX, double velY, double velZ) {
        return RangedCombatController.computeBowAimAngles(
                startX, startY, startZ, targetX, targetY, targetZ, velX, velY, velZ);
    }

    public static boolean isPointNearSegment2D(
            double px, double pz,
            double ax, double az,
            double bx, double bz,
            double maxRadius) {
        return RangedCombatController.isPointNearSegment2D(px, pz, ax, az, bx, bz, maxRadius);
    }

    public static void escortOwnerOrAnchor(AutomatonAIContext ctx, @Nullable EntityPlayer owner, @Nullable BlockPos anchor, boolean urgent) {
        cancelBowDraw(ctx);
        EntityAutomaton bot = ctx.getAutomaton();
        if (tryRecoverOwnedThrownWeapons(ctx, bot)) {
            return;
        }
        BlockPos targetPos = owner != null ? owner.getPosition() : anchor;
        if (targetPos == null) {
            return;
        }
        if (owner != null && !owner.onGround) {
            for (int drop = 1; drop <= 2; drop++) {
                BlockPos below = targetPos.down(drop);
                if (ctx.getWorld().getBlockState(below.down()).getMaterial().isSolid()) {
                    targetPos = below;
                    break;
                }
            }
        }
        double distSq = bot.getDistanceSq(targetPos);
        if (distSq > ESCORT_DISTANCE_SQ) {
            boolean sprintCatchUp = urgent || distSq > 100.0D || (owner != null && owner.isSprinting());
            ctx.requestPathTo(targetPos, sprintCatchUp);
        } else {
            ctx.stopNavigation();
        }
    }

    public static double computeDistanceSqToHitbox(
            double botX,
            double botEyeY,
            double botZ,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        double cx = Math.max(minX, Math.min(botX, maxX));
        double cy = Math.max(minY, Math.min(botEyeY, maxY));
        double cz = Math.max(minZ, Math.min(botZ, maxZ));
        double dx = botX - cx;
        double dy = botEyeY - cy;
        double dz = botZ - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double computeDistanceSqToHitbox(EntityAutomaton bot, EntityLivingBase target) {
        net.minecraft.util.math.AxisAlignedBB box = target.getEntityBoundingBox();
        return computeDistanceSqToHitbox(
                bot.posX,
                bot.posY + bot.getEyeHeight(),
                bot.posZ,
                box.minX,
                box.minY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.maxZ);
    }

    public static boolean tryRecoverOwnedThrownWeapons(AutomatonAIContext ctx, EntityAutomaton bot) {
        BlockPos droppedPos = com.x4yi.automata.compat.SpartanWeaponryCompat.findNearestOwnedDroppedWeaponPos(bot, 16.0D);
        if (droppedPos == null || ctx.isGoalOnCooldown(droppedPos, ctx.getWorldTime())) {
            return false;
        }
        if (bot.getDistanceSqToCenter(droppedPos) <= 2.25D) {
            com.x4yi.automata.compat.SpartanWeaponryCompat.tickThrownWeaponRecovery(bot);
            return false;
        }
        ctx.requestPathTo(droppedPos, true);
        return true;
    }

    private static void clearActiveTarget(AutomatonAIContext ctx, EntityAutomaton bot, CombatTacticsState state) {
        int prevId = state.activeTargetId;
        state.activeTargetId = -1;
        state.unseenTicks = 0;
        state.committedTargetUntilTick = -1L;
        if (prevId >= 0) {
            ctx.getBlackboard().releaseTarget(bot.getEntityId(), prevId);
            ctx.getBlackboard().releaseVengeanceSlam(bot.getEntityId(), prevId);
        }
    }

    private static void markTargetUnreachable(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            CombatTacticsState state,
            int targetId,
            long now) {
        state.unreachableEntityId = targetId;
        state.unreachableUntilTick = now + 80L;
        clearActiveTarget(ctx, bot, state);
        ctx.stopNavigation();
    }

    public static final class CombatTacticsState {
        long sTapUntilTick = -1L;
        public long bowChargeStartTick = -1L;
        long bowCooldownUntilTick = -1L;
        long lastExplosiveArrowWarnTick = -1L;
        long lastStrafeFlipTick = -1L;
        public long lastEngagedTick = -1L;
        long nextAllyCheckTick = -1L;
        int obstructedTicks = 0;
        boolean cachedNearbyAlly;
        public int activeTargetId = -1;
        long committedTargetUntilTick = -1L;
        int trackedHitTargetId = -1;
        int lowDamageStreak = 0;
        long preferSecondaryWeaponUntilTick = -1L;
        int unreachableEntityId = -1;
        long unreachableUntilTick = -1L;
        int unseenTicks = 0;
        int strafeDir;
        long vengeanceSlamCooldownUntilTick = -1L;
        long vengeanceKbCooldownUntilTick = -1L;
        int vengeanceGrappleTargetId = -1;
        int vengeanceGrappleTicksRemaining = 0;
        int vengeanceGrapplePhase = 0;

        CombatTacticsState(int initialFlankSign) {
            this.strafeDir = initialFlankSign;
        }
    }
}
