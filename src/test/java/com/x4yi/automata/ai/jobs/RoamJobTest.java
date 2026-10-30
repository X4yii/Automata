package com.x4yi.automata.ai.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import com.x4yi.automata.ai.AutomatonTaskConfig;

class RoamJobTest {

    @Test
    void requiresWorkZoneAndRespectsZoneMargin() {
        AutomatonTaskConfig cfg = new AutomatonTaskConfig();
        assertFalse(RoamJob.canRoam(null));
        assertFalse(RoamJob.canRoam(cfg));

        cfg.setPos1(new BlockPos(0, 64, 0));
        cfg.setPos2(new BlockPos(16, 70, 16));
        assertTrue(RoamJob.canRoam(cfg));
        assertTrue(RoamJob.isWithinZoneMargin(8.0D, 8.0D, 0, 16, 0, 16, 12.0D));
        assertFalse(RoamJob.isWithinZoneMargin(40.0D, 8.0D, 0, 16, 0, 16, 12.0D));
    }
}

