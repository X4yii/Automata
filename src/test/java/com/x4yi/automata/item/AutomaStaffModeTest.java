package com.x4yi.automata.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

class AutomaStaffModeTest {

    @Test
    void cyclesModesForwardAndBackward() {
        AutomaStaffMode start = AutomaStaffMode.SELECT_ZONE;
        assertEquals(AutomaStaffMode.ASSIGN_SAVING_CHEST, start.cycle(1));
        assertEquals(AutomaStaffMode.ASSIGN_SOURCE_CHEST, start.cycle(1).cycle(1));
        assertEquals(AutomaStaffMode.WHIP, start.cycle(3));
        assertEquals(AutomaStaffMode.TOGGLE_FOLLOW, start.cycle(-1));
        assertEquals(AutomaStaffMode.SELECT_ZONE, AutomaStaffMode.TOGGLE_FOLLOW.cycle(1));
        assertEquals(AutomaStaffMode.ASSIGN_SAVING_CHEST, AutomaStaffMode.fromName("ASSIGN_CHEST"));
        assertEquals(AutomaStaffMode.ASSIGN_SOURCE_CHEST, AutomaStaffMode.fromName("ASSIGN_SOURCE_CHEST"));
        for (AutomaStaffMode mode : AutomaStaffMode.values()) {
            assertEquals(mode, mode.cycle(0));
            assertNotEquals(mode.cycle(-1), mode.cycle(0));
            assertNotEquals(mode.cycle(0), mode.cycle(1));
            assertNotEquals(mode.cycle(-1), mode.cycle(1));
        }
    }

    @Test
    void cyclesDevStaffModesAndFormatsDuplicateNamesAndFiveByFiveAoe() {
        DevStaffMode start = DevStaffMode.DUPLICATE_BOT;
        assertEquals(DevStaffMode.REVIVE_BOTS, start.cycle(1));
        assertEquals(DevStaffMode.RESET_AI, start.cycle(-1));

        assertEquals("Alpha (1)", ItemAutomaDevStaff.computeIncrementedBotName("Alpha", 1));
        assertEquals("Alpha (2)", ItemAutomaDevStaff.computeIncrementedBotName("Alpha (1)", 2));
        assertEquals("Automaton (3)", ItemAutomaDevStaff.computeIncrementedBotName("", 3));

        AxisAlignedBB box = ItemAutomaDevStaff.computeFiveByFiveAoe(new BlockPos(10, 64, -5));
        assertEquals(5.0D, box.maxX - box.minX, 1e-6);
        assertEquals(5.0D, box.maxZ - box.minZ, 1e-6);
    }
}

