
package com.x4yi.automata.coordination;

import java.util.Map;

import net.minecraft.util.math.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SquadBlackboardTest {

    @Test
    void blockReservationRespectsOwnershipAndTtl() {
        SquadBlackboard board = new SquadBlackboard();
        BlockPos pos = new BlockPos(10, 65, -4);

        assertTrue(board.tryReserveBlock(1, pos, 100L, 20L));
        assertFalse(board.tryReserveBlock(2, pos, 110L, 20L));
        assertTrue(board.isReservedByOther(2, pos, 110L));
        assertFalse(board.isReservedByOther(1, pos, 110L));

        assertTrue(board.tryReserveBlock(2, pos, 125L, 20L));
        assertFalse(board.isReservedByOther(2, pos, 125L));

        board.releaseBlock(2, pos);
        assertFalse(board.isReservedByOther(1, pos, 126L));
    }

    @Test
    void downedAllyAlertsFindNearestExcludingSelf() {
        SquadBlackboard board = new SquadBlackboard();
        board.reportDowned(10, new BlockPos(50, 64, 50), 100L);
        board.reportDowned(11, new BlockPos(5, 64, 5), 100L);

        Map.Entry<Integer, BlockPos> nearestFor10 = board.findNearestDowned(10, new BlockPos(0, 64, 0));
        assertNotNull(nearestFor10);
        assertEquals(11, nearestFor10.getKey());

        board.clearDowned(11);
        assertNull(board.findNearestDowned(10, new BlockPos(0, 64, 0)));
    }

    @Test
    void resistsNonPositiveTtlAndConcurrentContention() throws InterruptedException {
        SquadBlackboard board = new SquadBlackboard();
        BlockPos contested = new BlockPos(-100, 64, 250);

        assertTrue(board.tryReserveBlock(1, contested, 10L, -50L));
        assertTrue(board.tryReserveBlock(2, contested, 11L, 0L));

        int threadCount = 8;
        Thread[] threads = new Thread[threadCount];
        int[] wins = new int[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int id = i + 1;
            final int idx = i;
            threads[i] = new Thread(() -> {
                for (int iter = 0; iter < 50; iter++) {
                    if (board.tryReserveBlock(id, contested, 100L + iter, 5L)) {
                        wins[idx]++;
                    }
                    board.releaseBlock(id, contested);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        int totalWins = 0;
        for (int w : wins) {
            totalWins += w;
        }
        assertTrue(totalWins > 0);
        board.pruneExpired(1000L);
        assertTrue(board.getActiveReservationsSnapshot().isEmpty());
    }

    @Test
    void rotatesCombatTurnsAndRespectsExclusivity() {
        SquadBlackboard board = new SquadBlackboard();
        int targetId = 99;

        assertTrue(board.tryClaimAttackTurn(1, targetId, 10L, 25L));
        assertFalse(board.tryClaimAttackTurn(2, targetId, 12L, 25L));
        assertTrue(board.isTurnHeldByOther(2, targetId, 12L));
        assertFalse(board.isTurnHeldByOther(1, targetId, 12L));

        board.releaseAttackTurn(1, targetId, 15L);
        assertTrue(board.tryClaimAttackTurn(2, targetId, 15L, 25L));
        assertFalse(board.tryClaimAttackTurn(1, targetId, 16L, 25L));

        assertTrue(board.tryClaimAttackTurn(1, targetId, 50L, 25L));
    }

    @Test
    void tracksActiveReviverAndPreventsDuplicateRevive() {
        SquadBlackboard board = new SquadBlackboard();
        BlockPos revivePos = new BlockPos(12, 64, -8);

        board.reportReviveInProgress(10, 20, revivePos, 100L);
        assertTrue(board.isDownedBeingRevivedByOther(11, 20, 105L));
        assertFalse(board.isDownedBeingRevivedByOther(10, 20, 105L));
        assertFalse(board.findActiveReviverForSquad(11, 105L) == null);
        assertTrue(board.findActiveReviverForSquad(10, 105L) == null);

        board.pruneExpired(130L);
        assertFalse(board.isDownedBeingRevivedByOther(11, 20, 130L));
    }

    @Test
    void findNearestDownedRespectsMaxDistanceAndActiveReviver() {
        SquadBlackboard board = new SquadBlackboard();
        BlockPos self = new BlockPos(0, 64, 0);
        board.reportDowned(2, new BlockPos(100, 64, 0), 10L);
        assertTrue(board.findNearestDowned(1, self, 10L) == null);

        board.reportDowned(3, new BlockPos(10, 64, 0), 10L);
        assertEquals(3, board.findNearestDowned(1, self, 10L).getKey());

        board.reportReviveInProgress(4, 3, new BlockPos(10, 64, 0), 10L);
        assertTrue(board.findNearestDowned(1, self, 12L) == null);
    }
}

