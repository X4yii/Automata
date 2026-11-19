package com.x4yi.automata.movement;

import javax.annotation.Nullable;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.PackedPos;
import com.x4yi.automata.navigation.PathNodeAction;
import com.x4yi.automata.navigation.PathResult;

public final class PathController {

    private static final int STUCK_CHECK_INTERVAL_TICKS = 40;
    private static final int COLLISION_STUCK_INTERVAL_TICKS = 36;
    private static final double MIN_STUCK_PROGRESS_SQ = 0.09D;
    private static final double RESUME_PATH_MAX_DIST_SQ = 16.0D;

    @Nullable
    private PathResult activePath;
    private int currentStepIndex;
    private double stuckOriginX;
    private double stuckOriginY;
    private double stuckOriginZ;
    private int stuckTicks;
    private int horizonBlockedCount;
    private boolean pausedForDirectControl;
    private long lastStuckGoal = Long.MIN_VALUE;
    private long lastStuckWaypoint = Long.MIN_VALUE;

    public void setPath(@Nullable PathResult path, EntityAutomaton bot) {
        this.activePath = path;
        this.currentStepIndex = path != null && path.length() > 1 ? 1 : 0;
        this.horizonBlockedCount = 0;
        this.pausedForDirectControl = false;
        resetStuckTracker(bot);
    }

    public void clear() {
        this.activePath = null;
        this.currentStepIndex = 0;
        this.stuckTicks = 0;
        this.horizonBlockedCount = 0;
        this.pausedForDirectControl = false;
    }

    public void pauseForDirectControl() {
        if (hasActivePath()) {
            pausedForDirectControl = true;
        }
    }

    public boolean isPausedForDirectControl() {
        return pausedForDirectControl;
    }

    public void resumeIfStillNear(EntityAutomaton bot) {
        if (!pausedForDirectControl) {
            return;
        }
        pausedForDirectControl = false;
        if (!hasActivePath()) {
            return;
        }
        long step = activePath.getStep(currentStepIndex);
        double dSq = bot.getDistanceSq(
                PackedPos.unpackX(step) + 0.5D,
                PackedPos.unpackY(step),
                PackedPos.unpackZ(step) + 0.5D);
        if (dSq > RESUME_PATH_MAX_DIST_SQ) {
            clear();
        } else {
            resetStuckTracker(bot);
        }
    }

    public boolean hasActivePath() {
        return activePath != null && currentStepIndex < activePath.length();
    }

    @Nullable
    public PathResult getActivePath() {
        return activePath;
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public void advanceStep(EntityAutomaton bot) {
        currentStepIndex++;
        horizonBlockedCount = 0;
        resetStuckTracker(bot);
    }

    public long getLastStuckGoal() {
        return lastStuckGoal;
    }

    public void clearLastStuckGoal() {
        lastStuckGoal = Long.MIN_VALUE;
    }

    public long consumeLastStuckWaypoint() {
        long val = lastStuckWaypoint;
        lastStuckWaypoint = Long.MIN_VALUE;
        return val;
    }

    public void recordManualUnstick() {
        if (activePath != null && activePath.length() > 0) {
            lastStuckGoal = activePath.getStep(activePath.length() - 1);
        }
        clear();
    }

    public void resetStuckTracker(EntityAutomaton bot) {
        this.stuckOriginX = bot.posX;
        this.stuckOriginY = bot.posY;
        this.stuckOriginZ = bot.posZ;
        this.stuckTicks = 0;
    }

    public int getStuckTicks() {
        return stuckTicks;
    }

    public boolean updateStuckTracker(EntityAutomaton bot, byte currentAction, long targetPacked) {
        boolean verticalProgress = Math.abs(bot.posY - stuckOriginY) >= 0.45D;
        if (verticalProgress || bot.getDistanceSq(stuckOriginX, stuckOriginY, stuckOriginZ) >= MIN_STUCK_PROGRESS_SQ) {
            resetStuckTracker(bot);
            return false;
        }
        stuckTicks++;
        int stuckLimit = bot.collidedHorizontally ? COLLISION_STUCK_INTERVAL_TICKS : STUCK_CHECK_INTERVAL_TICKS;
        if (currentAction == PathNodeAction.PILLAR_UP) {
            stuckLimit = Math.max(stuckLimit, 40);
        }
        if (stuckTicks >= stuckLimit) {
            lastStuckGoal = activePath.getStep(activePath.length() - 1);
            lastStuckWaypoint = targetPacked;
            clear();
            return true;
        }
        return false;
    }

    public boolean checkHorizonAndInvalidateIfBlocked(World world, int lookahead, long targetPacked) {
        if (activePath == null || world == null) {
            return false;
        }
        int end = Math.min(activePath.length(), currentStepIndex + lookahead);
        boolean blocked = false;
        for (int i = currentStepIndex; i < end; i++) {
            byte act = activePath.getAction(i);
            if (act != PathNodeAction.WALK && act != PathNodeAction.JUMP) {
                continue;
            }
            long packed = activePath.getStep(i);
            BlockPos feet = new BlockPos(PackedPos.unpackX(packed), PackedPos.unpackY(packed), PackedPos.unpackZ(packed));
            if (!world.isBlockLoaded(feet)) {
                continue;
            }
            if (!isPassableOrOpenable(world, feet) || !isPassableOrOpenable(world, feet.up())) {
                blocked = true;
                break;
            }
        }
        if (!blocked) {
            horizonBlockedCount = 0;
            return false;
        }
        if (++horizonBlockedCount >= 2) {
            lastStuckWaypoint = targetPacked;
            clear();
            return true;
        }
        return false;
    }

    private static boolean isPassableOrOpenable(World world, BlockPos pos) {
        if (CellClassifier.isPassable(world, pos)) {
            return true;
        }
        IBlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof BlockDoor || state.getBlock() instanceof BlockFenceGate;
    }
}
