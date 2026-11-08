package com.x4yi.automata.coordination;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class SquadRegistry {

    private static final int PRUNE_THRESHOLD_BOARDS = 64;
    private static final long IDLE_EVICT_MS = 300_000L;
    private static final ConcurrentHashMap<BoardKey, BoardEntry> BOARDS = new ConcurrentHashMap<>();

    private SquadRegistry() {}

    public static SquadBlackboard getBoard(int dimensionId, @Nullable UUID ownerUuid, @Nullable String channel) {
        String channelPart = channel == null ? "" : channel.trim();
        BoardKey key = new BoardKey(dimensionId, ownerUuid, channelPart);
        long nowMs = System.currentTimeMillis();
        BoardEntry entry = BOARDS.get(key);
        if (entry != null) {
            entry.lastAccessMs = nowMs;
            return entry.board;
        }
        if (BOARDS.size() >= PRUNE_THRESHOLD_BOARDS) {
            pruneIdleBoards(nowMs);
        }
        BoardEntry created = BOARDS.computeIfAbsent(key, k -> new BoardEntry(new SquadBlackboard(), nowMs));
        created.lastAccessMs = nowMs;
        return created.board;
    }

    public static void removeDimension(int dimensionId) {
        BOARDS.keySet().removeIf(key -> key.dimensionId == dimensionId);
    }

    static int boardCount() {
        return BOARDS.size();
    }

    static void pruneIdleBoards(long nowMs) {
        Iterator<Map.Entry<BoardKey, BoardEntry>> it = BOARDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BoardKey, BoardEntry> entry = it.next();
            if (nowMs - entry.getValue().lastAccessMs >= IDLE_EVICT_MS) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote && event.getWorld().provider != null) {
            removeDimension(event.getWorld().provider.getDimension());
        }
    }

    public static void clearAll() {
        BOARDS.clear();
    }

    private static final class BoardEntry {
        final SquadBlackboard board;
        volatile long lastAccessMs;

        BoardEntry(SquadBlackboard board, long lastAccessMs) {
            this.board = board;
            this.lastAccessMs = lastAccessMs;
        }
    }

    private static final class BoardKey {
        final int dimensionId;
        @Nullable
        final UUID ownerUuid;
        final String channel;
        final int hash;

        BoardKey(int dimensionId, @Nullable UUID ownerUuid, String channel) {
            this.dimensionId = dimensionId;
            this.ownerUuid = ownerUuid;
            this.channel = channel;
            this.hash = Objects.hash(dimensionId, ownerUuid, channel);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BoardKey other)) {
                return false;
            }
            return dimensionId == other.dimensionId
                    && Objects.equals(ownerUuid, other.ownerUuid)
                    && channel.equals(other.channel);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
