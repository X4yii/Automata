package com.x4yi.automata.movement;

import net.minecraft.util.math.MathHelper;

import com.x4yi.automata.compat.WingsCompat;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Server-side wing flight and slow-descent physics ported from
 * me.paulf.wings.server.flight.FlightDefault (Wings 1.1.6, MIT License).
 */
public final class WingFlightController {

    public static final float MIN_SPEED = 0.03F;
    public static final float MAX_SPEED = 0.0715F;
    public static final float Y_BOOST = 0.05F;
    public static final float FALL_REDUCTION = 0.9F;
    public static final float PITCH_OFFSET = 30.0F;

    private boolean flying;
    private float desiredMoveForward = 1.0F;

    public boolean isFlying() {
        return flying;
    }

    public void setFlying(boolean flying) {
        this.flying = flying;
    }

    public void setDesiredMoveForward(float moveForward) {
        this.desiredMoveForward = MathHelper.clamp(moveForward, 0.0F, 1.0F);
    }

    public void tickServer(EntityAutomaton bot) {
        if (!WingsCompat.hasUsableWings(bot)) {
            flying = false;
            return;
        }
        boolean canFly = WingsCompat.canFly(bot);
        if (flying && !canFly) {
            flying = false;
        }
        if (flying) {
            applyFlightDelta(bot, bot.rotationYaw, bot.rotationPitch, desiredMoveForward);
        }
        if (WingsCompat.canLand(bot)) {
            applySlowDescent(bot);
        }
    }

    /**
     * Applies one tick of Wings' FlightDefault thrust and lift to {@code bot}.
     */
    public static void applyFlightDelta(EntityAutomaton bot, float yawDeg, float pitchDeg, float moveForward) {
        double[] d = computeFlightDelta(yawDeg, pitchDeg, moveForward);
        bot.motionX += d[0];
        bot.motionY += d[1];
        bot.motionZ += d[2];
    }

    /**
     * Pure calculation of Wings' FlightDefault per-tick velocity delta {dx, dy, dz}.
     */
    public static double[] computeFlightDelta(float yawDeg, float pitchDeg, float moveForward) {
        float speed = (float) MathHelper.clampedLerp(MIN_SPEED, MAX_SPEED, moveForward);
        float elevationBoost = transform(Math.abs(pitchDeg), 45.0F, 90.0F, 1.0F, 0.0F);
        float pitch = -(float) Math.toRadians(pitchDeg - PITCH_OFFSET * elevationBoost);
        float yaw = -(float) Math.toRadians(yawDeg) - (float) Math.PI;
        float vxz = -MathHelper.cos(pitch);
        float vy = MathHelper.sin(pitch);
        float vz = MathHelper.cos(yaw);
        float vx = MathHelper.sin(yaw);
        double dx = vx * vxz * speed;
        double dy = vy * speed + Y_BOOST * (pitchDeg > 0.0F ? elevationBoost : 1.0D);
        double dz = vz * vxz * speed;
        return new double[] { dx, dy, dz };
    }

    /**
     * Applies Wings' FlightDefault slow-descent damping and fall-distance reset.
     */
    public static void applySlowDescent(EntityAutomaton bot) {
        if (bot.motionY < 0.0D) {
            bot.motionY *= FALL_REDUCTION;
        }
        bot.fallDistance = 0.0F;
    }

    public static void steerToward(EntityAutomaton bot, double targetX, double targetY, double targetZ, float maxTurnDeg) {
        double dx = targetX - bot.posX;
        double dy = targetY - (bot.posY + bot.getEyeHeight());
        double dz = targetZ - bot.posZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float desiredPitch = (float) -(Math.atan2(dy, Math.max(0.001D, horiz)) * (180.0D / Math.PI));
        bot.rotationYaw = approachAngle(bot.rotationYaw, desiredYaw, maxTurnDeg);
        bot.rotationYawHead = bot.rotationYaw;
        bot.rotationPitch = MathHelper.clamp(approachAngle(bot.rotationPitch, desiredPitch, maxTurnDeg), -80.0F, 80.0F);
    }

    private static float approachAngle(float current, float target, float maxDelta) {
        float diff = MathHelper.wrapDegrees(target - current);
        if (diff > maxDelta) {
            diff = maxDelta;
        } else if (diff < -maxDelta) {
            diff = -maxDelta;
        }
        return current + diff;
    }

    private static float transform(float value, float domainMin, float domainMax, float rangeMin, float rangeMax) {
        float t = MathHelper.clamp((value - domainMin) / (domainMax - domainMin), 0.0F, 1.0F);
        return rangeMin + t * (rangeMax - rangeMin);
    }
}
