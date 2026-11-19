package com.x4yi.automata.movement;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.PackedPos;
import com.x4yi.automata.navigation.PathNodeAction;
import com.x4yi.automata.navigation.PathResult;

public final class ParkourController {

    private static final double PARKOUR_LANDING_REACH_SQ = 0.35D;
    private static final double EDGE_PROGRESS_THRESHOLD = 0.22D;

    private int settleTicks;

    public void reset() {
        settleTicks = 0;
    }

    public boolean isSettling() {
        return settleTicks > 0;
    }

    public boolean tickSettle(EntityAutomaton bot) {
        if (settleTicks <= 0) {
            return false;
        }
        settleTicks--;
        bot.setSprinting(false);
        bot.setMoveForward(0.0F);
        bot.setMoveStrafing(0.0F);
        return true;
    }

    public boolean hasReachedLanding(EntityAutomaton bot, double horizDistSq, double dy) {
        return (bot.onGround || bot.isInWater())
                && horizDistSq <= PARKOUR_LANDING_REACH_SQ
                && Math.abs(dy) <= 0.45D;
    }

    public void onLanding(EntityAutomaton bot, PathResult path, int nextStepIndex) {
        settleTicks = computeLandingSettleTicks(bot.world, path, nextStepIndex);
        if (settleTicks > 0) {
            bot.motionX *= 0.45D;
            bot.motionZ *= 0.45D;
            bot.setSprinting(false);
        }
    }

    static int computeLandingSettleTicks(World world, PathResult path, int nextStepIndex) {
        if (path == null || nextStepIndex >= path.length()) {
            return 0;
        }
        byte nextAction = path.getAction(nextStepIndex);
        if (nextAction == PathNodeAction.SPRINT_JUMP && nextStepIndex > 0) {
            long prev = path.getStep(nextStepIndex - 1);
            long next = path.getStep(nextStepIndex);
            double dx = PackedPos.unpackX(next) - PackedPos.unpackX(prev);
            double dz = PackedPos.unpackZ(next) - PackedPos.unpackZ(prev);
            if (Math.hypot(dx, dz) > 0.1D && nextStepIndex >= 2) {
                long prevPrev = path.getStep(nextStepIndex - 2);
                double pdx = PackedPos.unpackX(prev) - PackedPos.unpackX(prevPrev);
                double pdz = PackedPos.unpackZ(prev) - PackedPos.unpackZ(prevPrev);
                double dot = pdx * dx + pdz * dz;
                double mag = Math.hypot(pdx, pdz) * Math.hypot(dx, dz);
                if (mag > 0.001D && (dot / mag) < 0.76D) {
                    return 2;
                }
            }
        }
        if (world != null && nextStepIndex > 0) {
            long land = path.getStep(nextStepIndex - 1);
            BlockPos below = new BlockPos(PackedPos.unpackX(land), PackedPos.unpackY(land) - 1, PackedPos.unpackZ(land));
            int solidNeighbors = 0;
            for (EnumFacing face : EnumFacing.HORIZONTALS) {
                if (CellClassifier.isSolidSupport(world, below.offset(face))) {
                    solidNeighbors++;
                }
            }
            if (solidNeighbors == 0) {
                return 2;
            }
        }
        return 0;
    }

    public boolean isReadyForTakeoff(EntityAutomaton bot, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.001D) {
            return false;
        }
        double dirX = dx / len;
        double dirZ = dz / len;
        int supportY = MathHelper.floor(bot.posY + 0.1D) - 1;
        BlockPos currentUnder = new BlockPos(MathHelper.floor(bot.posX), supportY, MathHelper.floor(bot.posZ));
        if (!bot.world.getBlockState(currentUnder).getMaterial().isSolid()) {
            return true;
        }
        if (!isNearForwardBlockEdge(bot.posX, bot.posZ, dx, dz)) {
            return false;
        }
        BlockPos aheadEdge = new BlockPos(
                MathHelper.floor(bot.posX + dirX * 0.55D),
                supportY,
                MathHelper.floor(bot.posZ + dirZ * 0.55D));
        BlockPos furtherEdge = new BlockPos(
                MathHelper.floor(bot.posX + dirX * 0.85D),
                supportY,
                MathHelper.floor(bot.posZ + dirZ * 0.85D));
        return !bot.world.getBlockState(aheadEdge).getMaterial().isSolid()
                || !bot.world.getBlockState(furtherEdge).getMaterial().isSolid();
    }

    public boolean isLandingStepValid(World world, int stepX, int stepY, int stepZ) {
        if (stepY <= 0 || stepY >= 255) {
            return false;
        }
        BlockPos landPos = new BlockPos(stepX, stepY, stepZ);
        if (!CellClassifier.isSolidSupport(world, landPos.down())) {
            return false;
        }
        return CellClassifier.isPassable(world, landPos)
                && CellClassifier.isPassable(world, landPos.up());
    }

    public void applyAirControl(
            EntityAutomaton bot,
            double dx,
            double dy,
            double dz,
            double horizDist,
            boolean canSprint) {
        if (horizDist < 0.05D) {
            return;
        }
        double dirX = dx / horizDist;
        double dirZ = dz / horizDist;
        double forwardVel = bot.motionX * dirX + bot.motionZ * dirZ;
        double latX = -dirZ;
        double latZ = dirX;
        double lateralVel = bot.motionX * latX + bot.motionZ * latZ;
        if (Math.abs(lateralVel) > 0.01D) {
            double latDamp = Math.min(0.025D, Math.abs(lateralVel) * 0.45D) * Math.signum(lateralVel);
            bot.motionX -= latX * latDamp;
            bot.motionZ -= latZ * latDamp;
        }
        if (bot.motionY < 0.0D && dy <= 0.25D && horizDist < 0.95D) {
            bot.motionX += MathHelper.clamp(dx * 0.04D, -0.025D, 0.025D);
            bot.motionZ += MathHelper.clamp(dz * 0.04D, -0.025D, 0.025D);
            if (forwardVel > 0.14D || horizDist < 0.45D) {
                bot.motionX *= 0.68D;
                bot.motionZ *= 0.68D;
                bot.setSprinting(false);
                return;
            }
        }
        double targetSpeed = horizDist >= 3.6D ? 0.29D : (horizDist >= 2.6D ? 0.23D : 0.16D);
        bot.setSprinting(canSprint && horizDist >= 2.6D);
        if (forwardVel < targetSpeed && horizDist > 0.75D) {
            double boost = Math.min(0.015D, targetSpeed - forwardVel);
            bot.motionX += dirX * boost;
            bot.motionZ += dirZ * boost;
        }
    }

    private static boolean isNearForwardBlockEdge(double posX, double posZ, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.001D) {
            return false;
        }
        double relX = posX - (Math.floor(posX) + 0.5D);
        double relZ = posZ - (Math.floor(posZ) + 0.5D);
        double forwardProgress = relX * (dx / len) + relZ * (dz / len);
        return forwardProgress >= EDGE_PROGRESS_THRESHOLD;
    }
}
