package com.x4yi.automata.ai.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class ThreatFieldEvaluatorTest {

    @Test
    void computesEscapeVectorAwayFromHordeAndDetectsCorneredState() {
        ThreatFieldEvaluator.ThreatAssessment assessment = ThreatFieldEvaluator.computeThreatAssessment(
                0.0D,
                0.0D,
                Arrays.asList(
                        new double[] {2.0D, 0.0D, 1.0D},
                        new double[] {0.0D, 2.0D, 1.0D},
                        new double[] {2.0D, 2.0D, 1.0D},
                        new double[] {-2.0D, 1.5D, 1.0D}),
                Collections.emptyList());

        assertTrue(assessment.hasEscapeVector());
        assertTrue(assessment.cornered());
        assertTrue(assessment.escapeZ() < -0.5D);
    }

    @Test
    void doesNotFlagCorneredForSingleThreatAndScoresTacticalTargets() {
        ThreatFieldEvaluator.ThreatAssessment assessment = ThreatFieldEvaluator.computeThreatAssessment(
                0.0D,
                0.0D,
                Collections.singletonList(new double[] {4.0D, 0.0D, 1.0D}),
                Collections.singletonList(new double[] {-8.0D, 0.0D}));

        assertFalse(assessment.cornered());
        assertTrue(assessment.escapeX() < -0.9D);

        double plain = CombatTargetingService.computeTacticalTargetScore(25.0D, false, false, false, false);
        double ranged = CombatTargetingService.computeTacticalTargetScore(25.0D, false, false, false, true);
        double allyThreat = CombatTargetingService.computeTacticalTargetScore(25.0D, false, true, false, false);
        double creeper = CombatTargetingService.computeTacticalTargetScore(25.0D, true, false, false, false);

        assertTrue(creeper < allyThreat);
        assertTrue(allyThreat < ranged);
        assertTrue(ranged < plain);
    }
}

