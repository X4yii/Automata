
package com.x4yi.automata.coordination;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.navigation.PackedPos;

public class SquadBlackboard {

    private static final long DEFAULT_RESERVATION_TTL_TICKS = 100L;

    private final ConcurrentHashMap<Long, BlockReservation> blockReservations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, DownedAlert> downedAllies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CombatTurnSlot> combatTurns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ReviveAlert> activeRevives = new ConcurrentHashMap<>();

    public boolean tryReserveBlock(int entityId, BlockPos pos, long worldTime) {
        return tryReserveBlock(entityId, pos, worldTime, DEFAULT_RESERVATION_TTL_TICKS);
    }

    public boolean tryReserveBlock(int entityId, BlockPos pos, long worldTime, long ttlTicks) {
        long key = PackedPos.pack(pos.getX(), pos.getY(), pos.getZ());
        long expiresAt = worldTime + Math.max(1L, ttlTicks);
        BlockReservation next = new BlockReservation(entityId, expiresAt);

        BlockReservation result = blockReservations.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAtTick <= worldTime || existing.ownerEntityId == entityId) {
                return next;
            }
            return existing;
        });
        return result.ownerEntityId == entityId;
    }

    public void releaseBlock(int entityId, BlockPos pos) {
        long key = PackedPos.pack(pos.getX(), pos.getY(), pos.getZ());
        blockReservations.computeIfPresent(key, (k, existing) ->
                existing.ownerEntityId == entityId ? null : existing);
    }

    public boolean isReservedByOther(int entityId, BlockPos pos, long worldTime) {
        long key = PackedPos.pack(pos.getX(), pos.getY(), pos.getZ());
        BlockReservation existing = blockReservations.get(key);
        if (existing == null) {
            return false;
        }
        if (existing.expiresAtTick <= worldTime) {
            blockReservations.remove(key, existing);
            return false;
        }
        return existing.ownerEntityId != entityId;
    }

    public void reportDowned(int entityId, BlockPos pos, long worldTime) {
        downedAllies.put(entityId, new DownedAlert(pos.toImmutable(), worldTime));
    }

    public void clearDowned(int entityId) {
        downedAllies.remove(entityId);
    }

    private static final double MAX_DOWNED_SEARCH_DIST_SQ = 4096.0D;

    @Nullable
    public Map.Entry<Integer, BlockPos> findNearestDowned(int selfEntityId, BlockPos selfPos) {
        return findNearestDowned(selfEntityId, selfPos, -1L);
    }

    @Nullable
    public Map.Entry<Integer, BlockPos> findNearestDowned(int selfEntityId, BlockPos selfPos, long worldTime) {
        int bestId = -1;
        BlockPos bestPos = null;
        double bestDistSq = MAX_DOWNED_SEARCH_DIST_SQ;

        for (Map.Entry<Integer, DownedAlert> entry : downedAllies.entrySet()) {
            int candidateId = entry.getKey();
            if (candidateId == selfEntityId) {
                continue;
            }
            if (worldTime >= 0L && isDownedBeingRevivedByOther(selfEntityId, candidateId, worldTime)) {
                continue;
            }
            BlockPos pos = entry.getValue().pos;
            double distSq = selfPos.distanceSq(pos);
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                bestId = candidateId;
                bestPos = pos;
            }
        }
        return bestPos == null ? null : Map.entry(bestId, bestPos);
    }

    public Map<Long, BlockReservation> getActiveReservationsSnapshot() {
        return Map.copyOf(blockReservations);
    }

    public void pruneExpired(long worldTime) {
        blockReservations.entrySet().removeIf(e -> e.getValue().expiresAtTick <= worldTime);
        combatTurns.entrySet().removeIf(e -> e.getValue().expiresAtTick <= worldTime && e.getValue().activeAttackerId == -1);
        activeRevives.entrySet().removeIf(e -> e.getValue().expiresAtTick <= worldTime);
    }

    public void reportReviveInProgress(int reviverEntityId, int downedEntityId, BlockPos pos, long worldTime) {
        activeRevives.put(reviverEntityId, new ReviveAlert(reviverEntityId, downedEntityId, pos.toImmutable(), worldTime + 20L));
    }

    public void clearReviveInProgress(int reviverEntityId) {
        activeRevives.remove(reviverEntityId);
    }

    @Nullable
    public ReviveAlert findActiveReviverForSquad(int selfEntityId, long worldTime) {
        ReviveAlert nearest = null;
        for (ReviveAlert alert : activeRevives.values()) {
            if (alert.expiresAtTick <= worldTime) {
                activeRevives.remove(alert.reviverEntityId, alert);
                continue;
            }
            if (alert.reviverEntityId != selfEntityId) {
                nearest = alert;
                break;
            }
        }
        return nearest;
    }

    public boolean isDownedBeingRevivedByOther(int selfEntityId, int downedEntityId, long worldTime) {
        for (ReviveAlert alert : activeRevives.values()) {
            if (alert.expiresAtTick <= worldTime) {
                activeRevives.remove(alert.reviverEntityId, alert);
                continue;
            }
            if (alert.downedEntityId == downedEntityId && alert.reviverEntityId != selfEntityId) {
                return true;
            }
        }
        return false;
    }

    public boolean tryClaimAttackTurn(int attackerEntityId, int targetEntityId, long worldTime, long ttlTicks) {
        return tryClaimAttackTurn(attackerEntityId, targetEntityId, worldTime, ttlTicks, false);
    }

    public boolean tryClaimAttackTurn(
            int attackerEntityId,
            int targetEntityId,
            long worldTime,
            long ttlTicks,
            boolean allowFocusFire) {
        if (allowFocusFire) {
            return true;
        }
        long expiresAt = worldTime + Math.max(1L, ttlTicks);
        CombatTurnSlot result = combatTurns.compute(targetEntityId, (k, existing) -> {
            if (existing == null || existing.expiresAtTick <= worldTime) {
                return new CombatTurnSlot(attackerEntityId, attackerEntityId, expiresAt);
            }
            if (existing.activeAttackerId == attackerEntityId) {
                return new CombatTurnSlot(attackerEntityId, existing.lastAttackerId, expiresAt);
            }
            if (existing.activeAttackerId == -1 && existing.lastAttackerId != attackerEntityId) {
                return new CombatTurnSlot(attackerEntityId, attackerEntityId, expiresAt);
            }
            return existing;
        });
        return result.activeAttackerId == attackerEntityId;
    }

    public void releaseAttackTurn(int attackerEntityId, int targetEntityId, long worldTime) {
        combatTurns.computeIfPresent(targetEntityId, (k, existing) -> {
            if (existing.activeAttackerId == attackerEntityId) {
                return new CombatTurnSlot(-1, attackerEntityId, worldTime + 3L);
            }
            return existing;
        });
    }

    public boolean isTurnHeldByOther(int attackerEntityId, int targetEntityId, long worldTime) {
        CombatTurnSlot existing = combatTurns.get(targetEntityId);
        if (existing == null || existing.expiresAtTick <= worldTime) {
            return false;
        }
        return existing.activeAttackerId != -1 && existing.activeAttackerId != attackerEntityId;
    }

    public record BlockReservation(int ownerEntityId, long expiresAtTick) {}

    private record DownedAlert(BlockPos pos, long reportedTick) {}

    private record CombatTurnSlot(int activeAttackerId, int lastAttackerId, long expiresAtTick) {}

    public record ReviveAlert(int reviverEntityId, int downedEntityId, BlockPos pos, long expiresAtTick) {}
}

