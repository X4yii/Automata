package com.x4yi.automata.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AutomatonBrainTest {

    @Test
    void evaluatesNullAndOfflineOwnerWithoutServer() {
        assertEquals(
                AutomatonBrain.OwnerAvailability.OWNER_NULL,
                AutomatonBrain.evaluateOwnerAvailability(null, null));
        assertEquals(
                AutomatonBrain.OwnerAvailability.OWNER_OFFLINE,
                AutomatonBrain.evaluateOwnerAvailability(UUID.randomUUID(), null));
    }
}

