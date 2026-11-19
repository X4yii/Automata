package com.x4yi.automata.ai.jobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatJobSupportTest {

    @Test
    void alternatesFlankSignByEntityIdParity() {
        assertEquals(1, CombatJobSupport.computeInitialFlankSign(2));
        assertEquals(-1, CombatJobSupport.computeInitialFlankSign(3));
    }

    @Test
    void computesExpectedStrafeInputsForEachCombatPhase() {
        float[] sTap = CombatJobSupport.computeCombatStrafeInput(4.0D, true, false, false, 1);
        assertTrue(sTap[0] < 0.0F);
        assertTrue(sTap[1] > 0.0F);

        float[] creeperFlee = CombatJobSupport.computeCombatStrafeInput(4.0D, false, true, false, -1);
        assertEquals(-1.0F, creeperFlee[0], 0.001F);
        assertTrue(creeperFlee[1] < 0.0F);

        float[] orbit = CombatJobSupport.computeCombatStrafeInput(10.0D, false, false, true, 1);
        assertTrue(Math.abs(orbit[1]) > 0.8F);
    }

    @Test
    void detectsFriendlyInLineOfFireSegment() {
        assertTrue(CombatJobSupport.isPointNearSegment2D(5.0D, 0.5D, 0.0D, 0.0D, 10.0D, 0.0D, 1.15D));
        assertTrue(!CombatJobSupport.isPointNearSegment2D(5.0D, 3.0D, 0.0D, 0.0D, 10.0D, 0.0D, 1.15D));
        assertTrue(!CombatJobSupport.isPointNearSegment2D(15.0D, 0.0D, 0.0D, 0.0D, 10.0D, 0.0D, 1.15D));
    }

    @Test
    void computesAccurateParabolicBowAnglesAndLead() {
        float[] flatShort = CombatJobSupport.computeBowAimAngles(
                0.0D, 65.5D, 0.0D,
                10.0D, 65.5D, 0.0D,
                0.0D, 0.0D, 0.0D);
        float[] flatLong = CombatJobSupport.computeBowAimAngles(
                0.0D, 65.5D, 0.0D,
                25.0D, 65.5D, 0.0D,
                0.0D, 0.0D, 0.0D);
        assertTrue(flatLong[1] < flatShort[1]);

        float[] leading = CombatJobSupport.computeBowAimAngles(
                0.0D, 65.5D, 0.0D,
                20.0D, 65.5D, 0.0D,
                0.0D, 0.0D, 0.25D);
        assertTrue(leading[0] > flatShort[0]);
    }

    @Test
    void meleeSpacingRetreatsDuringCooldownAndMaintainsStrikeRangeWhenReady() {
        float[] cooldownClose = CombatJobSupport.computeMeleeSpacingInput(6.25D, false, false, false, false, 1);
        assertTrue(cooldownClose[0] < -0.5F);

        float[] invulnClose = CombatJobSupport.computeMeleeSpacingInput(6.25D, true, true, false, false, 1);
        assertTrue(invulnClose[0] < -0.5F);

        float[] readyTooClose = CombatJobSupport.computeMeleeSpacingInput(4.0D, true, false, false, false, 1);
        assertTrue(readyTooClose[0] < 0.0F);

        float[] readyTooFar = CombatJobSupport.computeMeleeSpacingInput(12.25D, true, false, false, false, 1);
        assertTrue(readyTooFar[0] > 0.5F);
    }

    @Test
    void critStrafeSupportsRetreatLateralAndAdvanceWithoutForcedForwardSprint() {
        float[] tooClose = CombatJobSupport.computeCombatStrafeInput(2.0D, false, false, false, 1);
        assertTrue(tooClose[0] < 0.0F);
        assertTrue(Math.abs(tooClose[1]) > 0.0F);

        float[] idealRange = CombatJobSupport.computeCombatStrafeInput(6.5D, false, false, false, -1);
        assertTrue(idealRange[0] <= 0.35F);
        assertTrue(idealRange[1] < 0.0F);

        float[] far = CombatJobSupport.computeCombatStrafeInput(16.0D, false, false, false, 1);
        assertTrue(far[0] > 0.5F);
    }

    @Test
    void tacticsStateStartsWithClearedBowChargeTimer() {
        CombatJobSupport.CombatTacticsState state = CombatJobSupport.createTacticsState(4);
        assertEquals(-1L, state.bowChargeStartTick);
    }

    @Test
    void skipsChaseEnderPearlAgainstAerialTargets() {
        assertFalse(TacticalUtilityController.shouldThrowChasePearl(400.0D, true, false, 6.0D, true));
        assertFalse(TacticalUtilityController.shouldThrowChasePearl(400.0D, false, false, 4.0D, true));
        assertTrue(TacticalUtilityController.shouldThrowChasePearl(400.0D, false, true, 0.5D, true));
    }

    @Test
    void computesHitboxDistanceAgainstLargeAndElevatedTargets() {
        double distSq = CombatJobSupport.computeDistanceSqToHitbox(
                6.5D, 1.6D, 0.0D,
                8.5D, 0.0D, -1.5D,
                11.5D, 3.0D, 1.5D);
        assertEquals(4.0D, distSq, 1e-6);

        double insideSq = CombatJobSupport.computeDistanceSqToHitbox(
                9.0D, 1.6D, 0.0D,
                8.5D, 0.0D, -1.5D,
                11.5D, 3.0D, 1.5D);
        assertEquals(0.0D, insideSq, 1e-6);
    }

    @Test
    void rejectsUnseenWallAndCaveTargetsUnlessCloseOrActivelyAggro() {
        assertFalse(CombatTargetingService.shouldConsiderVisibilityAndAccess(false, false, false, 144.0D, 1.0D, false));
        assertFalse(CombatTargetingService.shouldConsiderVisibilityAndAccess(true, true, true, 64.0D, 0.0D, true));
        assertTrue(CombatTargetingService.shouldConsiderVisibilityAndAccess(true, false, false, 225.0D, 2.0D, false));
        assertTrue(CombatTargetingService.shouldConsiderVisibilityAndAccess(false, false, false, 16.0D, 1.0D, false));
        assertTrue(CombatTargetingService.shouldConsiderVisibilityAndAccess(false, true, false, 100.0D, 2.0D, false));
    }

    @Test
    void aquaticCombatMaintainsForwardMomentumInAquaAcrobatics() {
        float[] aquaApproach = AquaticCombatController.computeAquaticApproachInputs(true, -0.5D, false, 1);
        assertTrue(aquaApproach[0] > 0.5F);
        assertTrue(Math.abs(aquaApproach[1]) > 0.5F);

        float[] vanillaStrike = AquaticCombatController.computeAquaticApproachInputs(false, 0.2D, true, -1);
        assertTrue(vanillaStrike[0] > 0.8F);
        assertTrue(vanillaStrike[1] < 0.0F);
    }
}

