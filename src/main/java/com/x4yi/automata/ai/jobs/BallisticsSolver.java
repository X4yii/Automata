package com.x4yi.automata.ai.jobs;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class BallisticsSolver {

    private static final double BOW_SPEED = 3.0D;
    private static final double BOW_GRAVITY = 0.05D;
    private static final double PEARL_SPEED = 1.5D;
    private static final double PEARL_GRAVITY = 0.03D;
    private static final double AIR_DRAG = 0.99D;

    private BallisticsSolver() {}

    public static float[] solveBowAngles(Vec3d shooterEye, EntityLivingBase target) {
        Vec3d aim = target.getPositionVector().add(0.0D, target.height * 0.55D, 0.0D);
        Vec3d vel = new Vec3d(target.motionX, target.motionY, target.motionZ);
        return solveProjectileAngles(shooterEye, aim, vel, BOW_SPEED, BOW_GRAVITY);
    }

    public static float[] solveEnderPearlAngles(Vec3d throwerEye, Vec3d targetPos) {
        return solveProjectileAngles(throwerEye, targetPos, Vec3d.ZERO, PEARL_SPEED, PEARL_GRAVITY);
    }

    public static float[] solveProjectileAngles(
            Vec3d origin,
            Vec3d targetPos,
            Vec3d targetVelocity,
            double speed,
            double gravity) {
        double dist = origin.distanceTo(targetPos);
        double flightTicks = Math.max(1.0D, dist / speed);
        Vec3d predicted = targetPos;
        for (int iter = 0; iter < 3; iter++) {
            double dragFactor = (1.0D - Math.pow(AIR_DRAG, flightTicks)) / (1.0D - AIR_DRAG);
            predicted = targetPos.add(
                    targetVelocity.x * flightTicks * 0.85D,
                    targetVelocity.y * flightTicks * 0.5D,
                    targetVelocity.z * flightTicks * 0.85D);
            double horiz = Math.hypot(predicted.x - origin.x, predicted.z - origin.z);
            flightTicks = Math.max(1.0D, horiz / (speed * Math.max(0.45D, dragFactor / flightTicks)));
        }

        double dx = predicted.x - origin.x;
        double dz = predicted.z - origin.z;
        double dy = predicted.y - origin.y + 0.5D * gravity * flightTicks * flightTicks;
        double horizDist = Math.hypot(dx, dz);

        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = MathHelper.clamp(
                (float) (-(MathHelper.atan2(dy, Math.max(0.001D, horizDist)) * (180.0D / Math.PI))),
                -85.0F,
                85.0F);
        return new float[] { yaw, pitch };
    }
}

