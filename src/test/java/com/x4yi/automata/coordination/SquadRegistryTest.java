package com.x4yi.automata.coordination;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class SquadRegistryTest {

    @Test
    void removeDimensionEvictsOnlyUnloadedDimensionBoards() {
        SquadRegistry.clearAll();
        UUID owner = UUID.randomUUID();
        SquadBlackboard overworld = SquadRegistry.getBoard(0, owner, "alpha");
        SquadBlackboard nether = SquadRegistry.getBoard(-1, owner, "alpha");

        assertSame(overworld, SquadRegistry.getBoard(0, owner, "alpha"));
        assertSame(nether, SquadRegistry.getBoard(-1, owner, "alpha"));

        SquadRegistry.removeDimension(-1);

        assertSame(overworld, SquadRegistry.getBoard(0, owner, "alpha"));
        assertNotSame(nether, SquadRegistry.getBoard(-1, owner, "alpha"));
        SquadRegistry.clearAll();
    }
}

