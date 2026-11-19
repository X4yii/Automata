
package com.x4yi.automata.coordination;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.util.math.BlockPos;

import com.x4yi.automata.navigation.PackedPos;

public class SquadBlackboard {

    private static final long DEFAULT_RESERVATION_TTL_TICKS = 100L;

    private final Long2ObjectOpenHashMap<BlockReservation> blockReservations = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<DownedAlert> downedAllies = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<CombatTurnSlot> combatTurns = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<ReviveAlert> activeRevives = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<ItemExchangeOffer> activeExchanges = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<HazardZoneAlert> activeHazardZones = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<BuildSpaceClaim> activeBuildClaims = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<TargetSlot> targetAssignments = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<VengeanceClaim> vengeanceSlamClaims = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<PriorityAlert> priorityTargets = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<CachedThreatEntry> threatProfileCache = new Int2ObjectOpenHashMap<>();

    public boolean tryReserveBlock(int entityId, BlockPos pos, long worldTime) {
        return tryReserveBlock(entityId, pos, worldTime, DEFAULT_RESERVATION_TTL_TICKS);
    }

    public synchronized boolean tryReserveBlock(int entityId, BlockPos pos, long worldTime, long ttlTicks) {
        long key = PackedPos.pack(pos.getX(), pos.getY(), pos.getZ());
        long expiresAt = worldTime + Math.max(1L, ttlTicks);
        BlockReservation existing = blockReservations.get(key);
        if (existing == null || existing.expiresAtTick <= worldTime || existing.ownerEntityId == entityId) {
            blockReservations.put(key, new BlockReservation(entityId, expiresAt));
            return true;
        }
        return false;
    }

    public synchronized void releaseBlock(int entityId, BlockPos pos) {
        long key = PackedPos.pack(pos.getX(), pos.getY(), pos.getZ());
        BlockReservation existing = blockReservations.get(key);
        if (existing != null && existing.ownerEntityId == entityId) {
            blockReservations.remove(key);
        }
    }

    public synchronized boolean isReservedByOther(int entityId, BlockPos pos, long worldTime) {
        long key = PackedPos.pack(pos.getX(), pos.getY(), pos.getZ());
        BlockReservation existing = blockReservations.get(key);
        if (existing == null) {
            return false;
        }
        if (existing.expiresAtTick <= worldTime) {
            blockReservations.remove(key);
            return false;
        }
        return existing.ownerEntityId != entityId;
    }

    public synchronized void reportDowned(int entityId, BlockPos pos, long worldTime) {
        downedAllies.put(entityId, new DownedAlert(pos.toImmutable(), worldTime));
    }

    public synchronized void clearDowned(int entityId) {
        downedAllies.remove(entityId);
    }

    private static final double MAX_DOWNED_SEARCH_DIST_SQ = 4096.0D;

    @Nullable
    public Map.Entry<Integer, BlockPos> findNearestDowned(int selfEntityId, BlockPos selfPos) {
        return findNearestDowned(selfEntityId, selfPos, -1L);
    }

    @Nullable
    public synchronized Map.Entry<Integer, BlockPos> findNearestDowned(int selfEntityId, BlockPos selfPos, long worldTime) {
        int bestId = -1;
        BlockPos bestPos = null;
        double bestDistSq = MAX_DOWNED_SEARCH_DIST_SQ;

        for (Int2ObjectMap.Entry<DownedAlert> entry : downedAllies.int2ObjectEntrySet()) {
            int candidateId = entry.getIntKey();
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

    public synchronized Map<Long, BlockReservation> getActiveReservationsSnapshot() {
        Map<Long, BlockReservation> copy = new HashMap<>(blockReservations.size());
        for (Long2ObjectMap.Entry<BlockReservation> entry : blockReservations.long2ObjectEntrySet()) {
            copy.put(entry.getLongKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    public synchronized void pruneExpired(long worldTime) {
        for (ObjectIterator<Long2ObjectMap.Entry<BlockReservation>> it = blockReservations.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
            if (it.next().getValue().expiresAtTick <= worldTime) {
                it.remove();
            }
        }
        for (ObjectIterator<Int2ObjectMap.Entry<CombatTurnSlot>> it = combatTurns.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            CombatTurnSlot slot = it.next().getValue();
            if (slot.expiresAtTick <= worldTime && slot.activeAttackerId == -1) {
                it.remove();
            }
        }
        for (ObjectIterator<Int2ObjectMap.Entry<ReviveAlert>> it = activeRevives.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            if (it.next().getValue().expiresAtTick <= worldTime) {
                it.remove();
            }
        }
        for (ObjectIterator<Int2ObjectMap.Entry<ItemExchangeOffer>> it = activeExchanges.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            if (it.next().getValue().expiresAtTick <= worldTime) {
                it.remove();
            }
        }
        for (ObjectIterator<Int2ObjectMap.Entry<HazardZoneAlert>> it = activeHazardZones.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            if (it.next().getValue().expiresAtTick <= worldTime) {
                it.remove();
            }
        }
        for (ObjectIterator<Int2ObjectMap.Entry<BuildSpaceClaim>> it = activeBuildClaims.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            if (it.next().getValue().expiresAtTick <= worldTime) {
                it.remove();
            }
        }
        for (ObjectIterator<Int2ObjectMap.Entry<TargetSlot>> it = targetAssignments.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            if (it.next().getValue().pruneAndIsEmpty(worldTime)) {
                it.remove();
            }
        }
        for (ObjectIterator<Int2ObjectMap.Entry<VengeanceClaim>> it = vengeanceSlamClaims.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            if (it.next().getValue().expiresAtTick <= worldTime) {
                it.remove();
            }
        }
        for (ObjectIterator<Int2ObjectMap.Entry<PriorityAlert>> it = priorityTargets.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            if (it.next().getValue().expiresAtTick <= worldTime) {
                it.remove();
            }
        }
        for (ObjectIterator<Int2ObjectMap.Entry<CachedThreatEntry>> it = threatProfileCache.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            if (it.next().getValue().expiresAtTick <= worldTime) {
                it.remove();
            }
        }
    }

    public void reportReviveInProgress(int reviverEntityId, int downedEntityId, BlockPos pos, long worldTime) {
        reportReviveInProgress(reviverEntityId, downedEntityId, pos, 0.0D, worldTime);
    }

    public synchronized void reportReviveInProgress(
            int reviverEntityId,
            int downedEntityId,
            BlockPos pos,
            double distSq,
            long worldTime) {
        activeRevives.put(reviverEntityId, new ReviveAlert(reviverEntityId, downedEntityId, pos.toImmutable(), distSq, worldTime + 20L));
    }

    public synchronized boolean tryClaimReviveDuty(
            int reviverEntityId,
            int downedEntityId,
            BlockPos pos,
            double distSq,
            long worldTime) {
        for (ObjectIterator<Int2ObjectMap.Entry<ReviveAlert>> it = activeRevives.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            ReviveAlert alert = it.next().getValue();
            if (alert.expiresAtTick <= worldTime) {
                it.remove();
                continue;
            }
            if (alert.downedEntityId == downedEntityId && alert.reviverEntityId != reviverEntityId) {
                double existingDist = Math.sqrt(Math.max(0.0D, alert.distSq));
                double candidateDist = Math.sqrt(Math.max(0.0D, distSq));
                if (candidateDist + 4.0D < existingDist) {
                    it.remove();
                    break;
                }
                return false;
            }
        }
        reportReviveInProgress(reviverEntityId, downedEntityId, pos, distSq, worldTime);
        return true;
    }

    public synchronized void clearReviveInProgress(int reviverEntityId) {
        activeRevives.remove(reviverEntityId);
    }

    @Nullable
    public synchronized ReviveAlert findActiveReviverForSquad(int selfEntityId, long worldTime) {
        ReviveAlert nearest = null;
        for (ObjectIterator<Int2ObjectMap.Entry<ReviveAlert>> it = activeRevives.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            ReviveAlert alert = it.next().getValue();
            if (alert.expiresAtTick <= worldTime) {
                it.remove();
                continue;
            }
            if (alert.reviverEntityId != selfEntityId) {
                nearest = alert;
                break;
            }
        }
        return nearest;
    }

    public synchronized boolean isDownedBeingRevivedByOther(int selfEntityId, int downedEntityId, long worldTime) {
        for (ObjectIterator<Int2ObjectMap.Entry<ReviveAlert>> it = activeRevives.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            ReviveAlert alert = it.next().getValue();
            if (alert.expiresAtTick <= worldTime) {
                it.remove();
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

    public synchronized boolean tryClaimAttackTurn(
            int attackerEntityId,
            int targetEntityId,
            long worldTime,
            long ttlTicks,
            boolean allowFocusFire) {
        if (allowFocusFire) {
            return true;
        }
        long expiresAt = worldTime + Math.max(1L, ttlTicks);
        CombatTurnSlot existing = combatTurns.get(targetEntityId);
        if (existing == null || existing.expiresAtTick <= worldTime) {
            combatTurns.put(targetEntityId, new CombatTurnSlot(attackerEntityId, attackerEntityId, expiresAt));
            return true;
        }
        if (existing.activeAttackerId == attackerEntityId) {
            combatTurns.put(targetEntityId, new CombatTurnSlot(attackerEntityId, existing.lastAttackerId, expiresAt));
            return true;
        }
        if (existing.activeAttackerId == -1 && existing.lastAttackerId != attackerEntityId) {
            combatTurns.put(targetEntityId, new CombatTurnSlot(attackerEntityId, attackerEntityId, expiresAt));
            return true;
        }
        return false;
    }

    public synchronized void releaseAttackTurn(int attackerEntityId, int targetEntityId, long worldTime) {
        CombatTurnSlot existing = combatTurns.get(targetEntityId);
        if (existing != null && existing.activeAttackerId == attackerEntityId) {
            combatTurns.put(targetEntityId, new CombatTurnSlot(-1, attackerEntityId, worldTime + 3L));
        }
    }

    public synchronized boolean isTurnHeldByOther(int attackerEntityId, int targetEntityId, long worldTime) {
        CombatTurnSlot existing = combatTurns.get(targetEntityId);
        if (existing == null || existing.expiresAtTick <= worldTime) {
            return false;
        }
        return existing.activeAttackerId != -1 && existing.activeAttackerId != attackerEntityId;
    }

    public synchronized boolean tryBeginExchange(int giverId, int receiverId, BlockPos anchorPos, long worldTime, long ttlTicks) {
        if (giverId == receiverId) {
            return false;
        }
        for (ObjectIterator<Int2ObjectMap.Entry<ItemExchangeOffer>> it = activeExchanges.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            ItemExchangeOffer offer = it.next().getValue();
            if (offer.expiresAtTick <= worldTime) {
                it.remove();
                continue;
            }
            if (offer.giverEntityId == giverId
                    || offer.receiverEntityId == giverId
                    || offer.giverEntityId == receiverId
                    || offer.receiverEntityId == receiverId) {
                return offer.giverEntityId == giverId && offer.receiverEntityId == receiverId;
            }
        }
        ItemExchangeOffer created = new ItemExchangeOffer(
                giverId, receiverId, ExchangeStage.APPROACHING, anchorPos.toImmutable(), -1L, worldTime + Math.max(1L, ttlTicks));
        activeExchanges.put(giverId, created);
        return true;
    }

    public synchronized void refreshExchange(int giverId, int receiverId, BlockPos anchorPos, long worldTime, long ttlTicks) {
        ItemExchangeOffer existing = activeExchanges.get(giverId);
        if (existing != null && existing.receiverEntityId == receiverId && existing.stage == ExchangeStage.APPROACHING) {
            activeExchanges.put(
                    giverId,
                    new ItemExchangeOffer(
                            giverId, receiverId, ExchangeStage.APPROACHING, anchorPos.toImmutable(), -1L, worldTime + Math.max(1L, ttlTicks)));
        }
    }

    public synchronized void markExchangeDropped(int giverId, int receiverId, BlockPos dropPos, long worldTime, long ttlTicks) {
        activeExchanges.put(
                giverId,
                new ItemExchangeOffer(
                        giverId,
                        receiverId,
                        ExchangeStage.DROPPED,
                        dropPos.toImmutable(),
                        worldTime,
                        worldTime + Math.max(1L, ttlTicks)));
    }

    public synchronized void completeExchange(int giverId, int receiverId) {
        ItemExchangeOffer existing = activeExchanges.get(giverId);
        if (existing != null && existing.receiverEntityId == receiverId) {
            activeExchanges.remove(giverId);
        }
    }

    @Nullable
    public synchronized ItemExchangeOffer findExchangeForBot(int entityId, long worldTime) {
        for (ObjectIterator<Int2ObjectMap.Entry<ItemExchangeOffer>> it = activeExchanges.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            ItemExchangeOffer offer = it.next().getValue();
            if (offer.expiresAtTick <= worldTime) {
                it.remove();
                continue;
            }
            if (offer.giverEntityId == entityId || offer.receiverEntityId == entityId) {
                return offer;
            }
        }
        return null;
    }

    public synchronized void reportHazardZone(
            int hazardEntityId,
            double posX,
            double posZ,
            double dirX,
            double dirZ,
            double radius,
            long worldTime,
            long ttlTicks) {
        activeHazardZones.put(
                hazardEntityId,
                new HazardZoneAlert(hazardEntityId, posX, posZ, dirX, dirZ, radius, worldTime + Math.max(1L, ttlTicks)));
    }

    public synchronized List<HazardZoneAlert> getActiveHazardZones(long worldTime) {
        List<HazardZoneAlert> out = new ArrayList<>();
        for (ObjectIterator<Int2ObjectMap.Entry<HazardZoneAlert>> it = activeHazardZones.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            HazardZoneAlert alert = it.next().getValue();
            if (alert.expiresAtTick <= worldTime) {
                it.remove();
                continue;
            }
            out.add(alert);
        }
        return out;
    }

    public synchronized boolean tryClaimTarget(
            int botId,
            int targetId,
            boolean isRanged,
            double distSq,
            int maxMeleeSlots,
            long worldTime,
            long ttlTicks) {
        long expiresAt = worldTime + Math.max(1L, ttlTicks);
        TargetSlot slot = targetAssignments.get(targetId);
        if (slot == null) {
            slot = new TargetSlot();
            targetAssignments.put(targetId, slot);
        }
        return slot.tryClaim(botId, isRanged, distSq, maxMeleeSlots, worldTime, expiresAt);
    }

    public synchronized int getAssignedMeleeCount(int targetId, int excludeBotId, double candidateDistSq, long worldTime) {
        TargetSlot slot = targetAssignments.get(targetId);
        if (slot == null) {
            return 0;
        }
        return slot.countActiveMelee(excludeBotId, candidateDistSq, worldTime);
    }

    public synchronized void releaseTarget(int botId, int targetId) {
        TargetSlot slot = targetAssignments.get(targetId);
        if (slot != null && slot.releaseAndIsEmpty(botId)) {
            targetAssignments.remove(targetId);
        }
    }

    public synchronized boolean tryClaimVengeanceSlam(
            int botId,
            int targetId,
            boolean allowMultiSlam,
            long worldTime,
            long ttlTicks) {
        if (allowMultiSlam) {
            return true;
        }
        long expiresAt = worldTime + Math.max(1L, ttlTicks);
        VengeanceClaim existing = vengeanceSlamClaims.get(targetId);
        if (existing == null || existing.expiresAtTick <= worldTime || existing.botEntityId == botId) {
            vengeanceSlamClaims.put(targetId, new VengeanceClaim(botId, expiresAt));
            return true;
        }
        return existing.botEntityId == botId;
    }

    public synchronized boolean isVengeanceSlamClaimedByOther(int botId, int targetId, long worldTime) {
        VengeanceClaim existing = vengeanceSlamClaims.get(targetId);
        if (existing == null) {
            return false;
        }
        if (existing.expiresAtTick <= worldTime) {
            vengeanceSlamClaims.remove(targetId);
            return false;
        }
        return existing.botEntityId != botId;
    }

    public synchronized void releaseVengeanceSlam(int botId, int targetId) {
        VengeanceClaim existing = vengeanceSlamClaims.get(targetId);
        if (existing != null && existing.botEntityId == botId) {
            vengeanceSlamClaims.remove(targetId);
        }
    }

    public synchronized void reportPriorityTarget(
            int reporterBotId,
            int targetId,
            double weightMultiplier,
            long worldTime,
            long ttlTicks) {
        double clampedWeight = Math.max(0.10D, Math.min(1.0D, weightMultiplier));
        long expiresAt = worldTime + Math.max(1L, ttlTicks);
        PriorityAlert existing = priorityTargets.get(targetId);
        if (existing == null || existing.expiresAtTick <= worldTime || clampedWeight <= existing.weightMultiplier) {
            priorityTargets.put(targetId, new PriorityAlert(reporterBotId, clampedWeight, expiresAt));
        }
    }

    public synchronized double getPriorityMultiplier(int targetId, long worldTime) {
        PriorityAlert existing = priorityTargets.get(targetId);
        if (existing == null) {
            return 1.0D;
        }
        if (existing.expiresAtTick <= worldTime) {
            priorityTargets.remove(targetId);
            return 1.0D;
        }
        return existing.weightMultiplier;
    }

    public synchronized double getAssignedFlankAngleRad(int botId, int targetId, long worldTime) {
        TargetSlot slot = targetAssignments.get(targetId);
        if (slot == null) {
            return Double.NaN;
        }
        return slot.getAssignedFlankAngleRad(botId, targetId, worldTime);
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> T getOrComputeThreatProfile(int targetId, long worldTime, long ttlTicks, Supplier<T> computer) {
        CachedThreatEntry existing = threatProfileCache.get(targetId);
        if (existing != null && existing.expiresAtTick > worldTime) {
            return (T) existing.profile;
        }
        T computed = computer.get();
        threatProfileCache.put(targetId, new CachedThreatEntry(computed, worldTime + Math.max(1L, ttlTicks)));
        return computed;
    }

    @Nullable
    public synchronized ReviveAlert findNearestActiveReviveAlert(int selfEntityId, BlockPos pos, double maxDistSq, long worldTime) {
        ReviveAlert nearest = null;
        double bestDistSq = maxDistSq;
        for (ObjectIterator<Int2ObjectMap.Entry<ReviveAlert>> it = activeRevives.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            ReviveAlert alert = it.next().getValue();
            if (alert.expiresAtTick <= worldTime) {
                it.remove();
                continue;
            }
            if (alert.reviverEntityId == selfEntityId) {
                continue;
            }
            double dSq = alert.pos.distanceSq(pos);
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                nearest = alert;
            }
        }
        return nearest;
    }

    public static int[] computeSquadWorkerIndex(int selfEntityId, List<Integer> activePeerIds) {
        if (activePeerIds == null || activePeerIds.isEmpty()) {
            return new int[] {0, 1};
        }
        List<Integer> sorted = new ArrayList<>(activePeerIds.size() + 1);
        sorted.add(selfEntityId);
        for (Integer peerId : activePeerIds) {
            if (peerId != null && peerId != selfEntityId && !sorted.contains(peerId)) {
                sorted.add(peerId);
            }
        }
        Collections.sort(sorted);
        int idx = sorted.indexOf(selfEntityId);
        return new int[] {Math.max(0, idx), sorted.size()};
    }

    public record HazardZoneAlert(
            int hazardEntityId,
            double posX,
            double posZ,
            double dirX,
            double dirZ,
            double radius,
            long expiresAtTick) {}

    public enum ExchangeStage {
        APPROACHING,
        DROPPED
    }

    public record ItemExchangeOffer(
            int giverEntityId,
            int receiverEntityId,
            ExchangeStage stage,
            BlockPos dropPos,
            long droppedAtTick,
            long expiresAtTick) {}

    public synchronized void claimBuildSpace(int builderId, BlockPos pos, long worldTime, long ttlTicks) {
        if (pos == null) {
            return;
        }
        activeBuildClaims.put(builderId, new BuildSpaceClaim(builderId, pos.toImmutable(), worldTime + Math.max(1L, ttlTicks)));
    }

    public synchronized boolean isBuilderClaiming(int builderId, long worldTime) {
        BuildSpaceClaim claim = activeBuildClaims.get(builderId);
        return claim != null && claim.expiresAtTick > worldTime;
    }

    public synchronized boolean computeYieldVector(
            int botId,
            double botX,
            double botY,
            double botZ,
            double radiusSq,
            long worldTime,
            double[] outVec) {
        outVec[0] = 0.0D;
        outVec[1] = 0.0D;
        boolean yielded = false;
        for (ObjectIterator<Int2ObjectMap.Entry<BuildSpaceClaim>> it = activeBuildClaims.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
            BuildSpaceClaim claim = it.next().getValue();
            if (claim.expiresAtTick <= worldTime) {
                it.remove();
                continue;
            }
            if (claim.builderEntityId == botId) {
                continue;
            }
            if (Math.abs(botY - claim.pos.getY()) > 2.5D) {
                continue;
            }
            double dx = botX - (claim.pos.getX() + 0.5D);
            double dz = botZ - (claim.pos.getZ() + 0.5D);
            double distSq = dx * dx + dz * dz;
            if (distSq <= radiusSq) {
                if (distSq < 1e-4D) {
                    outVec[0] += ((botId & 1) == 0 ? 1.0D : -1.0D);
                } else {
                    double dist = Math.sqrt(distSq);
                    outVec[0] += dx / dist;
                    outVec[1] += dz / dist;
                }
                yielded = true;
            }
        }
        return yielded;
    }

    public record BlockReservation(int ownerEntityId, long expiresAtTick) {}

    public record BuildSpaceClaim(int builderEntityId, BlockPos pos, long expiresAtTick) {}

    private record DownedAlert(BlockPos pos, long reportedTick) {}

    private record CombatTurnSlot(int activeAttackerId, int lastAttackerId, long expiresAtTick) {}

    public record ReviveAlert(int reviverEntityId, int downedEntityId, BlockPos pos, double distSq, long expiresAtTick) {
        public ReviveAlert(int reviverEntityId, int downedEntityId, BlockPos pos, long expiresAtTick) {
            this(reviverEntityId, downedEntityId, pos, 0.0D, expiresAtTick);
        }
    }

    private record VengeanceClaim(int botEntityId, long expiresAtTick) {}

    private record PriorityAlert(int reporterEntityId, double weightMultiplier, long expiresAtTick) {}

    private record CachedThreatEntry(Object profile, long expiresAtTick) {}

    private record TargetClaim(boolean ranged, double distSq, long expiresAtTick) {}

    private static final class TargetSlot {
        private final Int2ObjectOpenHashMap<TargetClaim> claims = new Int2ObjectOpenHashMap<>();

        synchronized boolean tryClaim(
                int botId,
                boolean isRanged,
                double distSq,
                int maxMeleeSlots,
                long now,
                long expiresAt) {
            for (ObjectIterator<Int2ObjectMap.Entry<TargetClaim>> it = claims.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
                if (it.next().getValue().expiresAtTick <= now) {
                    it.remove();
                }
            }
            if (isRanged || claims.containsKey(botId)) {
                claims.put(botId, new TargetClaim(isRanged, distSq, expiresAt));
                return true;
            }
            int activeMelee = 0;
            int farthestBotId = -1;
            double farthestDistSq = -1.0D;
            for (Int2ObjectMap.Entry<TargetClaim> entry : claims.int2ObjectEntrySet()) {
                TargetClaim claim = entry.getValue();
                if (!claim.ranged) {
                    activeMelee++;
                    if (claim.distSq > farthestDistSq) {
                        farthestDistSq = claim.distSq;
                        farthestBotId = entry.getIntKey();
                    }
                }
            }
            if (activeMelee < maxMeleeSlots) {
                claims.put(botId, new TargetClaim(false, distSq, expiresAt));
                return true;
            }
            if (farthestBotId >= 0 && Math.sqrt(farthestDistSq) > Math.sqrt(distSq) + 6.0D) {
                claims.remove(farthestBotId);
                claims.put(botId, new TargetClaim(false, distSq, expiresAt));
                return true;
            }
            return false;
        }

        synchronized int countActiveMelee(int excludeBotId, double candidateDistSq, long now) {
            int count = 0;
            double candidateDist = Math.sqrt(Math.max(0.0D, candidateDistSq));
            for (Int2ObjectMap.Entry<TargetClaim> entry : claims.int2ObjectEntrySet()) {
                TargetClaim c = entry.getValue();
                if (c.expiresAtTick <= now || entry.getIntKey() == excludeBotId || c.ranged) {
                    continue;
                }
                if (Math.sqrt(Math.max(0.0D, c.distSq)) > candidateDist + 6.0D) {
                    continue;
                }
                count++;
            }
            return count;
        }

        synchronized double getAssignedFlankAngleRad(int botId, int targetId, long now) {
            TargetClaim self = claims.get(botId);
            if (self == null || self.ranged || self.expiresAtTick <= now) {
                return Double.NaN;
            }
            int rank = 0;
            int totalMelee = 0;
            for (Int2ObjectMap.Entry<TargetClaim> entry : claims.int2ObjectEntrySet()) {
                TargetClaim c = entry.getValue();
                if (!c.ranged && c.expiresAtTick > now) {
                    totalMelee++;
                    if (entry.getIntKey() < botId) {
                        rank++;
                    }
                }
            }
            if (totalMelee <= 0) {
                return Double.NaN;
            }
            double baseAngle = (((targetId * 2654435761L) & 0xFFFFL) / 65536.0D) * (Math.PI * 2.0D);
            return baseAngle + (rank * (Math.PI * 2.0D) / totalMelee);
        }

        synchronized boolean releaseAndIsEmpty(int botId) {
            claims.remove(botId);
            return claims.isEmpty();
        }

        synchronized boolean pruneAndIsEmpty(long now) {
            for (ObjectIterator<Int2ObjectMap.Entry<TargetClaim>> it = claims.int2ObjectEntrySet().fastIterator(); it.hasNext();) {
                if (it.next().getValue().expiresAtTick <= now) {
                    it.remove();
                }
            }
            return claims.isEmpty();
        }
    }
}

