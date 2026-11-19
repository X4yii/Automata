package com.x4yi.automata.ai.jobs;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArrow;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.compat.SpartanWeaponryCompat;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.world.FakePlayerService;

public final class RangedCombatController {

    private static final double ATTACK_RANGE_SQ = 9.0D;
    private static final double BOW_IDEAL_RANGE_SQ = 1024.0D;
    private static final double BOW_MAX_RANGE_SQ = 4096.0D;
    private static final double BOW_MAX_VERTICAL_RANGE = 72.0D;
    private static final long BOW_CHARGE_TICKS = 20L;

    private RangedCombatController() {}

    static void engageWithBow(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state,
            double distSq,
            long now) {
        if (distSq <= ATTACK_RANGE_SQ && isCorneredFromTarget(ctx, bot, target)) {
            ctx.stopNavigation();
            bot.resetActiveHand();
            state.bowChargeStartTick = -1L;
            ctx.getMovement().lookSmoothlyAt(target.posX, target.posY + target.getEyeHeight(), target.posZ);
            if (bot.isAttackReady() && target.hurtResistantTime <= target.maxHurtResistantTime / 2) {
                bot.setSprinting(true);
                bot.swingArm(EnumHand.MAIN_HAND);
                FakePlayerService.attackEntity(ctx.getWorld(), bot, target);
                state.sTapUntilTick = now + 4L;
            }
            MeleeCombatController.tryContextualStrafeJump(ctx, bot, target, state);
            return;
        }

        ItemStack held = bot.getHeldItemMainhand();
        WeaponCombatProfile profile = WeaponCombatProfile.resolve(held);

        if (profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.THROWING_SKIRMISHER
                && SpartanWeaponryCompat.getRemainingThrowingAmmo(held) <= 0) {
            state.bowChargeStartTick = -1L;
            if (bot.isHandActive()) {
                bot.resetActiveHand();
            }
            if (distSq > 9.0D) {
                BlockPos droppedPos = SpartanWeaponryCompat.findNearestOwnedDroppedWeaponPos(bot, 18.0D);
                if (droppedPos != null && !ctx.isGoalOnCooldown(droppedPos, now)) {
                    ctx.requestPathTo(droppedPos, true);
                    return;
                }
            }
            EquipmentSelector.equipBestWeapon(bot);
            return;
        }

        if (profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.BOOMERANG_SKIRMISHER
                && (SpartanWeaponryCompat.getRemainingThrowingAmmo(held) <= 0
                        || SpartanWeaponryCompat.hasBoomerangInFlight(bot))) {
            if (bot.isHandActive()) {
                bot.resetActiveHand();
            }
            state.bowChargeStartTick = -1L;
            if (!SpartanWeaponryCompat.hasBoomerangInFlight(bot)) {
                BlockPos droppedPos = SpartanWeaponryCompat.findNearestOwnedDroppedWeaponPos(bot, 24.0D);
                if (droppedPos != null && !ctx.isGoalOnCooldown(droppedPos, now)) {
                    ctx.requestPathTo(droppedPos, true);
                    return;
                }
            }
            ctx.stopNavigation();
            ctx.getMovement().lookSmoothlyAt(target.posX, target.posY + target.getEyeHeight(), target.posZ);
            float fwd = distSq < 100.0D ? -0.95F : (distSq > 484.0D ? 0.65F : 0.0F);
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, fwd, state.strafeDir * 0.85F, true, now);
            return;
        }

        if (now < state.bowCooldownUntilTick) {
            if (distSq < 81.0D) {
                BlockPos kiteGoal = computeKiteEscapePos(ctx, bot, target);
                if (kiteGoal != null) {
                    ctx.requestPathTo(kiteGoal, true);
                    return;
                }
            }
            ctx.stopNavigation();
            float fwd = distSq < 100.0D ? -0.95F : 0.0F;
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, fwd, state.strafeDir * 0.75F, true, now);
            return;
        }

        ctx.stopNavigation();
        if (distSq < 100.0D) {
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, -0.95F, state.strafeDir * 0.70F, true, now);
        } else if (profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.BOOMERANG_SKIRMISHER && distSq > 484.0D) {
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, 0.75F, state.strafeDir * 0.60F, true, now);
        } else if (distSq > BOW_IDEAL_RANGE_SQ) {
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, 0.55F, state.strafeDir * 0.45F, false, now);
        } else {
            applyArcherFlankPositioning(ctx, bot, target, state, distSq, now);
        }

        boolean isCrossbow = profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.CROSSBOW_MARKSMAN;
        boolean crossbowLoaded = isCrossbow && SpartanWeaponryCompat.isCrossbowLoaded(held);
        double projectileSpeed = isCrossbow
                ? SpartanWeaponryCompat.getCrossbowBoltSpeed(held) * 3.0D
                : 3.0D;

        EntityLivingBase aimTarget = target;
        float[] ballistic = computeBowAimAngles(
                bot.posX, bot.posY + 1.52D, bot.posZ,
                aimTarget.posX, aimTarget.posY + aimTarget.height * 0.55D, aimTarget.posZ,
                aimTarget.motionX, aimTarget.onGround ? 0.0D : aimTarget.motionY, aimTarget.motionZ,
                projectileSpeed);

        boolean obstructed = hasFriendlyInLineOfFire(ctx, bot, aimTarget)
                || !isBowArcClear(ctx.getWorld(), bot, aimTarget);
        if (obstructed) {
            EntityLivingBase alt = findAlternativeClearBowTarget(ctx, bot, aimTarget);
            if (alt != null) {
                aimTarget = alt;
                state.activeTargetId = alt.getEntityId();
                ballistic = computeBowAimAngles(
                        bot.posX, bot.posY + 1.52D, bot.posZ,
                        aimTarget.posX, aimTarget.posY + aimTarget.height * 0.55D, aimTarget.posZ,
                        aimTarget.motionX, aimTarget.onGround ? 0.0D : aimTarget.motionY, aimTarget.motionZ,
                        projectileSpeed);
            } else {
                state.obstructedTicks++;
                if (state.obstructedTicks > 24) {
                    state.bowChargeStartTick = -1L;
                    if (bot.isHandActive()) {
                        bot.resetActiveHand();
                    }
                    BlockPos flankPos = computeLateralFiringPos(ctx, bot, aimTarget, state.strafeDir);
                    if (flankPos != null) {
                        ctx.requestPathTo(flankPos, true);
                    } else {
                        MeleeCombatController.applyCombatStrafe(
                                ctx, bot, aimTarget, state, 0.0F, state.strafeDir * 1.0F, true, now);
                    }
                    return;
                }
                MeleeCombatController.applyCombatStrafe(ctx, bot, aimTarget, state, 0.0F, state.strafeDir * 1.0F, true, now);
                ctx.getMovement().snapLook(ballistic[0], ballistic[1]);
                return;
            }
        } else {
            state.obstructedTicks = 0;
        }

        if (shouldHoldShotForCloseMeleeAlly(ctx, bot, aimTarget)) {
            ctx.getMovement().snapLook(ballistic[0], ballistic[1]);
            MeleeCombatController.applyCombatStrafe(ctx, bot, aimTarget, state, 0.0F, state.strafeDir * 0.85F, true, now);
            return;
        }

        ctx.getMovement().snapLook(ballistic[0], ballistic[1]);

        if (state.bowChargeStartTick < 0L) {
            state.bowChargeStartTick = now;
            bot.setActiveHand(EnumHand.MAIN_HAND);
            return;
        }
        long chargeNeeded = profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.BOW_MARKSMAN
                ? (distSq < 36.0D ? 12L : BOW_CHARGE_TICKS)
                : Math.max(6L, profile.getRangedChargeTicks());
        if (isCrossbow) {
            chargeNeeded = crossbowLoaded
                    ? Math.max(2L, SpartanWeaponryCompat.getCrossbowAimTicks(held))
                    : Math.max(10L, SpartanWeaponryCompat.getCrossbowReloadTicks(held));
        }
        if (now - state.bowChargeStartTick >= chargeNeeded) {
            bot.motionX = 0.0D;
            bot.motionZ = 0.0D;
            ctx.getMovement().snapLook(ballistic[0], ballistic[1]);
            bot.resetActiveHand();
            if (isCrossbow) {
                int boltSlot = crossbowLoaded ? -1 : findBoltSlot(bot);
                FakePlayerService.fireOrLoadCrossbow(
                        ctx.getWorld(), bot, bot.getInventory().getCombinedInventory(), boltSlot, (int) chargeNeeded);
                state.bowChargeStartTick = -1L;
                state.bowCooldownUntilTick = now + (crossbowLoaded ? (distSq < 81.0D ? 12L : 6L) : 4L);
            } else {
                int ammoSlot = com.x4yi.automata.compat.ModdedWeaponCompat.findNoCubesAmmoSlot(bot, held);
                if (ammoSlot == -3 || ammoSlot == -1 && !com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesSelfAmmo(held)
                        && !com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesZeroAmmoWeapon(held)) {
                    ammoSlot = profile.isRangedSkirmishStyle() ? -1 : findArrowSlot(bot);
                }
                if (ammoSlot != -2) {
                    FakePlayerService.releaseBowShot(
                            ctx.getWorld(), bot, bot.getInventory().getCombinedInventory(), ammoSlot, (int) chargeNeeded);
                }
                state.bowChargeStartTick = -1L;
                long postShotCooldown = profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.BOOMERANG_SKIRMISHER
                        ? 10L
                        : (com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesTickRangedWeapon(held)
                                ? 4L
                                : (distSq < 81.0D ? 14L : 6L));
                state.bowCooldownUntilTick = now + postShotCooldown;
            }
            MeleeCombatController.tryContextualStrafeJump(ctx, bot, aimTarget, state);
        }
    }

    private static void applyArcherFlankPositioning(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state,
            double distSq,
            long now) {
        AxisAlignedBB meleeBox = target.getEntityBoundingBox().grow(5.5D);
        double sumX = 0.0D;
        double sumZ = 0.0D;
        int meleeCount = 0;
        for (EntityAutomaton ally : bot.world.getEntitiesWithinAABB(EntityAutomaton.class, meleeBox)) {
            if (ally == bot || !ally.isEntityAlive() || ally.isDowned() || !bot.isAlliedWith(ally)) {
                continue;
            }
            if (!(ally.getHeldItemMainhand().getItem() instanceof ItemBow)) {
                sumX += ally.posX;
                sumZ += ally.posZ;
                meleeCount++;
            }
        }
        if (meleeCount == 0) {
            return;
        }
        double cx = sumX / meleeCount;
        double cz = sumZ / meleeCount;
        double mDx = cx - target.posX;
        double mDz = cz - target.posZ;
        double bDx = bot.posX - target.posX;
        double bDz = bot.posZ - target.posZ;
        double cross = mDx * bDz - mDz * bDx;
        int flankDir = cross >= 0.0D ? 1 : -1;
        state.strafeDir = flankDir;
        float forward = distSq > 196.0D ? 0.75F : 0.0F;
        MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, forward, flankDir * 0.9F, false, now);
    }

    static float[] computeBowAimAngles(
            double startX, double startY, double startZ,
            double targetX, double targetY, double targetZ,
            double velX, double velY, double velZ) {
        return computeBowAimAngles(startX, startY, startZ, targetX, targetY, targetZ, velX, velY, velZ, 3.0D);
    }

    static float[] computeBowAimAngles(
            double startX, double startY, double startZ,
            double targetX, double targetY, double targetZ,
            double velX, double velY, double velZ,
            double speed) {
        double gravity = 0.05D;
        double drag = 0.99D;
        double oneMinusDrag = 1.0D - drag;
        double dx0 = targetX - startX;
        double dy0 = targetY - startY;
        double dz0 = targetZ - startZ;
        double t = Math.max(1.0D, Math.sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0) / speed);

        double horizDist = 0.0D;
        double requiredVy = 0.0D;
        double requiredVHoriz = speed;
        double predX = targetX;
        double predZ = targetZ;

        for (int iter = 0; iter < 10; iter++) {
            predX = targetX + velX * t;
            double predY = targetY + velY * t;
            predZ = targetZ + velZ * t;
            double dx = predX - startX;
            double dy = predY - startY;
            double dz = predZ - startZ;
            horizDist = Math.sqrt(dx * dx + dz * dz);
            double sDrag = Math.max(0.5D, (1.0D - Math.pow(drag, t)) / oneMinusDrag);
            double gravityComp = gravity * (t - sDrag) / oneMinusDrag;
            requiredVy = (dy + gravityComp) / sDrag;
            requiredVHoriz = horizDist / sDrag;
            double reqSpeed = Math.sqrt(requiredVHoriz * requiredVHoriz + requiredVy * requiredVy);
            if (Math.abs(reqSpeed - speed) < 1.0E-4D) {
                break;
            }
            t = MathHelper.clamp(t * (reqSpeed / speed), 0.5D, 80.0D);
        }

        double dx = predX - startX;
        double dz = predZ - startZ;

        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(MathHelper.atan2(requiredVy, requiredVHoriz) * (180.0D / Math.PI)));
        return new float[] {yaw, MathHelper.clamp(pitch, -85.0F, 85.0F)};
    }

    private static boolean isBowArcClear(WorldServer world, EntityAutomaton bot, EntityLivingBase target) {
        Vec3d start = new Vec3d(bot.posX, bot.posY + 1.52D, bot.posZ);
        Vec3d end = new Vec3d(target.posX, target.posY + target.height * 0.55D, target.posZ);
        double dist = start.distanceTo(end);
        double flightTicks = dist / (3.0D * 0.96D);
        double arcLift = Math.max(dist * 0.04D, 0.125D * 0.05D * flightTicks * flightTicks);
        Vec3d mid = new Vec3d(
                (start.x + end.x) * 0.5D,
                (start.y + end.y) * 0.5D + arcLift,
                (start.z + end.z) * 0.5D);
        RayTraceResult hit1 = world.rayTraceBlocks(start, mid, false, true, false);
        if (hit1 != null && hit1.typeOfHit == RayTraceResult.Type.BLOCK) {
            return false;
        }
        RayTraceResult hit2 = world.rayTraceBlocks(mid, end, false, true, false);
        return hit2 == null || hit2.typeOfHit != RayTraceResult.Type.BLOCK;
    }

    private static boolean isCorneredFromTarget(AutomatonAIContext ctx, EntityAutomaton bot, EntityLivingBase target) {
        double dx = bot.posX - target.posX;
        double dz = bot.posZ - target.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) {
            return true;
        }
        BlockPos behind = new BlockPos(bot.posX + (dx / len) * 2.0D, bot.posY, bot.posZ + (dz / len) * 2.0D);
        return !MeleeCombatController.isStrafeDestinationSafe(ctx, ctx.getWorld(), behind, target);
    }

    private static boolean hasFriendlyInLineOfFire(AutomatonAIContext ctx, EntityAutomaton bot, EntityLivingBase target) {
        List<EntityLivingBase> friendlies = ctx.getNearbyFriendlies(32.0D);
        for (int i = 0, n = friendlies.size(); i < n; i++) {
            EntityLivingBase entity = friendlies.get(i);
            if (entity == bot || entity == target || !entity.isEntityAlive()) {
                continue;
            }
            if (entity instanceof EntityAutomaton ally && ally.isDowned()) {
                continue;
            }
            if (isPointNearSegment2D(
                    entity.posX, entity.posZ,
                    bot.posX, bot.posZ,
                    target.posX, target.posZ,
                    1.15D)) {
                double dx = target.posX - bot.posX;
                double dz = target.posZ - bot.posZ;
                double horizLenSq = dx * dx + dz * dz;
                double t = horizLenSq > 1.0E-6D
                        ? MathHelper.clamp(((entity.posX - bot.posX) * dx + (entity.posZ - bot.posZ) * dz) / horizLenSq, 0.0D, 1.0D)
                        : 0.0D;
                double lineY = (bot.posY + 1.52D) + ((target.posY + target.height * 0.55D) - (bot.posY + 1.52D)) * t;
                if (lineY >= entity.posY - 0.25D && lineY <= entity.posY + entity.height + 0.55D) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static EntityLivingBase findAlternativeClearBowTarget(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase blockedTarget) {
        if (((ctx.getWorldTime() + bot.getEntityId()) % 5L) != 0L) {
            return null;
        }
        List<EntityLivingBase> living = ctx.getNearbyLivingEntities(32.0D);
        EntityLivingBase best = null;
        double bestDistSq = BOW_MAX_RANGE_SQ + BOW_MAX_VERTICAL_RANGE * BOW_MAX_VERTICAL_RANGE;
        for (int i = 0, n = living.size(); i < n; i++) {
            EntityLivingBase candidate = living.get(i);
            if (candidate == blockedTarget
                    || candidate instanceof EntityEnderman
                    || !CombatTargetingService.isValidCombatCandidate(ctx, candidate)
                    || !CombatJobSupport.isWithinBowRange(bot, candidate)) {
                continue;
            }
            double dSq = bot.getDistanceSq(candidate);
            if (dSq < bestDistSq
                    && !hasFriendlyInLineOfFire(ctx, bot, candidate)
                    && ctx.canSeeEntityCached(candidate)
                    && isBowArcClear(ctx.getWorld(), bot, candidate)) {
                bestDistSq = dSq;
                best = candidate;
            }
        }
        return best;
    }

    @Nullable
    static BlockPos computeLateralFiringPos(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            int strafeDir) {
        double dx = bot.posX - target.posX;
        double dz = bot.posZ - target.posZ;
        double baseAngle = Math.atan2(dz, dx);
        double stepAngle = (strafeDir >= 0 ? 1.0D : -1.0D) * (Math.PI * 0.36D);
        double[] radii = {11.0D, 13.0D, 9.0D};
        WorldServer world = ctx.getWorld();
        int baseY = MathHelper.floor(bot.posY + 0.2D);
        for (double r : radii) {
            for (int sign = 0; sign < 2; sign++) {
                double ang = baseAngle + (sign == 0 ? stepAngle : -stepAngle);
                int tx = MathHelper.floor(target.posX + Math.cos(ang) * r);
                int tz = MathHelper.floor(target.posZ + Math.sin(ang) * r);
                BlockPos candidate = new BlockPos(tx, baseY, tz);
                if (world.isBlockLoaded(candidate)
                        && !world.getBlockState(candidate).getMaterial().blocksMovement()
                        && world.getBlockState(candidate.down()).getMaterial().isSolid()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    static boolean shouldHoldShotForCloseMeleeAlly(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target) {
        if (target.getHealth() > 40.0F || AdaptiveThreatAnalyzer.isBossOrHighThreat(target)) {
            return false;
        }
        for (EntityLivingBase entity : ctx.getNearbyLivingEntities(32.0D)) {
            if (!(entity instanceof EntityAutomaton ally)
                    || ally == bot
                    || !ally.isEntityAlive()
                    || ally.isDowned()
                    || !bot.isAlliedWith(ally)) {
                continue;
            }
            if (ally.getHealthRatio() >= 0.45F && ally.getDistanceSq(target) <= 3.24D) {
                return true;
            }
        }
        return false;
    }

    static boolean isPointNearSegment2D(
            double px, double pz,
            double ax, double az,
            double bx, double bz,
            double maxRadius) {
        double abx = bx - ax;
        double abz = bz - az;
        double abLenSq = abx * abx + abz * abz;
        if (abLenSq < 1.0E-6D) {
            return false;
        }
        double t = ((px - ax) * abx + (pz - az) * abz) / abLenSq;
        if (t <= 0.0D || t >= 1.0D) {
            return false;
        }
        double closestX = ax + t * abx;
        double closestZ = az + t * abz;
        double dx = px - closestX;
        double dz = pz - closestZ;
        return dx * dx + dz * dz <= maxRadius * maxRadius;
    }

    public static boolean hasReadyRangedWeapon(EntityAutomaton bot) {
        if (isReadyRangedItem(bot, bot.getHeldItemMainhand())) {
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isReadyRangedItem(bot, inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    static boolean equipBowIfReady(EntityAutomaton bot) {
        ItemStack current = bot.getHeldItemMainhand();
        if (isReadyRangedItem(bot, current)) {
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (isReadyRangedItem(bot, stack)) {
                ItemStack prev = current.copy();
                ItemStack ranged = inv.extractItem(i, 1, false);
                bot.setHeldItem(EnumHand.MAIN_HAND, ranged);
                if (!prev.isEmpty()) {
                    inv.insertItem(i, prev, false);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isReadyRangedItem(EntityAutomaton bot, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (SpartanWeaponryCompat.isCrossbow(stack)) {
            return SpartanWeaponryCompat.isCrossbowLoaded(stack) || findBoltSlot(bot) >= 0;
        }
        if (SpartanWeaponryCompat.isBoomerang(stack)) {
            return true;
        }
        if (SpartanWeaponryCompat.isThrowableWeapon(stack)) {
            return SpartanWeaponryCompat.getRemainingThrowingAmmo(stack) > 0;
        }
        if (com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesRangedItem(stack)) {
            int slot = com.x4yi.automata.compat.ModdedWeaponCompat.findNoCubesAmmoSlot(bot, stack);
            if (slot >= 0 || slot == -1) {
                return true;
            }
            if (slot == -2) {
                return false;
            }
            return findArrowSlot(bot) >= 0;
        }
        if (com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesZeroAmmoWeapon(stack)) {
            return false;
        }
        return EquipmentSelector.isBowItem(stack) && findArrowSlot(bot) >= 0;
    }

    static final long EXPLOSIVE_ARROW_WARN_COOLDOWN_TICKS = 1200L;

    static int findBoltSlot(EntityAutomaton bot) {
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (SpartanWeaponryCompat.isBoltItem(stack) && !isExplosiveArrow(stack)) {
                return i;
            }
        }
        return -1;
    }

    static int findArrowSlot(EntityAutomaton bot) {
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() instanceof ItemArrow && !isExplosiveArrow(stack)) {
                return i;
            }
        }
        return -1;
    }

    static boolean isExplosiveArrow(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem().getRegistryName() != null
                && stack.getItem().getRegistryName().getPath().toLowerCase(java.util.Locale.ROOT).contains("explosive")) {
            return true;
        }
        return stack.getItem().getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("explosive");
    }

    static boolean hasOnlyExplosiveArrows(EntityAutomaton bot) {
        if (findArrowSlot(bot) >= 0) {
            return false;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() instanceof ItemArrow && isExplosiveArrow(stack)) {
                return true;
            }
        }
        return false;
    }

    static void warnIfOnlyExplosiveArrows(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            CombatJobSupport.CombatTacticsState state,
            long now) {
        if (findArrowSlot(bot) >= 0) {
            state.lastExplosiveArrowWarnTick = -1L;
            return;
        }
        if (!hasOnlyExplosiveArrows(bot)) {
            return;
        }
        CombatJobSupport.cancelBowDraw(ctx);
        if (state.lastExplosiveArrowWarnTick >= 0L
                && now - state.lastExplosiveArrowWarnTick < EXPLOSIVE_ARROW_WARN_COOLDOWN_TICKS) {
            return;
        }
        state.lastExplosiveArrowWarnTick = now;
        net.minecraft.entity.player.EntityPlayer owner = bot.getOwnerUuid() != null
                ? ctx.getWorld().getPlayerEntityByUUID(bot.getOwnerUuid())
                : null;
        if (owner != null) {
            owner.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
                    "message.automata.ranged_friendly_fire_warn",
                    bot.getName()));
        }
    }

    @javax.annotation.Nullable
    static BlockPos computeKiteEscapePos(AutomatonAIContext ctx, EntityAutomaton bot, EntityLivingBase target) {
        ThreatFieldEvaluator.ThreatAssessment threats = ThreatFieldEvaluator.assessLocalThreats(ctx, bot);
        if (threats.hasEscapeVector()) {
            BlockPos anchor = ThreatFieldEvaluator.findEscapeAnchor(ctx, bot, threats, 11.0D);
            if (anchor != null) {
                return anchor;
            }
        }
        double dx = bot.posX - target.posX;
        double dz = bot.posZ - target.posZ;
        double len = Math.hypot(dx, dz);
        if (len < 0.05D) {
            return null;
        }
        int gx = net.minecraft.util.math.MathHelper.floor(bot.posX + (dx / len) * 10.0D);
        int gz = net.minecraft.util.math.MathHelper.floor(bot.posZ + (dz / len) * 10.0D);
        BlockPos candidate = new BlockPos(gx, Math.round(bot.posY), gz);
        if (ctx.getWorld().isBlockLoaded(candidate)) {
            return candidate;
        }
        return null;
    }
}

