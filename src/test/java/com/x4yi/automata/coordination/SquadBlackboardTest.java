
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
    void buildSpaceClaimGuidesNearbyAlliesToYield() {
        SquadBlackboard board = new SquadBlackboard();
        BlockPos pillarPos = new BlockPos(10, 64, 10);
        board.claimBuildSpace(1, pillarPos, 100L, 20L);

        assertTrue(board.isBuilderClaiming(1, 110L));
        assertFalse(board.isBuilderClaiming(2, 110L));

        double[] vec = new double[2];
        assertTrue(board.computeYieldVector(2, 11.2D, 64.0D, 10.5D, 7.84D, 110L, vec));
        assertTrue(vec[0] > 0.0D);
        assertFalse(board.computeYieldVector(1, 10.5D, 64.0D, 10.5D, 7.84D, 110L, vec));
        assertFalse(board.computeYieldVector(2, 11.2D, 64.0D, 10.5D, 7.84D, 125L, vec));
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

    @Test
    void tryClaimReviveDutyPreventsStampedeAndAllowsCloserHandoff() {
        SquadBlackboard board = new SquadBlackboard();
        BlockPos pos = new BlockPos(10, 64, 10);

        assertTrue(board.tryClaimReviveDuty(10, 99, pos, 100.0D, 50L));
        assertFalse(board.tryClaimReviveDuty(11, 99, pos, 81.0D, 51L));
        assertTrue(board.tryClaimReviveDuty(12, 99, pos, 9.0D, 52L));
    }

    @Test
    void targetAssignmentSeparatesMeleeAndRangedAndAllowsProximityHandoff() {
        SquadBlackboard board = new SquadBlackboard();
        int targetId = 500;

        assertTrue(board.tryClaimTarget(1, targetId, false, 400.0D, 1, 10L, 25L));
        assertTrue(board.tryClaimTarget(2, targetId, true, 225.0D, 1, 11L, 25L));
        assertEquals(1, board.getAssignedMeleeCount(targetId, 3, 256.0D, 12L));

        assertFalse(board.tryClaimTarget(3, targetId, false, 256.0D, 1, 12L, 25L));
        assertTrue(board.tryClaimTarget(4, targetId, false, 16.0D, 1, 12L, 25L));
        assertEquals(1, board.getAssignedMeleeCount(targetId, 3, 16.0D, 12L));

        board.releaseTarget(4, targetId);
        assertEquals(0, board.getAssignedMeleeCount(targetId, 3, 16.0D, 12L));
    }

    @Test
    void vengeanceSlamClaimPreventsOverkillOnNonBossAndAllowsMultiSlamOnBoss() {
        SquadBlackboard board = new SquadBlackboard();
        int mobId = 777;

        assertTrue(board.tryClaimVengeanceSlam(1, mobId, false, 100L, 30L));
        assertTrue(board.isVengeanceSlamClaimedByOther(2, mobId, 110L));
        assertFalse(board.tryClaimVengeanceSlam(2, mobId, false, 110L, 30L));

        assertTrue(board.tryClaimVengeanceSlam(2, mobId, true, 110L, 30L));

        board.releaseVengeanceSlam(1, mobId);
        assertFalse(board.isVengeanceSlamClaimedByOther(2, mobId, 111L));
    }

    @Test
    void priorityTargetAlertsPropagateBestWeight() {
        SquadBlackboard board = new SquadBlackboard();
        int targetId = 888;

        assertEquals(1.0D, board.getPriorityMultiplier(targetId, 50L));
        board.reportPriorityTarget(1, targetId, 0.35D, 50L, 30L);
        assertEquals(0.35D, board.getPriorityMultiplier(targetId, 55L));

        board.reportPriorityTarget(2, targetId, 0.25D, 56L, 30L);
        assertEquals(0.25D, board.getPriorityMultiplier(targetId, 60L));
        assertEquals(1.0D, board.getPriorityMultiplier(targetId, 90L));
    }

    @Test
    void assignsDistinctFlankAnglesToMeleeAttackersAroundSameTarget() {
        SquadBlackboard board = new SquadBlackboard();
        int targetId = 999;

        assertTrue(board.tryClaimTarget(10, targetId, false, 9.0D, 3, 100L, 30L));
        assertTrue(board.tryClaimTarget(20, targetId, false, 9.0D, 3, 100L, 30L));
        assertTrue(board.tryClaimTarget(30, targetId, true, 100.0D, 3, 100L, 30L));

        double angle1 = board.getAssignedFlankAngleRad(10, targetId, 105L);
        double angle2 = board.getAssignedFlankAngleRad(20, targetId, 105L);
        double rangedAngle = board.getAssignedFlankAngleRad(30, targetId, 105L);

        assertFalse(Double.isNaN(angle1));
        assertFalse(Double.isNaN(angle2));
        assertTrue(Double.isNaN(rangedAngle));
        assertEquals(Math.PI, Math.abs(angle2 - angle1), 1e-6);
    }

    @Test
    void computesDeterministicSquadWorkerPartitionIndices() {
        int[] p1 = SquadBlackboard.computeSquadWorkerIndex(15, java.util.List.of(30, 5));
        int[] p2 = SquadBlackboard.computeSquadWorkerIndex(5, java.util.List.of(30, 15));
        int[] p3 = SquadBlackboard.computeSquadWorkerIndex(30, java.util.List.of(15, 5));

        assertEquals(3, p1[1]);
        assertEquals(1, p1[0]);
        assertEquals(0, p2[0]);
        assertEquals(2, p3[0]);
    }

    @Test
    void cachesThreatProfileUntilTtlExpires() {
        SquadBlackboard board = new SquadBlackboard();
        int[] calls = {0};

        String first = board.getOrComputeThreatProfile(42, 100L, 10L, () -> "v" + (++calls[0]));
        String second = board.getOrComputeThreatProfile(42, 105L, 10L, () -> "v" + (++calls[0]));
        String third = board.getOrComputeThreatProfile(42, 111L, 10L, () -> "v" + (++calls[0]));

        assertEquals("v1", first);
        assertEquals("v1", second);
        assertEquals("v2", third);
    }
}

