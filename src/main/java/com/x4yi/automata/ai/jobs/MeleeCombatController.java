package com.x4yi.automata.ai.jobs;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemShield;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.world.FakePlayerService;

final class MeleeCombatController {

    private static final double ATTACK_RANGE_SQ = 9.0D;
    private static final double AXE_SPACE_MIN_SQ = 6.25D;
    private static final long ALLY_CHECK_INTERVAL_TICKS = 5L;

    private MeleeCombatController() {}

    static void engageWithAxeCrit(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state,
            double distSq,
            long now,
            boolean targetInvulnerable,
            boolean coop) {
        if (bot.isHandActive()) {
            bot.resetActiveHand();
        }
        ctx.stopNavigation();
        ctx.getMovement().lookSmoothlyAt(target.posX, target.posY + target.getEyeHeight(), target.posZ);

        if (!bot.isAttackReady() && distSq < AXE_SPACE_MIN_SQ) {
            float[] input = CombatJobSupport.computeCombatStrafeInput(distSq, false, false, true, state.strafeDir);
            applyCombatStrafe(ctx, bot, target, state, input[0], input[1], true, now);
            return;
        }

        if (!bot.onGround && bot.fallDistance > 0.0F && bot.motionY < -0.05D
                && distSq <= ATTACK_RANGE_SQ && bot.isAttackReady() && !targetInvulnerable) {
            bot.setSprinting(false);
            performMeleeStrike(ctx, bot, target, state, now, coop);
            return;
        }

        if (bot.isAttackReady()) {
            bot.setSprinting(true);
            ctx.getMovement().applyDirectStrafe(1.0F, state.strafeDir * 0.25F, 1.0F);
            if (bot.onGround) {
                bot.getJumpHelper().setJumping();
            }
        } else {
            float[] input = CombatJobSupport.computeCombatStrafeInput(distSq, false, false, false, state.strafeDir);
            applyCombatStrafe(ctx, bot, target, state, input[0], input[1], true, now);
        }
    }

    static void performMeleeStrike(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state,
            long now,
            boolean coop) {
        bot.swingArm(EnumHand.MAIN_HAND);
        FakePlayerService.attackEntity(ctx.getWorld(), bot, target);
        bot.setSprinting(false);
        state.sTapUntilTick = now + 4L;
        if (coop) {
            ctx.getBlackboard().releaseAttackTurn(bot.getEntityId(), target.getEntityId(), now);
        }
    }

    static void applyCombatStrafe(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state,
            float forward,
            float strafe,
            boolean allowSprintBunnyhop,
            long now) {
        if (bot.hurtTime > 0 && now - state.lastStrafeFlipTick >= 8L) {
            state.lastStrafeFlipTick = now;
            state.strafeDir = -state.strafeDir;
            strafe = -strafe;
        } else if (now - state.lastStrafeFlipTick >= 14L) {
            state.lastStrafeFlipTick = now;
            if (bot.getRNG().nextFloat() < 0.45F) {
                state.strafeDir = -state.strafeDir;
                strafe = -strafe;
            }
        }

        double dx = target.posX - bot.posX;
        double dz = target.posZ - bot.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.001D) {
            double sx = (-dz / len) * state.strafeDir;
            double sz = (dx / len) * state.strafeDir;
            BlockPos lateralDest = new BlockPos(bot.posX + sx * 1.8D, bot.posY, bot.posZ + sz * 1.8D);
            if (!isStrafeDestinationSafe(ctx, ctx.getWorld(), lateralDest, target)
                    || hasNearbyAllyCached(bot, lateralDest, state, now)
                    || ThreatFieldEvaluator.hasOtherEnemyNear(ctx, bot, lateralDest, target)) {
                state.strafeDir = -state.strafeDir;
                strafe = -strafe;
                sx = -sx;
                sz = -sz;
                lateralDest = new BlockPos(bot.posX + sx * 1.8D, bot.posY, bot.posZ + sz * 1.8D);
            }
            if (allowSprintBunnyhop && bot.onGround && isStrafeDestinationSafe(ctx, ctx.getWorld(), lateralDest, target)
                    && (forward > 0.8F || forward < -0.8F)) {
                bot.setSprinting(bot.getHunger() > 6.0F);
                bot.getJumpHelper().setJumping();
            } else if (!allowSprintBunnyhop) {
                bot.setSprinting(false);
            }
        }
        ctx.getMovement().applyDirectStrafe(forward, strafe, 1.15F);
    }

    private static boolean hasNearbyAllyCached(
            EntityAutomaton self,
            BlockPos pos,
            CombatJobSupport.CombatTacticsState state,
            long now) {
        if (now < state.nextAllyCheckTick) {
            return state.cachedNearbyAlly;
        }
        state.nextAllyCheckTick = now + ALLY_CHECK_INTERVAL_TICKS;
        AxisAlignedBB box = new AxisAlignedBB(pos).grow(1.4D, 1.5D, 1.4D);
        boolean found = false;
        for (EntityAutomaton other : self.world.getEntitiesWithinAABB(EntityAutomaton.class, box)) {
            if (other != self && other.isEntityAlive()) {
                found = true;
                break;
            }
        }
        state.cachedNearbyAlly = found;
        return found;
    }

    static void handleCreeperEmergency(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase creeper,
            CombatJobSupport.CombatTacticsState state,
            double distSq,
            long now) {
        bot.resetActiveHand();
        state.bowChargeStartTick = -1L;
        equipShieldIfAvailable(bot);
        if (EquipmentSelector.isShieldItem(bot.getHeldItemOffhand())) {
            bot.setActiveHand(EnumHand.OFF_HAND);
        }
        ctx.stopNavigation();
        ctx.getMovement().lookSmoothlyAt(creeper.posX, creeper.posY + creeper.getEyeHeight(), creeper.posZ);
        if (distSq <= 18.0D) {
            tryPlaceCreeperShieldBlock(ctx, bot, creeper);
        }
        float[] input = CombatJobSupport.computeCombatStrafeInput(distSq, false, true, false, state.strafeDir);
        applyCombatStrafe(ctx, bot, creeper, state, input[0], input[1], true, now);
    }

    private static void tryPlaceCreeperShieldBlock(AutomatonAIContext ctx, EntityAutomaton bot, EntityLivingBase creeper) {
        double dx = creeper.posX - bot.posX;
        double dz = creeper.posZ - bot.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) {
            return;
        }
        BlockPos barrierPos = new BlockPos(bot.posX + (dx / len) * 1.1D, bot.posY, bot.posZ + (dz / len) * 1.1D);
        WorldServer world = ctx.getWorld();
        if (!world.isBlockLoaded(barrierPos) || !world.isAirBlock(barrierPos)) {
            return;
        }
        if (!EquipmentSelector.equipBuildingBlock(bot)) {
            return;
        }
        for (EnumFacing face : EnumFacing.VALUES) {
            BlockPos neighbor = barrierPos.offset(face);
            if (world.getBlockState(neighbor).getMaterial().isSolid()) {
                ctx.getMovement().lookAtBlockFace(neighbor, face.getOpposite());
                bot.swingArm(EnumHand.MAIN_HAND);
                EnumActionResult res = FakePlayerService.tryUseItemOnBlock(
                        world, bot, EnumHand.MAIN_HAND, neighbor, face.getOpposite(), 0.5F, 0.5F, 0.5F);
                if (res == EnumActionResult.SUCCESS) {
                    return;
                }
            }
        }
    }

    static void tryContextualStrafeJump(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state) {
        if (!bot.onGround) {
            return;
        }
        double dx = target.posX - bot.posX;
        double dz = target.posZ - bot.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) {
            return;
        }
        int firstDir = state.strafeDir;
        int[] dirs = {firstDir, -firstDir};
        WorldServer world = ctx.getWorld();

        for (int dir : dirs) {
            double sx = (-dz / len) * dir;
            double sz = (dx / len) * dir;
            BlockPos dest = new BlockPos(bot.posX + sx * 2.5D, bot.posY, bot.posZ + sz * 2.5D);
            BlockPos farDest = new BlockPos(bot.posX + sx * 3.5D, bot.posY, bot.posZ + sz * 3.5D);
            if (isStrafeDestinationSafe(ctx, world, dest, target)
                    && isStrafeDestinationSafe(ctx, world, farDest, target)) {
                bot.motionX += sx * 0.42D;
                bot.motionZ += sz * 0.42D;
                bot.getJumpHelper().setJumping();
                state.strafeDir = -dir;
                return;
            }
        }
        for (int dir : dirs) {
            double sx = (-dz / len) * dir;
            double sz = (dx / len) * dir;
            BlockPos dest = new BlockPos(bot.posX + sx * 2.5D, bot.posY, bot.posZ + sz * 2.5D);
            if (isStrafeDestinationSafe(ctx, world, dest, target)) {
                bot.motionX += sx * 0.42D;
                bot.motionZ += sz * 0.42D;
                bot.getJumpHelper().setJumping();
                state.strafeDir = -dir;
                return;
            }
        }
    }

    static boolean isStrafeDestinationSafe(
            AutomatonAIContext ctx,
            WorldServer world,
            BlockPos dest,
            @Nullable EntityLivingBase currentTarget) {
        if (!world.isBlockLoaded(dest)
                || !world.isAirBlock(dest)
                || !world.isAirBlock(dest.up())) {
            return false;
        }
        boolean hasSafeGround = false;
        for (int drop = 1; drop <= 3; drop++) {
            BlockPos check = dest.down(drop);
            if (world.getBlockState(check).getMaterial().isLiquid()) {
                return false;
            }
            if (world.getBlockState(check).getMaterial().isSolid()) {
                hasSafeGround = true;
                break;
            }
        }
        if (!hasSafeGround) {
            return false;
        }
        AxisAlignedBB dangerBox = new AxisAlignedBB(dest).grow(1.5D, 1.5D, 1.5D);
        for (EntityLivingBase entity : world.getEntitiesWithinAABB(EntityLivingBase.class, dangerBox)) {
            if (entity != currentTarget && CombatTargetingService.isValidCombatCandidate(ctx, entity)) {
                return false;
            }
        }
        return true;
    }

    static boolean isAxeOrSlowCritWeapon(EntityAutomaton bot) {
        return EquipmentSelector.isAxe(bot.getHeldItemMainhand()) || bot.getAttackCooldownPeriodTicks() >= 18.0D;
    }

    static void equipShieldIfAvailable(EntityAutomaton bot) {
        if (EquipmentSelector.isShieldItem(bot.getHeldItemOffhand())) {
            return;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (EquipmentSelector.isShieldItem(candidate)) {
                ItemStack prevOff = bot.getHeldItemOffhand().copy();
                ItemStack shield = inv.extractItem(i, 1, false);
                bot.setHeldItem(EnumHand.OFF_HAND, shield);
                if (!prevOff.isEmpty()) {
                    inv.insertItem(i, prevOff, false);
                }
                return;
            }
        }
    }
}

