package com.x4yi.automata.ai.jobs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.MathHelper;
import org.junit.jupiter.api.Test;

class RangedBallisticsRigorousTest {

    @Test
    void simulatedArrowHitsTargetsAcrossShortMediumLongAndVerticalRanges() {
        assertArrowHits(16.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.35D);
        assertArrowHits(32.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.35D);
        assertArrowHits(48.0D, 20.0D, -16.0D, 0.12D, 0.0D, 0.05D, 0.65D);
        assertArrowHits(24.0D, 40.0D, 12.0D, 0.0D, 0.0D, 0.0D, 0.65D);
        assertArrowHits(24.0D, -40.0D, -12.0D, 0.0D, 0.0D, 0.0D, 0.65D);
    }

    private static void assertArrowHits(
            double tx, double ty, double tz,
            double vxTarget, double vyTarget, double vzTarget,
            double maxMissRadius) {
        double sx = 0.0D;
        double sy = 1.52D;
        double sz = 0.0D;

        float[] angles = RangedCombatController.computeBowAimAngles(
                sx, sy, sz,
                tx, ty, tz,
                vxTarget, vyTarget, vzTarget);

        float yawRad = angles[0] * 0.017453292F;
        float pitchRad = angles[1] * 0.017453292F;
        double speed = 3.0D;
        double vx = -MathHelper.sin(yawRad) * MathHelper.cos(pitchRad) * speed;
        double vy = -MathHelper.sin(pitchRad) * speed;
        double vz = MathHelper.cos(yawRad) * MathHelper.cos(pitchRad) * speed;

        double ax = sx;
        double ay = sy;
        double az = sz;
        double curTx = tx;
        double curTy = ty;
        double curTz = tz;

        double minDist = Double.MAX_VALUE;
        for (int tick = 0; tick < 80; tick++) {
            double nextAx = ax + vx;
            double nextAy = ay + vy;
            double nextAz = az + vz;
            double segDx = nextAx - ax;
            double segDy = nextAy - ay;
            double segDz = nextAz - az;
            double segLenSq = segDx * segDx + segDy * segDy + segDz * segDz;
            double proj = segLenSq > 1.0E-9D
                    ? MathHelper.clamp(((curTx - ax) * segDx + (curTy - ay) * segDy + (curTz - az) * segDz) / segLenSq, 0.0D, 1.0D)
                    : 0.0D;
            double cx = ax + segDx * proj;
            double cy = ay + segDy * proj;
            double cz = az + segDz * proj;
            double d = Math.sqrt((cx - curTx) * (cx - curTx) + (cy - curTy) * (cy - curTy) + (cz - curTz) * (cz - curTz));
            if (d < minDist) {
                minDist = d;
            }
            ax = nextAx;
            ay = nextAy;
            az = nextAz;
            vx *= 0.99D;
            vy = vy * 0.99D - 0.05D;
            vz *= 0.99D;

            curTx += vxTarget;
            curTy += vyTarget;
            curTz += vzTarget;
        }
        assertTrue(minDist <= maxMissRadius,
                "Expected arrow to hit within " + maxMissRadius + "m, closest approach was " + minDist + "m");
    }
}
