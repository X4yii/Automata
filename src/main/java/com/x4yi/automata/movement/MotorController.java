package com.x4yi.automata.movement;

import net.minecraft.block.material.Material;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.PackedPos;
import com.x4yi.automata.navigation.PathNodeAction;
import com.x4yi.automata.navigation.PathResult;

public final class MotorController {

    private static final float MIN_SPRINT_HUNGER = 6.0F;
    private static final float PATH_INPUT_MAX_STEP = 0.45F;
    private static final float COMBAT_INPUT_MAX_STEP = 0.65F;

    private float smoothForward;
    private float smoothStrafe;
    private final double[] hazardPushScratch = new double[2];

    public void reset(EntityAutomaton bot) {
        smoothForward = 0.0F;
        smoothStrafe = 0.0F;
        bot.setSprinting(false);
        bot.setSneaking(false);
        bot.setMoveForward(0.0F);
        bot.setMoveStrafing(0.0F);
    }

    public static float blendMoveInput(float current, float target, float maxStep) {
        float delta = MathHelper.clamp(target - current, -maxStep, maxStep);
        return MathHelper.clamp(current + delta, -1.0F, 1.0F);
    }

    public static boolean resolveSneakState(
            boolean bridgeNeedsSneak,
            boolean lowCeilingSneak,
            boolean edgeBrakeSneak) {
        return bridgeNeedsSneak || lowCeilingSneak || edgeBrakeSneak;
    }

    public void applySneakState(EntityAutomaton bot, boolean desiredSneak) {
        if (bot.isSneaking() != desiredSneak) {
            bot.setSneaking(desiredSneak);
        }
    }

    public void applyDirectStrafe(
            EntityAutomaton bot,
            float forward,
            float strafe,
            float speedMultiplier,
            @javax.annotation.Nullable com.x4yi.automata.ai.AutomatonAIContext ctx) {
        double baseSpeed = bot.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        bot.setAIMoveSpeed((float) (baseSpeed * speedMultiplier));
        float adjFwd = forward;
        float adjStrafe = strafe;
        if (ctx != null && com.x4yi.automata.ai.ExplosiveHazardDetector.computeStaticHazardClearance(
                ctx, bot.posX, bot.posY, bot.posZ, hazardPushScratch)) {
            double mag = Math.hypot(hazardPushScratch[0], hazardPushScratch[1]);
            if (mag > 1.0E-4D) {
                double px = hazardPushScratch[0] / mag;
                double pz = hazardPushScratch[1] / mag;
                float yawRad = bot.rotationYaw * 0.017453292F;
                double fwdX = -Math.sin(yawRad);
                double fwdZ = Math.cos(yawRad);
                double rightX = Math.cos(yawRad);
                double rightZ = Math.sin(yawRad);
                float pushFwd = (float) (px * fwdX + pz * fwdZ);
                float pushStrafe = (float) (px * rightX + pz * rightZ);
                adjFwd = MathHelper.clamp(adjFwd * 0.55F + pushFwd * 0.85F, -1.0F, 1.0F);
                adjStrafe = MathHelper.clamp(adjStrafe * 0.55F + pushStrafe * 0.85F, -1.0F, 1.0F);
            }
        }
        smoothForward = blendMoveInput(smoothForward, adjFwd, COMBAT_INPUT_MAX_STEP);
        smoothStrafe = blendMoveInput(smoothStrafe, adjStrafe, COMBAT_INPUT_MAX_STEP);
        bot.setMoveForward(smoothForward);
        bot.setMoveStrafing(smoothStrafe);
    }

    public void applyDirectStrafe(EntityAutomaton bot, float forward, float strafe, float speedMultiplier) {
        applyDirectStrafe(bot, forward, strafe, speedMultiplier, null);
    }

    public void applyDirectionalMovement(
            EntityAutomaton bot,
            double steerX,
            double steerZ,
            @javax.annotation.Nullable com.x4yi.automata.ai.AutomatonAIContext ctx) {
        double mdx = steerX - bot.posX;
        double mdz = steerZ - bot.posZ;
        if (mdx * mdx + mdz * mdz < 0.0025D) {
            smoothForward = 0.0F;
            smoothStrafe = 0.0F;
            bot.setMoveForward(0.0F);
            bot.setMoveStrafing(0.0F);
            return;
        }
        if (ctx != null && com.x4yi.automata.ai.ExplosiveHazardDetector.computeStaticHazardClearance(
                ctx, bot.posX, bot.posY, bot.posZ, hazardPushScratch)) {
            double mag = Math.hypot(hazardPushScratch[0], hazardPushScratch[1]);
            if (mag > 1.0E-4D) {
                mdx += (hazardPushScratch[0] / mag) * 1.35D;
                mdz += (hazardPushScratch[1] / mag) * 1.35D;
            }
        }
        double baseSpeed = bot.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        if (bot.isSneaking()) {
            baseSpeed *= 0.35D;
        }
        bot.setAIMoveSpeed((float) baseSpeed);
        float targetYaw = (float) (MathHelper.atan2(mdz, mdx) * (180.0D / Math.PI)) - 90.0F;
        float relRad = MathHelper.wrapDegrees(targetYaw - bot.rotationYaw) * (float) (Math.PI / 180.0D);
        float targetFwd = MathHelper.cos(relRad);
        float targetStrafe = -MathHelper.sin(relRad);
        smoothForward = targetFwd;
        smoothStrafe = targetStrafe;
        bot.setMoveForward(targetFwd);
        bot.setMoveStrafing(targetStrafe);
    }

    public void applyDirectionalMovement(EntityAutomaton bot, double steerX, double steerZ) {
        applyDirectionalMovement(bot, steerX, steerZ, null);
    }

    public static boolean shouldTriggerBunnyhop(
            boolean onGround,
            boolean sprinting,
            boolean sneaking,
            boolean inWater,
            boolean standingOnFarmland,
            boolean miningActive,
            float hunger,
            boolean fastPace,
            boolean straightClearCorridor,
            float yawErrorDeg,
            boolean ceilingClear3m,
            boolean groundAheadSafe) {
        return fastPace
                && onGround
                && sprinting
                && !sneaking
                && !inWater
                && !standingOnFarmland
                && !miningActive
                && hunger > MIN_SPRINT_HUNGER
                && straightClearCorridor
                && yawErrorDeg < 18.0F
                && ceilingClear3m
                && groundAheadSafe;
    }

    public static boolean isPathSafeForBunnyhop(
            EntityAutomaton bot,
            PathResult path,
            int currentStepIndex,
            float yawErrorDeg) {
        if (path == null || currentStepIndex + 6 >= path.length() || yawErrorDeg >= 12.0F) {
            return false;
        }
        if (bot.moveForward < 0.85F || bot.collidedHorizontally) {
            return false;
        }
        if (bot.motionX * bot.motionX + bot.motionZ * bot.motionZ < 0.012D) {
            return false;
        }
        int baseY = PackedPos.unpackY(path.getStep(currentStepIndex));
        for (int i = 0; i < 4; i++) {
            int idx = currentStepIndex + i;
            if (path.getAction(idx) != PathNodeAction.WALK || PackedPos.unpackY(path.getStep(idx)) != baseY) {
                return false;
            }
        }
        return hasThreeBlockHeadroom(bot.world, bot.posX, bot.posY, bot.posZ)
                && isGroundAheadSafe(bot.world, bot.posX, bot.posY, bot.posZ, bot.rotationYaw);
    }

    public static boolean hasThreeBlockHeadroom(World world, double x, double y, double z) {
        if (world == null) {
            return true;
        }
        int bx = MathHelper.floor(x);
        int by = MathHelper.floor(y + 0.1D);
        int bz = MathHelper.floor(z);
        return CellClassifier.isPassable(world, new BlockPos(bx, by + 2, bz));
    }

    public static boolean isGroundAheadSafe(World world, double x, double y, double z, float yawDeg) {
        if (world == null) {
            return true;
        }
        float rad = yawDeg * 0.017453292F;
        double dirX = -Math.sin(rad);
        double dirZ = Math.cos(rad);
        int baseY = MathHelper.floor(y + 0.1D) - 1;
        for (int step = 1; step <= 2; step++) {
            int gx = MathHelper.floor(x + dirX * step);
            int gz = MathHelper.floor(z + dirZ * step);
            BlockPos floor = new BlockPos(gx, baseY, gz);
            BlockPos feet = floor.up();
            if (!world.isBlockLoaded(floor)) {
                return false;
            }
            Material floorMat = world.getBlockState(floor).getMaterial();
            Material feetMat = world.getBlockState(feet).getMaterial();
            if (!floorMat.isSolid() || floorMat == Material.LAVA || feetMat == Material.LAVA || feetMat == Material.WATER) {
                return false;
            }
        }
        return true;
    }

    public static boolean isStandingOnFarmland(EntityAutomaton bot) {
        BlockPos below = new BlockPos(
                MathHelper.floor(bot.posX),
                MathHelper.floor(bot.posY - 0.1D),
                MathHelper.floor(bot.posZ));
        return bot.world.getBlockState(below).getBlock() == Blocks.FARMLAND;
    }
}
