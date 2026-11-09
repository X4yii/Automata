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
        if (state.lastEngagedTick == now) {
            return;
        }
        EntityLivingBase revenge = bot.getRevengeTarget();
        if (state.activeTargetId < 0 && revenge != null && isValidCombatCandidate(ctx, revenge)) {
            engageTarget(ctx, revenge, true);
            return;
        }
        if (state.activeTargetId < 0) {
            return;
        }
        Entity entity = bot.world.getEntityByID(state.activeTargetId);
        if (!(entity instanceof EntityLivingBase target) || !isValidCombatCandidate(ctx, target)) {
            state.activeTargetId = -1;
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
    static EntityLivingBase findNearestTarget(
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
        state.activeTargetId = target.getEntityId();
        state.lastEngagedTick = now;

        if (bot.isEating()) {
            retreatWhileEating(ctx, bot, state, now);
            return;
        }

        double distSq = bot.getDistanceSq(target);
        boolean creeperFlee = target instanceof EntityCreeper creeper
                && creeper.getCreeperState() > 0
                && distSq < CREEPER_FLEE_RANGE_SQ;
        if (creeperFlee) {
            MeleeCombatController.handleCreeperEmergency(ctx, bot, target, state, distSq, now);
            return;
        }

        ThreatFieldEvaluator.ThreatAssessment threats = ThreatFieldEvaluator.assessLocalThreats(ctx, bot);
        if (bot.getHealthRatio() < 0.35F && distSq < 64.0D && (distSq < 36.0D || !RangedCombatController.equipBowIfReady(bot))) {
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

        if (!(target instanceof EntityEnderman)
                && (isWithinBowRange(bot, target) || AdaptiveThreatAnalyzer.isAerialThreat(bot, target))
                && !bot.isInWater()
                && bot.canEntityBeSeen(target)
                && RangedCombatController.equipBowIfReady(bot)) {
            RangedCombatController.engageWithBow(ctx, bot, target, state, distSq, now);
            return;
        }
        state.bowChargeStartTick = -1L;

        MeleeCombatController.equipShieldIfAvailable(bot);
        TacticalUtilityController.updateShieldDefense(bot, target, distSq);

        boolean coop = ctx.getTaskConfig().isSquadCooperation();
        boolean inTurnRange = distSq <= 12.25D && bot.canEntityBeSeen(target);
        boolean allowFocus = !inTurnRange
                || threats.enemyCount() >= 5
                || bot.hurtTime > 0
                || distSq <= 6.25D
                || (target instanceof EntityCreeper creeper && creeper.getCreeperState() > 0);
        boolean waitingTurn = coop
                && inTurnRange
                && !ctx.getBlackboard().tryClaimAttackTurn(bot.getEntityId(), target.getEntityId(), now, TURN_CLAIM_TTL_TICKS, allowFocus);

        EquipmentSelector.equipBestWeapon(bot);
        if (com.x4yi.automata.compat.SpartanWeaponryCompat.isTwoHanded(bot.getHeldItemMainhand())) {
            MeleeCombatController.unequipOffhandForTwoHanded(bot);
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

        WeaponCombatProfile profile = WeaponCombatProfile.resolve(bot.getHeldItemMainhand());
        boolean heavyCritStyle = profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.HEAVY_BREAKER
                || MeleeCombatController.isAxeOrSlowCritWeapon(bot);
        if (!lowCeilingGap
                && heavyCritStyle
                && bot.canEntityBeSeen(target)
                && distSq <= Math.max(CRIT_APPROACH_RANGE_SQ, profile.getAttackRangeSq() + 4.0D)) {
            MeleeCombatController.engageWithAxeCrit(ctx, bot, target, state, distSq, now, targetInvulnerable, coop);
            return;
        }

        boolean canHitLowGap = lowCeilingGap && distSq <= LOW_GAP_ATTACK_RANGE_SQ && bot.canEntityBeSeen(target);
        if ((distSq <= CLOSE_COMBAT_20HZ_SQ && bot.canEntityBeSeen(target)) || canHitLowGap) {
            if (bot.isHandActive() && bot.isAttackReady()) {
                bot.resetActiveHand();
            }
            ctx.stopNavigation();
            bot.setSneaking(lowCeilingGap);
            ctx.getMovement().lookSmoothlyAt(target.posX, aimY, target.posZ);
            float[] input;
            if (lowCeilingGap) {
                float fwd = distSq < 4.41D ? -0.75F : (distSq > 9.0D ? 0.55F : 0.0F);
                input = new float[] {fwd, state.strafeDir * 0.55F};
            } else if (profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.POLEARM_REACH) {
                float fwd = distSq < profile.getIdealMinRangeSq()
                        ? -0.85F
                        : (distSq > profile.getAttackRangeSq() ? 0.90F : 0.10F);
                input = new float[] {fwd, state.strafeDir * 0.85F};
            } else if (profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.DUELIST_AGGRESSIVE) {
                float fwd = bot.isAttackReady() ? 1.0F : (distSq < 5.0D ? -0.75F : 0.25F);
                input = new float[] {fwd, state.strafeDir * 0.95F};
            } else {
                input = computeMeleeSpacingInput(distSq, bot.isAttackReady(), targetInvulnerable, false, false, state.strafeDir);
            }
            boolean allowSprint = !lowCeilingGap && (profile.prefersSprintHits() || bot.isAttackReady());
            if (profile.prefersSprintHits() && bot.isAttackReady()) {
                bot.setSprinting(true);
            }
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, input[0], input[1], allowSprint, now);
            double effectiveRangeSq = lowCeilingGap ? LOW_GAP_ATTACK_RANGE_SQ : Math.max(ATTACK_RANGE_SQ, profile.getAttackRangeSq());
            if (distSq <= effectiveRangeSq && bot.isAttackReady() && !targetInvulnerable) {
                MeleeCombatController.performMeleeStrike(ctx, bot, target, state, now, coop);
            }
        } else {
            ctx.requestPathTo(target.getPosition(), true);
        }
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
        EntityAutomaton bot = ctx.getAutomaton();
        BlockPos targetPos = owner != null ? owner.getPosition() : anchor;
        if (targetPos == null) {
            return;
        }
        double distSq = bot.getDistanceSq(targetPos);
        if (distSq > ESCORT_DISTANCE_SQ) {
            boolean sprintCatchUp = urgent || distSq > 100.0D || (owner != null && owner.isSprinting());
            ctx.requestPathTo(targetPos, sprintCatchUp);
        } else {
            ctx.stopNavigation();
        }
    }

    public static final class CombatTacticsState {
        long sTapUntilTick = -1L;
        public long bowChargeStartTick = -1L;
        long bowCooldownUntilTick = -1L;
        long lastStrafeFlipTick = -1L;
        long lastEngagedTick = -1L;
        long nextAllyCheckTick = -1L;
        int obstructedTicks = 0;
        boolean cachedNearbyAlly;
        public int activeTargetId = -1;
        int strafeDir;

        CombatTacticsState(int initialFlankSign) {
            this.strafeDir = initialFlankSign;
        }
    }
}
