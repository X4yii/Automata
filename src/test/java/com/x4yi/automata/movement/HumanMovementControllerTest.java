package com.x4yi.automata.movement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HumanMovementControllerTest {

    @Test
    void triggersStepJumpOnlyCloseToBlockEdgeOrOnCollision() {
        assertFalse(HumanMovementController.shouldTriggerStepJump(1.25D, 0.0D, 5.0F, false));
        assertTrue(HumanMovementController.shouldTriggerStepJump(1.0D, 0.0D, 5.0F, false));
        assertTrue(HumanMovementController.shouldTriggerStepJump(1.0D, 1.0D, 10.0F, false));
        assertFalse(HumanMovementController.shouldTriggerStepJump(0.9D, 0.0D, 60.0F, false));
        assertTrue(HumanMovementController.shouldTriggerStepJump(1.3D, 0.0D, 60.0F, true));
    }

    @Test
    void acceptsAscendWaypointWhenStandingOnLayeredBlockTop() {
        assertTrue(HumanMovementController.hasReachedAscendWaypoint(0.25D, 0.0D));
        assertTrue(HumanMovementController.hasReachedAscendWaypoint(0.25D, -0.25D));
        assertTrue(HumanMovementController.hasReachedAscendWaypoint(0.25D, -0.5D));
        assertFalse(HumanMovementController.hasReachedAscendWaypoint(0.25D, 0.4D));
        assertFalse(HumanMovementController.hasReachedAscendWaypoint(0.5D, -0.25D));
    }

    @Test
    void checksMiningReachBeforeHaltingForBreakThrough() {
        assertTrue(HumanMovementController.isWithinTerrainReach(0.5D, 64.0D, 0.5D, 4, 64, 0));
        assertFalse(HumanMovementController.isWithinTerrainReach(0.5D, 64.0D, 0.5D, 6, 64, 0));
    }

    @Test
    void coordinatesBridgeSneakPlacementAndEdgeHold() {
        assertFalse(HumanMovementController.shouldSneakForBridge(3.0D));
        assertTrue(HumanMovementController.shouldSneakForBridge(2.5D));
        assertFalse(HumanMovementController.shouldAttemptBridgePlace(2.5D));
        assertTrue(HumanMovementController.shouldAttemptBridgePlace(2.2D));
        assertFalse(HumanMovementController.shouldHoldBridgeEdge(1.8D));
        assertTrue(HumanMovementController.shouldHoldBridgeEdge(1.1D));
    }

    @Test
    void requiresHorizontalCenteringBeforePillarJump() {
        assertFalse(HumanMovementController.needsPillarCentering(0.15D, -0.15D));
        assertTrue(HumanMovementController.needsPillarCentering(0.30D, 0.0D));
        assertTrue(HumanMovementController.needsPillarCentering(0.0D, -0.30D));
    }
}

