package com.x4yi.automata.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WingFlightControllerTest {

    @Test
    void matchesWingsFlightDefaultConstantsAndVerticalLift() {
        assertEquals(0.03F, WingFlightController.MIN_SPEED);
        assertEquals(0.0715F, WingFlightController.MAX_SPEED);
        assertEquals(0.05F, WingFlightController.Y_BOOST);
        assertEquals(0.9F, WingFlightController.FALL_REDUCTION);
        assertEquals(30.0F, WingFlightController.PITCH_OFFSET);

        double[] levelFlight = WingFlightController.computeFlightDelta(0.0F, 0.0F, 1.0F);
        assertTrue(levelFlight[1] > 0.05D, "Level flight should generate upward lift");
        assertTrue(levelFlight[2] > 0.0D, "Yaw 0 should propel along +Z");

        double[] steepClimb = WingFlightController.computeFlightDelta(0.0F, -60.0F, 1.0F);
        assertTrue(steepClimb[1] > levelFlight[1], "Looking up should increase vertical climb");
    }
}
