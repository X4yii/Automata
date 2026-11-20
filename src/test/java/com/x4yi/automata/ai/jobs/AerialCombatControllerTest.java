package com.x4yi.automata.ai.jobs;

import org.junit.jupiter.api.Test;

import com.x4yi.automata.ai.jobs.AerialCombatController.AerialTactic;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AerialCombatControllerTest {

    @Test
    void selectsContextualAerialTacticsAcrossAllScenarios() {
        assertEquals(
                AerialTactic.GROUND_ONLY,
                AerialCombatController.selectTactic(false, false, true, true, true, true, false, false, 0.0D));

        assertEquals(
                AerialTactic.EMERGENCY_LAND_LOW_SATIATION,
                AerialCombatController.selectTactic(true, false, true, false, true, true, false, true, 4.0D));

        assertEquals(
                AerialTactic.GROUND_ONLY,
                AerialCombatController.selectTactic(true, true, false, false, true, false, false, false, 0.0D));

        assertEquals(
                AerialTactic.AIR_VS_AIR_DOGFIGHT,
                AerialCombatController.selectTactic(true, true, true, true, true, false, false, true, 1.0D));

        assertEquals(
                AerialTactic.AIR_VS_GROUND_RANGED_HARASS,
                AerialCombatController.selectTactic(true, true, true, false, true, true, true, true, 5.0D));

        assertEquals(
                AerialTactic.AIR_VS_GROUND_DIVE_STRIKE,
                AerialCombatController.selectTactic(true, true, true, false, true, false, false, true, 5.0D));

        assertEquals(
                AerialTactic.AIR_CLIMB_RECOVER,
                AerialCombatController.selectTactic(true, true, true, false, false, false, false, true, 2.0D));
    }
}
