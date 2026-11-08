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

    @Test
    void handlesTwentyFiveSquadsAndEvictsIdleBoards() {
        SquadRegistry.clearAll();
        UUID owner = UUID.randomUUID();
        SquadBlackboard[] boards = new SquadBlackboard[25];
        for (int i = 0; i < 25; i++) {
            boards[i] = SquadRegistry.getBoard(0, owner, "squad-" + i);
        }
        org.junit.jupiter.api.Assertions.assertEquals(25, SquadRegistry.boardCount());
        for (int i = 0; i < 25; i++) {
            assertSame(boards[i], SquadRegistry.getBoard(0, owner, "squad-" + i));
        }
        SquadRegistry.pruneIdleBoards(System.currentTimeMillis() + 600_000L);
        org.junit.jupiter.api.Assertions.assertEquals(0, SquadRegistry.boardCount());
        SquadRegistry.clearAll();
    }
}

