package com.x4yi.automata.ai.jobs;

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
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.world.FakePlayerService;

final class RangedCombatController {

    private static final double ATTACK_RANGE_SQ = 9.0D;
    private static final double BOW_MAX_RANGE_SQ = 1024.0D;
    private static final long BOW_CHARGE_TICKS = 20L;

    private RangedCombatController() {}

    static void engageWithBow(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state,
            double distSq,
            long now) {
        ctx.stopNavigation();
        if (distSq <= ATTACK_RANGE_SQ && isCorneredFromTarget(ctx, bot, target)) {
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

        if (distSq < 100.0D) {
            MeleeCombatController.applyCombatStrafe(ctx, bot, target, state, -0.95F, state.strafeDir * 0.70F, true, now);
        } else {
            applyArcherFlankPositioning(ctx, bot, target, state, distSq, now);
        }

        EntityLivingBase aimTarget = target;
        float[] ballistic = computeBowAimAngles(
                bot.posX, bot.posY + 1.52D, bot.posZ,
                aimTarget.posX, aimTarget.posY + aimTarget.height * 0.55D, aimTarget.posZ,
                aimTarget.motionX, aimTarget.onGround ? 0.0D : aimTarget.motionY, aimTarget.motionZ);

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
                        aimTarget.motionX, aimTarget.onGround ? 0.0D : aimTarget.motionY, aimTarget.motionZ);
            } else {
                MeleeCombatController.applyCombatStrafe(ctx, bot, aimTarget, state, 0.0F, state.strafeDir * 1.0F, true, now);
                ctx.getMovement().snapLook(ballistic[0], ballistic[1]);
                return;
            }
        }

        ctx.getMovement().snapLook(ballistic[0], ballistic[1]);

        if (now < state.bowCooldownUntilTick) {
            return;
        }
        if (state.bowChargeStartTick < 0L) {
            state.bowChargeStartTick = now;
            bot.setActiveHand(EnumHand.MAIN_HAND);
            return;
        }
        if (now - state.bowChargeStartTick >= BOW_CHARGE_TICKS) {
            bot.motionX = 0.0D;
            bot.motionZ = 0.0D;
            ctx.getMovement().snapLook(ballistic[0], ballistic[1]);
            bot.resetActiveHand();
            int arrowSlot = findArrowSlot(bot);
            FakePlayerService.releaseBowShot(ctx.getWorld(), bot, bot.getInventory().getCombinedInventory(), arrowSlot, (int) BOW_CHARGE_TICKS);
            state.bowChargeStartTick = -1L;
            state.bowCooldownUntilTick = now + 6L;
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
        UUID ownerUuid = bot.getOwnerUuid();
        for (EntityAutomaton ally : bot.world.getEntitiesWithinAABB(EntityAutomaton.class, meleeBox)) {
            if (ally == bot || !ally.isEntityAlive() || ally.isDowned()) {
                continue;
            }
            if (ownerUuid != null && !ownerUuid.equals(ally.getOwnerUuid())) {
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
        double predX = targetX;
        double predY = targetY;
        double predZ = targetZ;
        double speed = 3.0D;
        double gravity = 0.05D;
        double t = 0.0D;

        for (int iter = 0; iter < 3; iter++) {
            double dx = predX - startX;
            double dy = predY - startY;
            double dz = predZ - startZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            t = dist / (speed * 0.96D);
            predX = targetX + velX * t;
            predY = targetY + velY * t;
            predZ = targetZ + velZ * t;
        }

        double dx = predX - startX;
        double dz = predZ - startZ;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double gravityDrop = 0.5D * gravity * t * t;
        double dy = (predY + gravityDrop) - startY;

        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(MathHelper.atan2(dy, horizDist) * (180.0D / Math.PI)));
        return new float[] {yaw, MathHelper.clamp(pitch, -85.0F, 85.0F)};
    }

    private static boolean isBowArcClear(WorldServer world, EntityAutomaton bot, EntityLivingBase target) {
        Vec3d start = new Vec3d(bot.posX, bot.posY + 1.52D, bot.posZ);
        Vec3d end = new Vec3d(target.posX, target.posY + target.height * 0.55D, target.posZ);
        double dist = start.distanceTo(end);
        Vec3d mid = new Vec3d(
                (start.x + end.x) * 0.5D,
                (start.y + end.y) * 0.5D + dist * 0.04D,
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
        AxisAlignedBB box = bot.getEntityBoundingBox()
                .union(target.getEntityBoundingBox())
                .grow(1.5D);
        UUID ownerUuid = bot.getOwnerUuid();
        for (EntityLivingBase entity : bot.world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (entity == bot || entity == target || !entity.isEntityAlive()) {
                continue;
            }
            boolean friendly = (entity instanceof EntityPlayer player && ownerUuid != null && ownerUuid.equals(player.getUniqueID()))
                    || (entity instanceof EntityAutomaton ally && ownerUuid != null && ownerUuid.equals(ally.getOwnerUuid()));
            if (!friendly) {
                continue;
            }
            if (isPointNearSegment2D(
                    entity.posX, entity.posZ,
                    bot.posX, bot.posZ,
                    target.posX, target.posZ,
                    1.15D)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static EntityLivingBase findAlternativeClearBowTarget(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase blockedTarget) {
        AxisAlignedBB box = new AxisAlignedBB(bot.getPosition()).grow(32.0D);
        EntityLivingBase best = null;
        double bestDistSq = BOW_MAX_RANGE_SQ;
        for (EntityLivingBase candidate : bot.world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (candidate == blockedTarget
                    || candidate instanceof EntityEnderman
                    || !CombatTargetingService.isValidCombatCandidate(ctx, candidate)) {
                continue;
            }
            double dSq = bot.getDistanceSq(candidate);
            if (dSq < bestDistSq
                    && bot.canEntityBeSeen(candidate)
                    && !hasFriendlyInLineOfFire(ctx, bot, candidate)
                    && isBowArcClear(ctx.getWorld(), bot, candidate)) {
                bestDistSq = dSq;
                best = candidate;
            }
        }
        return best;
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

    static boolean equipBowIfReady(EntityAutomaton bot) {
        if (findArrowSlot(bot) < 0) {
            return false;
        }
        if (com.x4yi.automata.ai.EquipmentSelector.isBowItem(bot.getHeldItemMainhand())) {
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (com.x4yi.automata.ai.EquipmentSelector.isBowItem(stack)) {
                ItemStack prev = bot.getHeldItemMainhand().copy();
                ItemStack bow = inv.extractItem(i, 1, false);
                bot.setHeldItem(EnumHand.MAIN_HAND, bow);
                if (!prev.isEmpty()) {
                    inv.insertItem(i, prev, false);
                }
                return true;
            }
        }
        return false;
    }

    static int findArrowSlot(EntityAutomaton bot) {
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() instanceof ItemArrow) {
                return i;
            }
        }
        return -1;
    }
}

