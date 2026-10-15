package com.x4yi.automata.ai.jobs;

import net.minecraft.util.math.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaBlacklistTrackerTest {

    @Test
    void expiresBlacklistedEntriesAfterDuration() {
        AreaBlacklistTracker tracker = new AreaBlacklistTracker(80L);
        BlockPos pos = new BlockPos(10, 64, -5);

        tracker.blacklist(pos, 100L);
        assertTrue(tracker.isBlacklisted(pos, 100L));
        assertTrue(tracker.isBlacklisted(pos, 179L));
        assertFalse(tracker.isBlacklisted(pos, 180L));
    }
}

