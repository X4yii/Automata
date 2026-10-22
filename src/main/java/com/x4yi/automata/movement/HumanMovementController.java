
package com.x4yi.automata.movement;

import javax.annotation.Nullable;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Blocks;
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

    public enum MovementMood {
        CHILL,
        TRYHARD
    }

    private static final float YAW_CHILL_FACTOR = 0.14F;
    private static final float PITCH_CHILL_FACTOR = 0.14F;
    private static final float YAW_TRYHARD_FACTOR = 0.55F;
    private static final float PITCH_TRYHARD_FACTOR = 0.50F;
    private static final double WAYPOINT_REACH_SQ = 0.64D;
    private static final double PARKOUR_LANDING_REACH_SQ = 0.35D;
    private static final double ASCEND_WAYPOINT_REACH_SQ = 0.42D;
    private static final double ASCEND_MAX_DY = 0.25D;
    private static final double ASCEND_MAX_OVERSHOOT_DY = 0.65D;
    private static final double STEP_JUMP_AXIS_REACH = 1.05D;
    private static final float MIN_SPRINT_HUNGER = 6.0F;
    private static final int STUCK_CHECK_INTERVAL_TICKS = 30;
    private static final int COLLISION_STUCK_INTERVAL_TICKS = 16;
    private static final double MIN_STUCK_PROGRESS_SQ = 0.09D;
    private static final float MAX_JUMP_YAW_ERROR_DEG = 25.0F;
    private static final float BLOCK_AIM_ACTION_YAW_STEP_DEG = 65.0F;
    private static final float BLOCK_AIM_ACTION_PITCH_STEP_DEG = 55.0F;
    private static final double TERRAIN_MINING_REACH_SQ = 20.25D;
    private static final double BRIDGE_SNEAK_DIST_SQ = 2.56D;
    private static final double BRIDGE_PLACE_DIST_SQ = 2.25D;
    private static final double BRIDGE_HOLD_DIST_SQ = 1.15D;
    private static final double PILLAR_CENTER_TOLERANCE = 0.22D;

    private final EntityAutomaton automaton;
    private final BlockPlacementService placementService = new BlockPlacementService();

    private MovementMood mood = MovementMood.CHILL;

    @Nullable
    private PathResult activePath;
    private int currentStepIndex;
    private double stuckOriginX;
    private double stuckOriginY;
    private double stuckOriginZ;
    private int stuckTicks;
    private int parkourSettleTicks;
    private boolean combatStrafeThisTick;
    private boolean directControlActive;
    private long lastStuckGoal = Long.MIN_VALUE;

    public HumanMovementController(EntityAutomaton automaton) {
        this.automaton = automaton;
    }

    public void setPath(@Nullable PathResult path) {
        this.activePath = path;
        this.currentStepIndex = path != null && path.length() > 1 ? 1 : 0;
        this.parkourSettleTicks = 0;
        resetStuckTracker();
    }

    public void clearPath() {
        this.activePath = null;
        this.currentStepIndex = 0;
        this.stuckTicks = 0;
        this.parkourSettleTicks = 0;
        this.combatStrafeThisTick = false;
        this.directControlActive = false;
        clearDirectInputs();
    }

    private void clearDirectInputs() {
        automaton.setSprinting(false);
        automaton.setSneaking(false);
        automaton.setMoveForward(0.0F);
        automaton.setMoveStrafing(0.0F);
    }

    public boolean isDirectControlActive() {
        return directControlActive;
    }

    public void applyDirectStrafe(float forward, float strafe, float speedMultiplier) {
        double baseSpeed = automaton.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        automaton.setAIMoveSpeed((float) (baseSpeed * speedMultiplier));
        automaton.setMoveForward(forward);
        automaton.setMoveStrafing(strafe);
        this.combatStrafeThisTick = true;
        this.directControlActive = true;
    }

    public long getLastStuckGoal() {
        return lastStuckGoal;
    }

    public void clearLastStuckGoal() {
        this.lastStuckGoal = Long.MIN_VALUE;
    }

    public void nudgeUnstick() {
        if (activePath != null && activePath.length() > 0) {
            this.lastStuckGoal = activePath.getStep(activePath.length() - 1);
        }
        clearPath();
    }

    private void resetStuckTracker() {
        this.stuckOriginX = automaton.posX;
        this.stuckOriginY = automaton.posY;
        this.stuckOriginZ = automaton.posZ;
        this.stuckTicks = 0;
    }

    public void setMood(MovementMood mood) {
        this.mood = mood == null ? MovementMood.CHILL : mood;
    }

    public MovementMood getMood() {
        return mood;
    }

    public boolean hasActivePath() {
        return activePath != null && currentStepIndex < activePath.length();
    }

    @Nullable
    public PathResult getActivePath() {
        return activePath;
    }

    public void tick(boolean urgent) {
        if (automaton.isDowned()) {
            combatStrafeThisTick = false;
            directControlActive = false;
            clearDirectInputs();
            return;
        }
        if (!hasActivePath()) {
            if (combatStrafeThisTick) {
                combatStrafeThisTick = false;
                return;
            }
            directControlActive = false;
            clearDirectInputs();
            return;
        }
        combatStrafeThisTick = false;
        directControlActive = false;

        long targetPacked = activePath.getStep(currentStepIndex);
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

        byte currentAction = activePath.getAction(currentStepIndex);
        boolean ascendingStep = dy > ASCEND_MAX_DY;
        boolean reachedWaypoint;
        if (currentAction == PathNodeAction.SPRINT_JUMP) {
            reachedWaypoint = (automaton.onGround || automaton.isInWater())
                    && horizDistSq <= PARKOUR_LANDING_REACH_SQ
                    && Math.abs(dy) <= 0.45D;
        } else if (ascendingStep) {
            reachedWaypoint = hasReachedAscendWaypoint(horizDistSq, dy);
        } else {
            reachedWaypoint = horizDistSq <= WAYPOINT_REACH_SQ && Math.abs(dy) <= 1.2D;
        }

        if (reachedWaypoint) {
            if (currentAction == PathNodeAction.SPRINT_JUMP) {
                automaton.motionX *= 0.35D;
                automaton.motionZ *= 0.35D;
                automaton.setSprinting(false);
                automaton.setSneaking(true);
                parkourSettleTicks = 6;
            }
            currentStepIndex++;
            resetStuckTracker();
            if (!hasActivePath()) {
                clearPath();
                return;
            }
            targetPacked = activePath.getStep(currentStepIndex);
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
        }

        placementService.tickCooldown();
        boolean verticalProgress = Math.abs(automaton.posY - stuckOriginY) >= 0.45D;
        if (verticalProgress || automaton.getDistanceSq(stuckOriginX, stuckOriginY, stuckOriginZ) >= MIN_STUCK_PROGRESS_SQ) {
            resetStuckTracker();
        } else {
            stuckTicks++;
            int stuckLimit = automaton.collidedHorizontally ? COLLISION_STUCK_INTERVAL_TICKS : STUCK_CHECK_INTERVAL_TICKS;
            if (currentAction == PathNodeAction.PILLAR_UP) {
                stuckLimit = Math.max(stuckLimit, 40);
            }
            if (stuckTicks >= stuckLimit) {
                lastStuckGoal = activePath.getStep(activePath.length() - 1);
                clearPath();
                return;
            }
        }

        byte action = activePath.getAction(currentStepIndex);
        if (!handleTerrainModificationStep(action, stepX, stepY, stepZ)) {
            return;
        }

        double steerX = targetX;
        double steerZ = targetZ;
        if (action == PathNodeAction.WALK && currentStepIndex + 1 < activePath.length()) {
            long nextPacked = activePath.getStep(currentStepIndex + 1);
            int nextY = PackedPos.unpackY(nextPacked);
            if (nextY == stepY && activePath.getAction(currentStepIndex + 1) == PathNodeAction.WALK && horizDistSq < 1.44D) {
                steerX = targetX * 0.65D + (PackedPos.unpackX(nextPacked) + 0.5D) * 0.35D;
                steerZ = targetZ * 0.65D + (PackedPos.unpackZ(nextPacked) + 0.5D) * 0.35D;
            }
        }

        boolean needsStepJump = action == PathNodeAction.JUMP
                || action == PathNodeAction.SPRINT_JUMP
                || dy > 0.1D;
        int lookIdx = needsStepJump ? currentStepIndex : Math.min(currentStepIndex + 2, activePath.length() - 1);
        long lookPacked = activePath.getStep(lookIdx);
        lookSmoothlyAt(
                PackedPos.unpackX(lookPacked) + 0.5D,
                PackedPos.unpackY(lookPacked) + automaton.getEyeHeight(),
                PackedPos.unpackZ(lookPacked) + 0.5D,
                action == PathNodeAction.SPRINT_JUMP || action == PathNodeAction.JUMP);

        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float yawError = Math.abs(MathHelper.wrapDegrees(desiredYaw - automaton.rotationYaw));
        boolean alignedForJump = yawError < MAX_JUMP_YAW_ERROR_DEG;

        if (parkourSettleTicks > 0) {
            parkourSettleTicks--;
            automaton.setSprinting(false);
            automaton.setSneaking(true);
            automaton.setMoveForward(0.0F);
            automaton.setMoveStrafing(0.0F);
            resetStuckTracker();
            return;
        }

        double horizDist = Math.sqrt(horizDistSq);
        boolean atParkourEdge = action == PathNodeAction.SPRINT_JUMP && automaton.onGround && isReadyForParkourTakeoff(dx, dz);
        if (atParkourEdge && yawError >= 15.0F) {
            automaton.setSprinting(false);
            automaton.setSneaking(true);
            automaton.setMoveForward(0.0F);
            automaton.setMoveStrafing(0.0F);
            return;
        }

        boolean miningActive = isMiningActive();
        boolean standingOnFarmland = isStandingOnFarmland();
        boolean canSprint = automaton.getHunger() > MIN_SPRINT_HUNGER;
        boolean tryhard = urgent || mood == MovementMood.TRYHARD;
        boolean parkourNeedsSprint = action == PathNodeAction.SPRINT_JUMP && horizDist >= 2.6D;
        boolean shouldSprint = !miningActive && canSprint && !standingOnFarmland && (tryhard
                || parkourNeedsSprint
                || horizDistSq > 256.0D);
        automaton.setSprinting(shouldSprint);
        applyDirectionalMovement(steerX, steerZ);

        boolean parkourShiftBrake = false;
        if (action == PathNodeAction.SPRINT_JUMP && !automaton.onGround && !automaton.isInWater()) {
            parkourShiftBrake = applyParkourAirControl(dx, dy, dz, horizDist, canSprint);
        }

        if (action != PathNodeAction.BRIDGE && !parkourShiftBrake && automaton.isSneaking()) {
            automaton.setSneaking(false);
        }

        if (automaton.isInWater()) {
            if (!miningActive && canSprint) {
                automaton.setSprinting(true);
            }
            lookSmoothlyAt(stepX + 0.5D, targetY + automaton.getEyeHeight(), stepZ + 0.5D);
            boolean needsAirInWater = automaton.getAir() < (miningActive ? 60 : 120);
            boolean stepIsDry = automaton.world.getBlockState(new BlockPos(stepX, stepY, stepZ)).getMaterial()
                    != net.minecraft.block.material.Material.WATER;
            boolean climbingOut = !miningActive && ((stepIsDry && targetY > automaton.posY + 0.10D)
                    || (automaton.collidedHorizontally && targetY >= automaton.posY - 0.15D)
                    || targetY > automaton.posY + 0.85D);
            if (needsAirInWater || climbingOut) {
                automaton.getJumpHelper().setJumping();
                if (climbingOut && automaton.collidedHorizontally && automaton.motionY < 0.34D) {
                    automaton.motionY = 0.34D;
                } else if (!automaton.isSwimming() && automaton.motionY < 0.08D) {
                    automaton.motionY += 0.04D;
                }
            } else if ((miningActive || targetY < automaton.posY - 0.25D) && automaton.getAir() >= 60) {
                if (automaton.isSwimming() && automaton.motionY > -0.18D) {
                    automaton.motionY -= 0.03D;
                } else if (!automaton.isSwimming() && automaton.motionY > -0.06D) {
                    automaton.motionY -= 0.02D;
                }
            }
            if ((automaton.collidedHorizontally || stuckTicks >= 10)
                    && automaton.world instanceof WorldServer ws
                    && AutomataConfig.allowTerrainModificationInPathing
                    && automaton.getTaskConfig().isAllowScaffolding()) {
                BlockPos waterEscape = new BlockPos(stepX, stepY - 1, stepZ);
                if (!CellClassifier.isSolidSupport(ws, waterEscape)) {
                    placementService.tryPlaceBlockLegit(ws, automaton, this, waterEscape, false, false);
                }
            }
        } else if (automaton.onGround && !miningActive) {
            if (action == PathNodeAction.PILLAR_UP) {
                automaton.getJumpHelper().setJumping();
            } else if (action == PathNodeAction.JUMP
                    && shouldTriggerStepJump(dx, dz, yawError, automaton.collidedHorizontally)) {
                automaton.getJumpHelper().setJumping();
            } else if (!standingOnFarmland && atParkourEdge && yawError < 15.0F) {
                if (!isLandingStepValid(stepX, stepY, stepZ)) {
                    nudgeUnstick();
                    return;
                }
                automaton.setSneaking(false);
                automaton.getJumpHelper().setJumping();
            } else if (!standingOnFarmland && automaton.collidedHorizontally && (dy > 0.05D || stuckTicks >= 10)
                    && canJumpObstacleAhead(dx, dz)) {
                automaton.getJumpHelper().setJumping();
            }
        }
        if (stuckTicks >= 8 && automaton.collidedHorizontally) {
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
        BlockPos aheadUpper = aheadHead.up();
        return isPassableForJump(aheadHead) && isPassableForJump(aheadUpper);
    }

    private boolean isPassableForJump(BlockPos pos) {
        return CellClassifier.isPassable(automaton.world, pos);
    }

    static boolean hasReachedAscendWaypoint(double horizDistSq, double dy) {
        return horizDistSq <= ASCEND_WAYPOINT_REACH_SQ && dy <= ASCEND_MAX_DY && dy >= -ASCEND_MAX_OVERSHOOT_DY;
    }

    static boolean shouldTriggerStepJump(double dx, double dz, float yawError, boolean collidedHorizontally) {
        if (collidedHorizontally) {
            return true;
        }
        return Math.abs(dx) <= STEP_JUMP_AXIS_REACH && Math.abs(dz) <= STEP_JUMP_AXIS_REACH && yawError < 45.0F;
    }

    private boolean isLandingStepValid(int stepX, int stepY, int stepZ) {
        if (stepY <= 0 || stepY >= 255) {
            return false;
        }
        BlockPos landPos = new BlockPos(stepX, stepY, stepZ);
        if (!CellClassifier.isSolidSupport(automaton.world, landPos.down())) {
            return false;
        }
        return CellClassifier.isPassable(automaton.world, landPos)
                && CellClassifier.isPassable(automaton.world, landPos.up());
    }

    private boolean applyParkourAirControl(double dx, double dy, double dz, double horizDist, boolean canSprint) {
        if (horizDist < 0.05D) {
            return false;
        }
        double dirX = dx / horizDist;
        double dirZ = dz / horizDist;
        double forwardVel = automaton.motionX * dirX + automaton.motionZ * dirZ;
        if (automaton.motionY < 0.0D && dy <= 0.15D && horizDist < 0.85D && forwardVel > 0.16D) {
            automaton.motionX *= 0.72D;
            automaton.motionZ *= 0.72D;
            automaton.setSprinting(false);
            automaton.setSneaking(true);
            return true;
        }
        double targetSpeed = horizDist >= 3.6D ? 0.29D : (horizDist >= 2.6D ? 0.23D : 0.16D);
        automaton.setSprinting(canSprint && horizDist >= 2.6D);
        if (forwardVel < targetSpeed && horizDist > 0.75D) {
            double boost = Math.min(0.015D, targetSpeed - forwardVel);
            automaton.motionX += dirX * boost;
            automaton.motionZ += dirZ * boost;
        }
        return false;
    }

    private void applyDirectionalMovement(double steerX, double steerZ) {
        double mdx = steerX - automaton.posX;
        double mdz = steerZ - automaton.posZ;
        if (mdx * mdx + mdz * mdz < 0.0025D) {
            automaton.setMoveForward(0.0F);
            automaton.setMoveStrafing(0.0F);
            return;
        }
        double baseSpeed = automaton.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        if (automaton.isSneaking()) {
            baseSpeed *= 0.35D;
        }
        automaton.setAIMoveSpeed((float) baseSpeed);
        float targetYaw = (float) (MathHelper.atan2(mdz, mdx) * (180.0D / Math.PI)) - 90.0F;
        float relRad = MathHelper.wrapDegrees(targetYaw - automaton.rotationYaw) * (float) (Math.PI / 180.0D);
        automaton.setMoveForward(MathHelper.cos(relRad));
        automaton.setMoveStrafing(-MathHelper.sin(relRad));
    }

    private boolean handleTerrainModificationStep(byte action, int stepX, int stepY, int stepZ) {
        if (!(automaton.world instanceof WorldServer worldServer)) {
            return true;
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
                automaton.setSneaking(false);
                clearPath();
                return false;
            }
            BlockPos support = new BlockPos(stepX, stepY - 1, stepZ);
            if (!CellClassifier.isSolidSupport(worldServer, support)) {
                automaton.setSprinting(false);
                automaton.setSneaking(true);
                double dx = (stepX + 0.5D) - automaton.posX;
                double dz = (stepZ + 0.5D) - automaton.posZ;
                double horizDistSq = dx * dx + dz * dz;
                if (!shouldAttemptBridgePlace(horizDistSq)) {
                    return true;
                }
                placementService.tryPlaceBlockLegit(worldServer, automaton, this, support, true, false);
                if (CellClassifier.isSolidSupport(worldServer, support)) {
                    automaton.setSneaking(false);
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
            automaton.setSneaking(false);
        }
        if (action == PathNodeAction.PILLAR_UP) {
            if (!AutomataConfig.allowTerrainModificationInPathing || !automaton.getTaskConfig().isAllowScaffolding()) {
                clearPath();
                return false;
            }
            // Clear any low ceiling at cy+3 before pillaring upward.
            BlockPos ceiling = new BlockPos(stepX, stepY + 2, stepZ);
            if (!CellClassifier.isPassable(worldServer, ceiling)) {
                if (!TerrainBreaker.tryClearSingleBlock(worldServer, automaton, this, ceiling)) {
                    return false;
                }
            }
            BlockPos support = new BlockPos(stepX, stepY - 1, stepZ);
            if (!CellClassifier.isSolidSupport(worldServer, support)) {
                double centerX = stepX + 0.5D;
                double centerZ = stepZ + 0.5D;
                double dx = centerX - automaton.posX;
                double dz = centerZ - automaton.posZ;
                if (automaton.onGround && needsPillarCentering(dx, dz)) {
                    automaton.setSprinting(false);
                    automaton.motionX *= 0.5D;
                    automaton.motionZ *= 0.5D;
                    applyDirectionalMovement(centerX, centerZ);
                    return false;
                }
                automaton.motionX *= 0.25D;
                automaton.motionZ *= 0.25D;
                automaton.setMoveForward(0.0F);
                automaton.setMoveStrafing(0.0F);
                automaton.setSprinting(false);
                lookAtBlockPoint(centerX, stepY - 1.0D, centerZ);
                if (automaton.onGround) {
                    automaton.getJumpHelper().setJumping();
                    return false;
                }
                if (automaton.posY >= stepY + 0.02D) {
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

    public void lookAtBlockFace(BlockPos pos) {
        lookAtBlockPoint(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    public void lookAtBlockFace(BlockPos pos, EnumFacing face) {
        lookAtBlockPoint(
                pos.getX() + 0.5D + face.getXOffset() * 0.5D,
                pos.getY() + 0.5D + face.getYOffset() * 0.5D,
                pos.getZ() + 0.5D + face.getZOffset() * 0.5D);
    }

    private void lookAtBlockPoint(double targetX, double targetY, double targetZ) {
        double dx = targetX - automaton.posX;
        double dy = targetY - (automaton.posY + automaton.getEyeHeight());
        double dz = targetZ - automaton.posZ;
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float desiredPitch = MathHelper.clamp(
                (float) (-(MathHelper.atan2(dy, horizDist) * (180.0D / Math.PI))),
                -85.0F,
                85.0F);

        automaton.rotationYaw = stepAngle(automaton.rotationYaw, desiredYaw, BLOCK_AIM_ACTION_YAW_STEP_DEG);
        automaton.rotationYawHead = automaton.rotationYaw;
        automaton.renderYawOffset = stepAngle(automaton.renderYawOffset, automaton.rotationYaw, BLOCK_AIM_ACTION_YAW_STEP_DEG);
        automaton.rotationPitch = stepAngle(automaton.rotationPitch, desiredPitch, BLOCK_AIM_ACTION_PITCH_STEP_DEG);
    }

    private static float stepAngle(float current, float target, float maxStep) {
        float delta = MathHelper.clamp(MathHelper.wrapDegrees(target - current), -maxStep, maxStep);
        return current + delta;
    }

    public void snapLook(float yaw, float pitch) {
        automaton.rotationYaw = yaw;
        automaton.rotationYawHead = yaw;
        automaton.renderYawOffset = yaw;
        automaton.rotationPitch = MathHelper.clamp(pitch, -85.0F, 85.0F);
    }

    public void lookSmoothlyAt(double targetX, double targetY, double targetZ) {
        lookSmoothlyAt(targetX, targetY, targetZ, mood == MovementMood.TRYHARD);
    }

    private void lookSmoothlyAt(double targetX, double targetY, double targetZ, boolean fastTurn) {
        double dx = targetX - automaton.posX;
        double dy = targetY - (automaton.posY + automaton.getEyeHeight());
        double dz = targetZ - automaton.posZ;
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float desiredPitch = (float) (-(MathHelper.atan2(dy, horizDist) * (180.0D / Math.PI)));

        float yawAlpha = fastTurn ? YAW_TRYHARD_FACTOR : YAW_CHILL_FACTOR;
        float pitchAlpha = fastTurn ? PITCH_TRYHARD_FACTOR : PITCH_CHILL_FACTOR;
        automaton.rotationYaw = interpolateAngle(automaton.rotationYaw, desiredYaw, yawAlpha);
        automaton.rotationYawHead = automaton.rotationYaw;
        automaton.renderYawOffset = interpolateAngle(automaton.renderYawOffset, automaton.rotationYaw, yawAlpha);
        automaton.rotationPitch = interpolateAngle(automaton.rotationPitch, MathHelper.clamp(desiredPitch, -85.0F, 85.0F), pitchAlpha);
    }

    private boolean isStandingOnFarmland() {
        BlockPos below = new BlockPos(
                MathHelper.floor(automaton.posX),
                MathHelper.floor(automaton.posY - 0.1D),
                MathHelper.floor(automaton.posZ));
        return automaton.world.getBlockState(below).getBlock() == Blocks.FARMLAND;
    }

    private boolean isReadyForParkourTakeoff(double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.001D) {
            return false;
        }
        double dirX = dx / len;
        double dirZ = dz / len;
        int supportY = MathHelper.floor(automaton.posY + 0.1D) - 1;
        BlockPos currentUnder = new BlockPos(
                MathHelper.floor(automaton.posX),
                supportY,
                MathHelper.floor(automaton.posZ));
        if (!automaton.world.getBlockState(currentUnder).getMaterial().isSolid()) {
            return true;
        }
        if (!isNearForwardBlockEdge(automaton.posX, automaton.posZ, dx, dz)) {
            return false;
        }
        BlockPos aheadEdge = new BlockPos(
                MathHelper.floor(automaton.posX + dirX * 0.55D),
                supportY,
                MathHelper.floor(automaton.posZ + dirZ * 0.55D));
        return !automaton.world.getBlockState(aheadEdge).getMaterial().isSolid();
    }

    private static boolean isNearForwardBlockEdge(double posX, double posZ, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.001D) {
            return false;
        }
        double relX = posX - (Math.floor(posX) + 0.5D);
        double relZ = posZ - (Math.floor(posZ) + 0.5D);
        double forwardProgress = relX * (dx / len) + relZ * (dz / len);
        return forwardProgress >= 0.28D;
    }

    private static float interpolateAngle(float current, float target, float alpha) {
        float delta = MathHelper.wrapDegrees(target - current);
        return current + delta * alpha;
    }
}
