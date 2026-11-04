package com.x4yi.automata.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AutomaStaffModeTest {

    @Test
    void cyclesModesForwardAndBackward() {
        AutomaStaffMode start = AutomaStaffMode.SELECT_ZONE;
        assertEquals(AutomaStaffMode.ASSIGN_CHEST, start.cycle(1));
        assertEquals(AutomaStaffMode.WHIP, start.cycle(1).cycle(1));
        assertEquals(AutomaStaffMode.TOGGLE_FOLLOW, start.cycle(-1));
        assertEquals(AutomaStaffMode.SELECT_ZONE, AutomaStaffMode.TOGGLE_FOLLOW.cycle(1));
    }
}

