package com.x4yi.automata.movement;

import net.minecraft.util.math.MathHelper;

import com.x4yi.automata.entity.EntityAutomaton;

public final class CameraController {

    private static final float YAW_NORMAL_ALPHA = 0.18F;
    private static final float PITCH_NORMAL_ALPHA = 0.18F;
    private static final float YAW_NORMAL_MAX_DEG = 14.0F;
    private static final float PITCH_NORMAL_MAX_DEG = 10.0F;

    private static final float YAW_FAST_ALPHA = 0.40F;
    private static final float PITCH_FAST_ALPHA = 0.36F;
    private static final float YAW_FAST_MAX_DEG = 32.0F;
    private static final float PITCH_FAST_MAX_DEG = 24.0F;

    private static final float BLOCK_YAW_ALPHA = 0.45F;
    private static final float BLOCK_PITCH_ALPHA = 0.42F;
    private static final float BLOCK_YAW_MAX_DEG = 36.0F;
    private static final float BLOCK_PITCH_MAX_DEG = 30.0F;

    private static final float BODY_MAX_LAG_DEG = 32.0F;

    private CameraController() {}

    public static void lookSmoothlyAt(
            EntityAutomaton bot,
            double targetX,
            double targetY,
            double targetZ,
            MovementPace pace) {
        double dx = targetX - bot.posX;
        double dy = targetY - (bot.posY + bot.getEyeHeight());
        double dz = targetZ - bot.posZ;
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float desiredPitch = MathHelper.clamp(
                (float) (-(MathHelper.atan2(dy, horizDist) * (180.0D / Math.PI))),
                -85.0F,
                85.0F);

        boolean fast = pace == MovementPace.FAST;
        float yawAlpha = fast ? YAW_FAST_ALPHA : YAW_NORMAL_ALPHA;
        float pitchAlpha = fast ? PITCH_FAST_ALPHA : PITCH_NORMAL_ALPHA;
        float yawMax = fast ? YAW_FAST_MAX_DEG : YAW_NORMAL_MAX_DEG;
        float pitchMax = fast ? PITCH_FAST_MAX_DEG : PITCH_NORMAL_MAX_DEG;

        bot.rotationYaw = interpolateAngleCapped(bot.rotationYaw, desiredYaw, yawAlpha, yawMax);
        bot.rotationYawHead = bot.rotationYaw;
        bot.rotationPitch = interpolateAngleCapped(bot.rotationPitch, desiredPitch, pitchAlpha, pitchMax);
        updateBodyYaw(bot, bot.rotationYaw, bot.isSprinting());
    }

    public static void lookAtBlockPoint(EntityAutomaton bot, double targetX, double targetY, double targetZ) {
        double dx = targetX - bot.posX;
        double dy = targetY - (bot.posY + bot.getEyeHeight());
        double dz = targetZ - bot.posZ;
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float desiredPitch = MathHelper.clamp(
                (float) (-(MathHelper.atan2(dy, horizDist) * (180.0D / Math.PI))),
                -85.0F,
                85.0F);

        bot.rotationYaw = interpolateAngleCapped(bot.rotationYaw, desiredYaw, BLOCK_YAW_ALPHA, BLOCK_YAW_MAX_DEG);
        bot.rotationYawHead = bot.rotationYaw;
        bot.rotationPitch = interpolateAngleCapped(bot.rotationPitch, desiredPitch, BLOCK_PITCH_ALPHA, BLOCK_PITCH_MAX_DEG);
        updateBodyYaw(bot, bot.rotationYaw, false);
    }

    public static void snapLook(EntityAutomaton bot, float yaw, float pitch) {
        bot.rotationYaw = yaw;
        bot.rotationYawHead = yaw;
        bot.renderYawOffset = yaw;
        bot.rotationPitch = MathHelper.clamp(pitch, -85.0F, 85.0F);
    }

    public static void updateBodyYaw(EntityAutomaton bot, float headYaw, boolean sprinting) {
        float diff = MathHelper.wrapDegrees(headYaw - bot.renderYawOffset);
        if (Math.abs(diff) > BODY_MAX_LAG_DEG) {
            bot.renderYawOffset = headYaw - Math.copySign(BODY_MAX_LAG_DEG, diff);
        }
        float bodyAlpha = sprinting ? 0.35F : 0.22F;
        bot.renderYawOffset = interpolateAngleCapped(bot.renderYawOffset, headYaw, bodyAlpha, 18.0F);
    }

    public static float interpolateAngleCapped(float current, float target, float alpha, float maxStepDeg) {
        float delta = MathHelper.wrapDegrees(target - current);
        float step = MathHelper.clamp(delta * alpha, -maxStepDeg, maxStepDeg);
        return current + step;
    }

    public static boolean isAlignedWithPoint(
            EntityAutomaton bot,
            double targetX,
            double targetY,
            double targetZ,
            float maxYawDiffDeg,
            float maxPitchDiffDeg) {
        double dx = targetX - bot.posX;
        double dy = targetY - (bot.posY + bot.getEyeHeight());
        double dz = targetZ - bot.posZ;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float desiredPitch = MathHelper.clamp(
                (float) (-(MathHelper.atan2(dy, horizDist) * (180.0D / Math.PI))),
                -85.0F,
                85.0F);
        float yawDiff = Math.abs(MathHelper.wrapDegrees(desiredYaw - bot.rotationYaw));
        float pitchDiff = Math.abs(desiredPitch - bot.rotationPitch);
        if (Math.abs(desiredPitch) >= 65.0F) {
            return pitchDiff <= maxPitchDiffDeg;
        }
        return yawDiff <= maxYawDiffDeg && pitchDiff <= maxPitchDiffDeg;
    }
}
