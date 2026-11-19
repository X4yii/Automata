package com.x4yi.automata.movement;

import javax.annotation.Nullable;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.PackedPos;
import com.x4yi.automata.navigation.PathNodeAction;
import com.x4yi.automata.navigation.PathResult;

public class HumanMovementController {

    private static final double WAYPOINT_REACH_SQ = 0.81D;
    private static final double ASCEND_WAYPOINT_REACH_SQ = 0.42D;
    private static final double ASCEND_MAX_DY = 0.25D;
    private static final double ASCEND_MAX_OVERSHOOT_DY = 0.65D;
    private static final double STEP_JUMP_AXIS_REACH = 1.05D;
    private static final float MIN_SPRINT_HUNGER = 6.0F;
    private static final float MAX_JUMP_YAW_ERROR_DEG = 25.0F;
    private static final double TERRAIN_MINING_REACH_SQ = 20.25D;
    private static final double BRIDGE_SNEAK_DIST_SQ = 2.56D;
    private static final double BRIDGE_PLACE_DIST_SQ = 2.25D;
    private static final double BRIDGE_HOLD_DIST_SQ = 1.15D;
    private static final double PILLAR_CENTER_TOLERANCE = 0.28D;

    private final EntityAutomaton automaton;
    private final BlockPlacementService placementService = new BlockPlacementService();
    private final PathController pathController = new PathController();
    private final ParkourController parkourController = new ParkourController();
    private final MotorController motorController = new MotorController();

    private MovementPace pace = MovementPace.NORMAL;
    private boolean directSteerThisTick;
    private boolean directControlActive;
    private int directControlGraceTicks;
    private int actionLookLockTicks;
    private boolean bridgeSneakRequested;

    public HumanMovementController(EntityAutomaton automaton) {
        this.automaton = automaton;
    }

    public void setPath(@Nullable PathResult path) {
        pathController.setPath(path, automaton);
        parkourController.reset();
    }

    public void clearPath() {
        pathController.clear();
        parkourController.reset();
        directSteerThisTick = false;
        directControlActive = false;
        directControlGraceTicks = 0;
        motorController.reset(automaton);
    }

    public void pausePathForDirectControl() {
        pathController.pauseForDirectControl();
    }

    public boolean isDirectControlActive() {
        return directControlActive;
    }

    public void applyDirectStrafe(float forward, float strafe, float speedMultiplier) {
        com.x4yi.automata.ai.AutomatonAIContext ctx =
                automaton.getBrain() != null ? automaton.getBrain().getContext() : null;
        motorController.applyDirectStrafe(automaton, forward, strafe, speedMultiplier, ctx);
        this.directSteerThisTick = true;
        this.directControlActive = true;
        this.directControlGraceTicks = 2;
    }

    public void moveDirectlyToward(double targetX, double targetZ, float speedMultiplier, boolean sprint) {
        double mdx = targetX - automaton.posX;
        double mdz = targetZ - automaton.posZ;
        if (mdx * mdx + mdz * mdz < 0.04D) {
            this.directSteerThisTick = false;
            this.directControlActive = false;
            this.directControlGraceTicks = 0;
            motorController.reset(automaton);
            return;
        }
        CameraController.lookSmoothlyAt(
                automaton, targetX, automaton.posY + automaton.getEyeHeight(), targetZ,
                sprint ? MovementPace.FAST : pace);
        boolean canSprint = sprint && automaton.getHunger() > MIN_SPRINT_HUNGER;
        automaton.setSprinting(canSprint);
        float targetYaw = (float) (MathHelper.atan2(mdz, mdx) * (180.0D / Math.PI)) - 90.0F;
        float relRad = MathHelper.wrapDegrees(targetYaw - automaton.rotationYaw) * (float) (Math.PI / 180.0D);
        applyDirectStrafe(MathHelper.cos(relRad), -MathHelper.sin(relRad), speedMultiplier);
    }

    public long getLastStuckGoal() {
        return pathController.getLastStuckGoal();
    }

    public void clearLastStuckGoal() {
        pathController.clearLastStuckGoal();
    }

    public void nudgeUnstick() {
        pathController.recordManualUnstick();
        clearPath();
    }

    public void setPace(MovementPace pace) {
        this.pace = pace == null ? MovementPace.NORMAL : pace;
    }

    public MovementPace getPace() {
        return pace;
    }

    public boolean hasActivePath() {
        return pathController.hasActivePath();
    }

    @Nullable
    public PathResult getActivePath() {
        return pathController.getActivePath();
    }

    public long consumeLastStuckWaypoint() {
        return pathController.consumeLastStuckWaypoint();
    }

    public void tick(boolean urgent) {
        if (actionLookLockTicks > 0) {
            actionLookLockTicks--;
        }
        if (automaton.isDowned()) {
            directSteerThisTick = false;
            directControlActive = false;
            directControlGraceTicks = 0;
            actionLookLockTicks = 0;
            motorController.reset(automaton);
            return;
        }
        if (directSteerThisTick) {
            directSteerThisTick = false;
            if (directControlGraceTicks > 0) {
                directControlGraceTicks--;
            }
            return;
        }
        if (directControlGraceTicks > 0) {
            directControlGraceTicks--;
            return;
        }
        directControlActive = false;
        pathController.resumeIfStillNear(automaton);

        PathResult activePath = pathController.getActivePath();
        if (activePath == null || !pathController.hasActivePath()) {
            motorController.reset(automaton);
            return;
        }

        int stepIdx = pathController.getCurrentStepIndex();
        long targetPacked = activePath.getStep(stepIdx);
        int stepX = PackedPos.unpackX(targetPacked);
        int stepY = PackedPos.unpackY(targetPacked);
        int stepZ = PackedPos.unpackZ(targetPacked);
        double targetX = stepX + 0.5D;
        double targetY = stepY;
        double targetZ = stepZ + 0.5D;
        double dx = targetX - automaton.posX;
        double dy = targetY - automaton.posY;
        double dz = targetZ - automaton.posZ;
        double horizDistSq = dx * dx + dz * dz;

        byte currentAction = activePath.getAction(stepIdx);
        boolean ascendingStep = dy > ASCEND_MAX_DY;
        boolean reachedWaypoint = currentAction == PathNodeAction.SPRINT_JUMP
                ? parkourController.hasReachedLanding(automaton, horizDistSq, dy)
                : (ascendingStep ? hasReachedAscendWaypoint(horizDistSq, dy)
                        : horizDistSq <= WAYPOINT_REACH_SQ && Math.abs(dy) <= 1.2D);
        if (!reachedWaypoint
                && currentAction == PathNodeAction.WALK
                && stepIdx + 1 < activePath.length()
                && activePath.getAction(stepIdx + 1) == PathNodeAction.WALK) {
            long nextPacked = activePath.getStep(stepIdx + 1);
            if (PackedPos.unpackY(nextPacked) == stepY) {
                double nextDistSq = automaton.getDistanceSq(
                        PackedPos.unpackX(nextPacked) + 0.5D,
                        automaton.posY,
                        PackedPos.unpackZ(nextPacked) + 0.5D);
                if (nextDistSq < horizDistSq) {
                    reachedWaypoint = true;
                }
            }
        }

        if (reachedWaypoint) {
            if (currentAction == PathNodeAction.SPRINT_JUMP) {
                parkourController.onLanding(automaton, activePath, stepIdx + 1);
            }
            pathController.advanceStep(automaton);
            if (!pathController.hasActivePath()) {
                clearPath();
                return;
            }
            stepIdx = pathController.getCurrentStepIndex();
            targetPacked = activePath.getStep(stepIdx);
            stepX = PackedPos.unpackX(targetPacked);
            stepY = PackedPos.unpackY(targetPacked);
            stepZ = PackedPos.unpackZ(targetPacked);
            targetX = stepX + 0.5D;
            targetY = stepY;
            targetZ = stepZ + 0.5D;
            dx = targetX - automaton.posX;
            dy = targetY - automaton.posY;
            dz = targetZ - automaton.posZ;
            horizDistSq = dx * dx + dz * dz;
            currentAction = activePath.getAction(stepIdx);
        }

        placementService.tickCooldown();
        if (pathController.updateStuckTracker(automaton, currentAction, targetPacked)) {
            parkourController.reset();
            motorController.reset(automaton);
            return;
        }
        if ((automaton.ticksExisted % 5) == 0
                && pathController.checkHorizonAndInvalidateIfBlocked(automaton.world, 3, targetPacked)) {
            parkourController.reset();
            motorController.reset(automaton);
            return;
        }

        bridgeSneakRequested = false;
        if (!handleTerrainModificationStep(activePath, stepIdx, currentAction, stepX, stepY, stepZ)) {
            motorController.applySneakState(automaton, MotorController.resolveSneakState(bridgeSneakRequested, false, false));
            return;
        }

        double steerX = targetX;
        double steerZ = targetZ;
        if (currentAction == PathNodeAction.WALK && stepIdx + 1 < activePath.length()) {
            long nextPacked = activePath.getStep(stepIdx + 1);
            if (PackedPos.unpackY(nextPacked) == stepY
                    && activePath.getAction(stepIdx + 1) == PathNodeAction.WALK
                    && horizDistSq < 1.69D) {
                steerX = targetX * 0.60D + (PackedPos.unpackX(nextPacked) + 0.5D) * 0.40D;
                steerZ = targetZ * 0.60D + (PackedPos.unpackZ(nextPacked) + 0.5D) * 0.40D;
            }
        }

        boolean nextIsParkour = stepIdx + 1 < activePath.length()
                && activePath.getAction(stepIdx + 1) == PathNodeAction.SPRINT_JUMP;
        boolean needsStepJump = currentAction == PathNodeAction.JUMP
                || currentAction == PathNodeAction.SPRINT_JUMP
                || nextIsParkour
                || dy > 0.1D;
        int lookIdx = needsStepJump
                ? (nextIsParkour && currentAction == PathNodeAction.WALK ? stepIdx + 1 : stepIdx)
                : Math.min(stepIdx + 2, activePath.length() - 1);
        long lookPacked = activePath.getStep(lookIdx);
        boolean fastPace = urgent || pace == MovementPace.FAST || needsStepJump;
        if (actionLookLockTicks <= 0) {
            CameraController.lookSmoothlyAt(
                    automaton,
                    PackedPos.unpackX(lookPacked) + 0.5D,
                    PackedPos.unpackY(lookPacked) + automaton.getEyeHeight(),
                    PackedPos.unpackZ(lookPacked) + 0.5D,
                    fastPace ? MovementPace.FAST : MovementPace.NORMAL);
        }

        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float yawError = Math.abs(MathHelper.wrapDegrees(desiredYaw - automaton.rotationYaw));

        if (parkourController.tickSettle(automaton)) {
            motorController.applySneakState(automaton, MotorController.resolveSneakState(bridgeSneakRequested, false, true));
            pathController.resetStuckTracker(automaton);
            return;
        }

        double horizDist = Math.sqrt(horizDistSq);
        boolean atParkourEdge = currentAction == PathNodeAction.SPRINT_JUMP
                && automaton.onGround
                && parkourController.isReadyForTakeoff(automaton, dx, dz);
        if (atParkourEdge && yawError >= 18.0F) {
            automaton.setSprinting(false);
            automaton.setMoveForward(0.0F);
            automaton.setMoveStrafing(0.0F);
            motorController.applySneakState(automaton, MotorController.resolveSneakState(bridgeSneakRequested, false, true));
            return;
        }

        boolean miningActive = isMiningActive();
        boolean standingOnFarmland = MotorController.isStandingOnFarmland(automaton);
        boolean canSprint = automaton.getHunger() > MIN_SPRINT_HUNGER;
        boolean parkourNeedsSprint = currentAction == PathNodeAction.SPRINT_JUMP && horizDist >= 2.6D;
        boolean shouldSprint = !miningActive && canSprint && !standingOnFarmland
                && (fastPace || parkourNeedsSprint || horizDistSq > 256.0D);
        automaton.setSprinting(shouldSprint);
        com.x4yi.automata.ai.AutomatonAIContext aiCtx =
                automaton.getBrain() != null ? automaton.getBrain().getContext() : null;
        motorController.applyDirectionalMovement(automaton, steerX, steerZ, aiCtx);

        if (currentAction == PathNodeAction.SPRINT_JUMP && !automaton.onGround && !automaton.isInWater()) {
            parkourController.applyAirControl(automaton, dx, dy, dz, horizDist, canSprint);
        }

        motorController.applySneakState(automaton, MotorController.resolveSneakState(bridgeSneakRequested, false, false));

        if (automaton.isInWater()) {
            SwimController.tickWaterNavigation(
                    automaton, this, placementService, stepX, stepY, stepZ, targetY,
                    miningActive, canSprint, pathController.getStuckTicks());
        } else if (automaton.onGround && !miningActive) {
            if (currentAction == PathNodeAction.PILLAR_UP) {
                automaton.getJumpHelper().setJumping();
            } else if (currentAction == PathNodeAction.JUMP
                    && shouldTriggerStepJump(dx, dz, yawError, automaton.collidedHorizontally)) {
                automaton.getJumpHelper().setJumping();
            } else if (!standingOnFarmland && atParkourEdge && yawError < 18.0F) {
                if (!parkourController.isLandingStepValid(automaton.world, stepX, stepY, stepZ)) {
                    nudgeUnstick();
                    return;
                }
                motorController.applySneakState(automaton, false);
                automaton.getJumpHelper().setJumping();
            } else if (!standingOnFarmland && automaton.collidedHorizontally
                    && (dy > 0.05D || pathController.getStuckTicks() >= 10)
                    && canJumpObstacleAhead(dx, dz)) {
                automaton.getJumpHelper().setJumping();
            } else if (MotorController.shouldTriggerBunnyhop(
                    automaton.onGround,
                    automaton.isSprinting(),
                    automaton.isSneaking(),
                    automaton.isInWater(),
                    standingOnFarmland,
                    miningActive,
                    automaton.getHunger(),
                    fastPace,
                    MotorController.isPathSafeForBunnyhop(automaton, activePath, stepIdx, yawError),
                    yawError,
                    MotorController.hasThreeBlockHeadroom(automaton.world, automaton.posX, automaton.posY, automaton.posZ),
                    MotorController.isGroundAheadSafe(automaton.world, automaton.posX, automaton.posY, automaton.posZ, automaton.rotationYaw))) {
                automaton.getJumpHelper().setJumping();
            }
        }
        if (pathController.getStuckTicks() >= 8 && automaton.collidedHorizontally) {
            float nudgeStrafe = (automaton.getEntityId() & 1) == 0 ? 0.65F : -0.65F;
            automaton.setMoveStrafing(MathHelper.clamp(automaton.moveStrafing + nudgeStrafe, -1.0F, 1.0F));
        }
    }

    private boolean canJumpObstacleAhead(double dx, double dz) {
        BlockPos selfFeet = new BlockPos(
                MathHelper.floor(automaton.posX),
                MathHelper.floor(automaton.posY + 0.1D),
                MathHelper.floor(automaton.posZ));
        BlockPos aboveHead = selfFeet.up(2);
        if (automaton.world.isBlockLoaded(aboveHead)
                && automaton.world.getBlockState(aboveHead).getCollisionBoundingBox(automaton.world, aboveHead) != null) {
            return false;
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) {
            return false;
        }
        BlockPos aheadHead = new BlockPos(
                MathHelper.floor(automaton.posX + (dx / len) * 0.85D),
                selfFeet.getY() + 1,
                MathHelper.floor(automaton.posZ + (dz / len) * 0.85D));
        return CellClassifier.isPassable(automaton.world, aheadHead)
                && CellClassifier.isPassable(automaton.world, aheadHead.up());
    }

    static boolean hasReachedAscendWaypoint(double horizDistSq, double dy) {
        return horizDistSq <= ASCEND_WAYPOINT_REACH_SQ && dy <= ASCEND_MAX_DY && dy >= -ASCEND_MAX_OVERSHOOT_DY;
    }

    static boolean shouldTriggerStepJump(double dx, double dz, float yawError, boolean collidedHorizontally) {
        if (collidedHorizontally) {
            return yawError < 90.0F;
        }
        return Math.abs(dx) <= STEP_JUMP_AXIS_REACH && Math.abs(dz) <= STEP_JUMP_AXIS_REACH && yawError < 45.0F;
    }

    private boolean handleTerrainModificationStep(
            PathResult path,
            int stepIdx,
            byte action,
            int stepX,
            int stepY,
            int stepZ) {
        if (!(automaton.world instanceof WorldServer worldServer)) {
            return true;
        }
        if (action == PathNodeAction.WALK || action == PathNodeAction.JUMP) {
            BlockPos feetPos = new BlockPos(stepX, stepY, stepZ);
            if (worldServer.isBlockLoaded(feetPos)) {
                TerrainBreaker.tryOpenDoorOrGate(worldServer, automaton, this, feetPos, worldServer.getBlockState(feetPos));
            }
            BlockPos headPos = feetPos.up();
            if (worldServer.isBlockLoaded(headPos)) {
                TerrainBreaker.tryOpenDoorOrGate(worldServer, automaton, this, headPos, worldServer.getBlockState(headPos));
            }
        }
        if (action == PathNodeAction.BREAK_THROUGH) {
            if (!AutomataConfig.allowTerrainModificationInPathing || !automaton.getTaskConfig().isAllowBreakThrough()) {
                clearPath();
                return false;
            }
            return TerrainBreaker.clearBreakThroughStep(worldServer, automaton, this, stepX, stepY, stepZ);
        }
        if (action == PathNodeAction.BRIDGE) {
            if (!AutomataConfig.allowTerrainModificationInPathing || !automaton.getTaskConfig().isAllowScaffolding()) {
                clearPath();
                return false;
            }
            boolean nextAlsoBridge = stepIdx + 1 < path.length() && path.getAction(stepIdx + 1) == PathNodeAction.BRIDGE;
            BlockPos support = new BlockPos(stepX, stepY - 1, stepZ);
            if (!CellClassifier.isSolidSupport(worldServer, support)) {
                automaton.setSprinting(false);
                bridgeSneakRequested = true;
                double dx = (stepX + 0.5D) - automaton.posX;
                double dz = (stepZ + 0.5D) - automaton.posZ;
                double horizDistSq = dx * dx + dz * dz;
                if (!shouldAttemptBridgePlace(horizDistSq)) {
                    return true;
                }
                placementService.tryPlaceBlockLegit(worldServer, automaton, this, support, true, false);
                if (CellClassifier.isSolidSupport(worldServer, support)) {
                    bridgeSneakRequested = nextAlsoBridge;
                    return true;
                }
                if (shouldHoldBridgeEdge(horizDistSq)) {
                    automaton.motionX *= 0.25D;
                    automaton.motionZ *= 0.25D;
                    automaton.setMoveForward(0.0F);
                    automaton.setMoveStrafing(0.0F);
                    return false;
                }
                return true;
            }
            bridgeSneakRequested = nextAlsoBridge;
        }
        if (action == PathNodeAction.PILLAR_UP) {
            if (!AutomataConfig.allowTerrainModificationInPathing || !automaton.getTaskConfig().isAllowScaffolding()) {
                clearPath();
                return false;
            }
            BlockPos ceiling = new BlockPos(stepX, stepY + 2, stepZ);
            if (!CellClassifier.isPassable(worldServer, ceiling)) {
                if (!TerrainBreaker.tryClearSingleBlock(worldServer, automaton, this, ceiling)) {
                    return false;
                }
            }
            BlockPos support = new BlockPos(stepX, stepY - 1, stepZ);
            if (!CellClassifier.isSolidSupport(worldServer, support)) {
                automaton.getBrain().getContext().getBlackboard().claimBuildSpace(
                        automaton.getEntityId(), support, worldServer.getTotalWorldTime(), 20L);
                double centerX = stepX + 0.5D;
                double centerZ = stepZ + 0.5D;
                double dx = centerX - automaton.posX;
                double dz = centerZ - automaton.posZ;
                if (automaton.onGround && needsPillarCentering(dx, dz)) {
                    automaton.setSprinting(false);
                    automaton.motionX *= 0.5D;
                    automaton.motionZ *= 0.5D;
                    motorController.applyDirectionalMovement(automaton, centerX, centerZ);
                    return false;
                }
                automaton.motionX = 0.0D;
                automaton.motionZ = 0.0D;
                automaton.setMoveForward(0.0F);
                automaton.setMoveStrafing(0.0F);
                automaton.setSprinting(false);
                setActionLookLock(4);
                CameraController.lookAtBlockPoint(automaton, centerX, stepY - 1.0D, centerZ);
                if (automaton.onGround) {
                    if (automaton.rotationPitch >= 58.0F && !placementService.isOnCooldown()) {
                        automaton.getJumpHelper().setJumping();
                    }
                    return false;
                }
                if (automaton.posY >= stepY - 0.12D) {
                    placementService.tryPlaceBlockLegit(worldServer, automaton, this, support, false, false);
                }
                return CellClassifier.isSolidSupport(worldServer, support);
            }
        }
        return true;
    }

    private boolean isMiningActive() {
        return automaton.getBrain().getContext().getMiningController().isMining();
    }

    public long consumeLastPlacedBlock() {
        return placementService.consumeLastPlacedBlock();
    }

    public boolean sealLiquidAt(WorldServer world, BlockPos liquidPos) {
        return placementService.tryPlaceBlockLegit(world, automaton, this, liquidPos, false, true);
    }

    public boolean placeSupportBlockAt(WorldServer world, BlockPos targetPos, boolean bridgeMode, boolean lavaRescue) {
        return placementService.tryPlaceBlockLegit(world, automaton, this, targetPos, bridgeMode, lavaRescue);
    }

    static boolean isWithinTerrainReach(double botX, double botY, double botZ, int blockX, int blockY, int blockZ) {
        double dx = (blockX + 0.5D) - botX;
        double dy = (blockY + 0.5D) - botY;
        double dz = (blockZ + 0.5D) - botZ;
        return dx * dx + dy * dy + dz * dz <= TERRAIN_MINING_REACH_SQ;
    }

    static boolean shouldSneakForBridge(double horizDistSq) {
        return horizDistSq <= BRIDGE_SNEAK_DIST_SQ;
    }

    static boolean shouldAttemptBridgePlace(double horizDistSq) {
        return horizDistSq <= BRIDGE_PLACE_DIST_SQ;
    }

    static boolean shouldHoldBridgeEdge(double horizDistSq) {
        return horizDistSq <= BRIDGE_HOLD_DIST_SQ;
    }

    static boolean needsPillarCentering(double dx, double dz) {
        return Math.abs(dx) > PILLAR_CENTER_TOLERANCE || Math.abs(dz) > PILLAR_CENTER_TOLERANCE;
    }

    public void setActionLookLock(int ticks) {
        this.actionLookLockTicks = Math.max(this.actionLookLockTicks, ticks);
    }

    public boolean isActionLookLocked() {
        return actionLookLockTicks > 0;
    }

    public boolean isAlignedWithBlock(BlockPos pos, float maxAngleDeg) {
        return CameraController.isAlignedWithPoint(
                automaton,
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                maxAngleDeg,
                maxAngleDeg);
    }

    public void lookAtBlockFace(BlockPos pos) {
        setActionLookLock(2);
        CameraController.lookAtBlockPoint(automaton, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    public void lookAtBlockFace(BlockPos pos, EnumFacing face) {
        setActionLookLock(2);
        CameraController.lookAtBlockPoint(
                automaton,
                pos.getX() + 0.5D + face.getXOffset() * 0.5D,
                pos.getY() + 0.5D + face.getYOffset() * 0.5D,
                pos.getZ() + 0.5D + face.getZOffset() * 0.5D);
    }

    public void snapLook(float yaw, float pitch) {
        CameraController.snapLook(automaton, yaw, pitch);
    }

    public void lookSmoothlyAt(double targetX, double targetY, double targetZ) {
        setActionLookLock(2);
        CameraController.lookSmoothlyAt(automaton, targetX, targetY, targetZ, pace);
    }
}
