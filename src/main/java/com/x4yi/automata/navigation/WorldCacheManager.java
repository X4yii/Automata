
package com.x4yi.automata.navigation;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class WorldCacheManager {

    private static final int SECTION_VOLUME = 4096;
    private static final int SECTIONS_PER_CHUNK = 16;
    private static final int MAX_SECTIONS_PER_DIMENSION = 16384;
    private static final int MAX_SECTIONS_CACHED_PER_CALL = 256;
    static final long SECTION_REFRESH_INTERVAL_TICKS = 30L;
    private static final byte[] EMPTY_SECTION = new byte[SECTION_VOLUME];

    private static final Map<Integer, DimensionSectionCache> DIMENSION_CACHES =
            new ConcurrentHashMap<>();

    private WorldCacheManager() {}

    public static INavigationWorldView createSnapshotView(int dimensionId) {
        return createSnapshotView(dimensionId, 256);
    }

    public static INavigationWorldView createSnapshotView(World world) {
        return createSnapshotView(world.provider.getDimension(), Math.max(256, world.getHeight()));
    }

    static INavigationWorldView createSnapshotView(int dimensionId, int worldHeight) {
        DimensionSectionCache cache = DIMENSION_CACHES.get(dimensionId);
        Long2ObjectOpenHashMap<byte[]> snapshot = cache == null ? null : cache.snapshotCopy();
        return new SnapshotView(snapshot, worldHeight);
    }

    public static INavigationWorldView createCorridorSnapshotView(
            World world,
            BlockPos start,
            BlockPos goal,
            int paddingChunks) {
        int dim = world.provider.getDimension();
        int worldHeight = Math.max(256, world.getHeight());
        DimensionSectionCache cache = DIMENSION_CACHES.get(dim);
        if (cache == null) {
            return new SnapshotView(null, worldHeight);
        }
        int minCx = (Math.min(start.getX(), goal.getX()) >> 4) - paddingChunks;
        int maxCx = (Math.max(start.getX(), goal.getX()) >> 4) + paddingChunks;
        int minCz = (Math.min(start.getZ(), goal.getZ()) >> 4) - paddingChunks;
        int maxCz = (Math.max(start.getZ(), goal.getZ()) >> 4) + paddingChunks;
        int spanX = maxCx - minCx + 1;
        int spanZ = maxCz - minCz + 1;
        if (spanX * spanZ > 121) {
            return new SnapshotView(cache.copyRegion(start, goal, 3, worldHeight), worldHeight);
        }
        return new SnapshotView(cache.copyCorridor(minCx, maxCx, minCz, maxCz, worldHeight), worldHeight);
    }

    public static void ensureRegionCached(World world, BlockPos center, int chunkRadius) {
        if (world.isRemote) {
            return;
        }
        int dim = world.provider.getDimension();
        DimensionSectionCache cache = DIMENSION_CACHES.computeIfAbsent(dim, k -> new DimensionSectionCache(MAX_SECTIONS_PER_DIMENSION));
        long nowTick = world.getTotalWorldTime();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int budget = MAX_SECTIONS_CACHED_PER_CALL;

        for (int dx = -chunkRadius; dx <= chunkRadius && budget > 0; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius && budget > 0; dz++) {
                budget -= cacheChunkIfLoaded(world, cache, centerChunkX + dx, centerChunkZ + dz, nowTick, budget);
            }
        }
    }

    public static boolean ensureCorridorCached(World world, BlockPos start, BlockPos goal, int paddingChunks) {
        if (world.isRemote) {
            return true;
        }
        int dim = world.provider.getDimension();
        DimensionSectionCache cache = DIMENSION_CACHES.computeIfAbsent(dim, k -> new DimensionSectionCache(MAX_SECTIONS_PER_DIMENSION));
        long nowTick = world.getTotalWorldTime();
        int minCx = (Math.min(start.getX(), goal.getX()) >> 4) - paddingChunks;
        int maxCx = (Math.max(start.getX(), goal.getX()) >> 4) + paddingChunks;
        int minCz = (Math.min(start.getZ(), goal.getZ()) >> 4) - paddingChunks;
        int maxCz = (Math.max(start.getZ(), goal.getZ()) >> 4) + paddingChunks;

        int spanX = maxCx - minCx + 1;
        int spanZ = maxCz - minCz + 1;
        if (spanX * spanZ > 121) {
            ensureRegionCached(world, start, 2);
            ensureRegionCached(world, goal, 2);
            return true;
        }
        int budget = MAX_SECTIONS_CACHED_PER_CALL;
        refreshStartChunk(world, cache, start.getX() >> 4, start.getZ() >> 4, nowTick);
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (budget <= 0) {
                    return false;
                }
                budget -= cacheChunkIfLoaded(world, cache, cx, cz, nowTick, budget);
            }
        }
        return true;
    }

    private static void refreshStartChunk(
            World world,
            DimensionSectionCache cache,
            int cx,
            int cz,
            long nowTick) {
        if (!world.isBlockLoaded(new BlockPos((cx << 4) + 8, 64, (cz << 4) + 8))) {
            return;
        }
        Chunk chunk = world.getChunk(cx, cz);
        int sectionCount = Math.min(chunk.getBlockStorageArray().length, Math.max(SECTIONS_PER_CHUNK, (world.getHeight() + 15) >> 4));
        for (int sy = 0; sy < sectionCount; sy++) {
            long key = PackedPos.packSection(cx, sy, cz);
            cache.put(key, snapshotSection(world, chunk, sy), nowTick);
        }
    }

    private static int cacheChunkIfLoaded(
            World world,
            DimensionSectionCache cache,
            int cx,
            int cz,
            long nowTick,
            int budget) {
        if (budget <= 0 || !world.isBlockLoaded(new BlockPos((cx << 4) + 8, 64, (cz << 4) + 8))) {
            return 0;
        }
        Chunk chunk = world.getChunk(cx, cz);
        int sectionCount = Math.min(chunk.getBlockStorageArray().length, Math.max(SECTIONS_PER_CHUNK, (world.getHeight() + 15) >> 4));
        long refreshInterval = com.x4yi.automata.ai.BudgetedTickScheduler.scaleInterval(
                (int) SECTION_REFRESH_INTERVAL_TICKS,
                com.x4yi.automata.ai.BudgetedTickScheduler.getServerMspt(world.getMinecraftServer()));
        int cached = 0;
        for (int sy = 0; sy < sectionCount && cached < budget; sy++) {
            long key = PackedPos.packSection(cx, sy, cz);
            if (cache.needsRefresh(key, nowTick, refreshInterval)) {
                cache.put(key, snapshotSection(world, chunk, sy), nowTick);
                cached++;
            }
        }
        return cached;
    }

    static boolean shouldRefreshSection(boolean hasSection, long lastRefreshTick, long nowTick) {
        return shouldRefreshSection(hasSection, lastRefreshTick, nowTick, SECTION_REFRESH_INTERVAL_TICKS);
    }

    static boolean shouldRefreshSection(boolean hasSection, long lastRefreshTick, long nowTick, long refreshIntervalTicks) {
        return !hasSection
                || lastRefreshTick < 0L
                || nowTick < lastRefreshTick
                || nowTick - lastRefreshTick >= Math.max(1L, refreshIntervalTicks);
    }

    public static void invalidateBlock(World world, BlockPos pos) {
        if (world.isRemote || pos.getY() < 0 || pos.getY() >= world.getHeight()) {
            return;
        }
        int dim = world.provider.getDimension();
        DimensionSectionCache cache = DIMENSION_CACHES.get(dim);
        if (cache == null) {
            return;
        }
        int cx = pos.getX() >> 4;
        int sy = pos.getY() >> 4;
        int cz = pos.getZ() >> 4;
        long key = PackedPos.packSection(cx, sy, cz);
        byte[] existing = cache.getCells(key);
        if (existing != null && world.isBlockLoaded(pos)) {
            byte nextCell = classifyBlock(world, pos, world.getBlockState(pos));
            int cellIdx = index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            if (existing == EMPTY_SECTION) {
                if (nextCell == BlockCellType.AIR) {
                    return;
                }
                byte[] copy = new byte[SECTION_VOLUME];
                copy[cellIdx] = nextCell;
                cache.putCellsOnly(key, copy);
            } else if (existing[cellIdx] != nextCell) {
                byte[] copy = Arrays.copyOf(existing, existing.length);
                copy[cellIdx] = nextCell;
                cache.putCellsOnly(key, copy);
            }
        }
    }

    private static byte[] snapshotSection(World world, Chunk chunk, int sectionY) {
        ExtendedBlockStorage[] storageArray = chunk.getBlockStorageArray();
        ExtendedBlockStorage storage = storageArray[sectionY];
        if (storage == null || storage.isEmpty()) {
            return EMPTY_SECTION;
        }
        byte[] cells = new byte[SECTION_VOLUME];
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        int baseX = chunk.x << 4;
        int baseY = sectionY << 4;
        int baseZ = chunk.z << 4;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    IBlockState state = storage.get(x, y, z);
                    mutablePos.setPos(baseX + x, baseY + y, baseZ + z);
                    cells[index(x, y, z)] = classifyBlock(world, mutablePos, state);
                }
            }
        }
        return cells;
    }

    private static final double PASSABLE_LAYER_MAX_Y = 0.25D;

    private static byte classifyBlock(World world, BlockPos pos, IBlockState state) {
        byte base = com.x4yi.automata.movement.CellClassifier.classify(world, pos, state);
        if (ClassifyListenerCheck.hasListeners()) {
            com.x4yi.automata.api.event.PathCellClassifyEvent event =
                    new com.x4yi.automata.api.event.PathCellClassifyEvent(world, pos, state, base);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
            return event.getCellType();
        }
        return base;
    }

    private static final class ClassifyListenerCheck {
        private static final net.minecraftforge.fml.common.eventhandler.ListenerList LISTENER_LIST =
                new com.x4yi.automata.api.event.PathCellClassifyEvent(null, BlockPos.ORIGIN, null, (byte) 0).getListenerList();

        static boolean hasListeners() {
            return LISTENER_LIST.getListeners(0).length > 0;
        }
    }

    static byte classifyCollisionBox(@Nullable AxisAlignedBB box) {
        return com.x4yi.automata.movement.CellClassifier.classifyCollisionBox(box);
    }

    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        invalidateBlock(event.getWorld(), event.getPos());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        invalidateBlock(event.getWorld(), event.getPos());
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        World world = event.getWorld();
        if (world.isRemote) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            invalidateBlock(world, pos);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        World world = event.getWorld();
        if (world.isRemote) {
            return;
        }
        DimensionSectionCache cache = DIMENSION_CACHES.get(world.provider.getDimension());
        if (cache == null) {
            return;
        }
        Chunk chunk = event.getChunk();
        int sectionCount = Math.max(SECTIONS_PER_CHUNK, chunk.getBlockStorageArray().length);
        for (int sy = 0; sy < sectionCount; sy++) {
            cache.remove(PackedPos.packSection(chunk.x, sy, chunk.z));
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (!world.isRemote) {
            DIMENSION_CACHES.remove(world.provider.getDimension());
        }
    }

    static final class DimensionSectionCache {
        private final int maxEntries;
        private final Long2ObjectLinkedOpenHashMap<CacheEntry> lru;

        DimensionSectionCache(int maxEntries) {
            this.maxEntries = maxEntries;
            this.lru = new Long2ObjectLinkedOpenHashMap<>(256, 0.75F);
        }

        synchronized boolean needsRefresh(long key, long nowTick) {
            return needsRefresh(key, nowTick, SECTION_REFRESH_INTERVAL_TICKS);
        }

        synchronized boolean needsRefresh(long key, long nowTick, long refreshIntervalTicks) {
            CacheEntry entry = lru.getAndMoveToFirst(key);
            return shouldRefreshSection(entry != null, entry == null ? -1L : entry.lastRefreshTick, nowTick, refreshIntervalTicks);
        }

        synchronized void put(long key, byte[] cells, long nowTick) {
            lru.putAndMoveToFirst(key, new CacheEntry(cells, nowTick));
            if (lru.size() > maxEntries) {
                lru.removeLast();
            }
        }

        synchronized void putCellsOnly(long key, byte[] cells) {
            CacheEntry existing = lru.get(key);
            long tick = existing != null ? existing.lastRefreshTick : 0L;
            lru.putAndMoveToFirst(key, new CacheEntry(cells, tick));
            if (lru.size() > maxEntries) {
                lru.removeLast();
            }
        }

        @Nullable
        synchronized byte[] getCells(long key) {
            CacheEntry entry = lru.getAndMoveToFirst(key);
            return entry == null ? null : entry.cells;
        }

        synchronized void remove(long key) {
            lru.remove(key);
        }

        synchronized int size() {
            return lru.size();
        }

        synchronized Long2ObjectOpenHashMap<byte[]> snapshotCopy() {
            Long2ObjectOpenHashMap<byte[]> copy = new Long2ObjectOpenHashMap<>(Math.max(16, lru.size()));
            for (Long2ObjectMap.Entry<CacheEntry> entry : lru.long2ObjectEntrySet()) {
                copy.put(entry.getLongKey(), entry.getValue().cells);
            }
            return copy;
        }

        synchronized Long2ObjectOpenHashMap<byte[]> copyCorridor(
                int minCx,
                int maxCx,
                int minCz,
                int maxCz,
                int worldHeight) {
            int maxSy = Math.max(SECTIONS_PER_CHUNK, (worldHeight + 15) >> 4);
            int expected = Math.max(16, (maxCx - minCx + 1) * (maxCz - minCz + 1) * maxSy);
            Long2ObjectOpenHashMap<byte[]> copy = new Long2ObjectOpenHashMap<>(expected);
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    for (int sy = 0; sy < maxSy; sy++) {
                        long key = PackedPos.packSection(cx, sy, cz);
                        CacheEntry entry = lru.getAndMoveToFirst(key);
                        if (entry != null) {
                            copy.put(key, entry.cells);
                        }
                    }
                }
            }
            return copy;
        }

        synchronized Long2ObjectOpenHashMap<byte[]> copyRegion(
                BlockPos start,
                BlockPos goal,
                int radiusChunks,
                int worldHeight) {
            int maxSy = Math.max(SECTIONS_PER_CHUNK, (worldHeight + 15) >> 4);
            Long2ObjectOpenHashMap<byte[]> copy = new Long2ObjectOpenHashMap<>(256);
            copyAround(copy, start.getX() >> 4, start.getZ() >> 4, radiusChunks, maxSy);
            copyAround(copy, goal.getX() >> 4, goal.getZ() >> 4, radiusChunks, maxSy);
            return copy;
        }

        private void copyAround(
                Long2ObjectOpenHashMap<byte[]> dst,
                int centerCx,
                int centerCz,
                int radius,
                int maxSy) {
            for (int cx = centerCx - radius; cx <= centerCx + radius; cx++) {
                for (int cz = centerCz - radius; cz <= centerCz + radius; cz++) {
                    for (int sy = 0; sy < maxSy; sy++) {
                        long key = PackedPos.packSection(cx, sy, cz);
                        CacheEntry entry = lru.getAndMoveToFirst(key);
                        if (entry != null) {
                            dst.put(key, entry.cells);
                        }
                    }
                }
            }
        }

        private static final class CacheEntry {
            final byte[] cells;
            final long lastRefreshTick;

            CacheEntry(byte[] cells, long lastRefreshTick) {
                this.cells = cells;
                this.lastRefreshTick = lastRefreshTick;
            }
        }
    }

    private static final class SnapshotView implements INavigationWorldView {
        private final Long2ObjectOpenHashMap<byte[]> sections;
        private final int worldHeight;

        SnapshotView(@Nullable Long2ObjectOpenHashMap<byte[]> sections, int worldHeight) {
            this.sections = sections;
            this.worldHeight = worldHeight;
        }

        @Override
        public byte getCellType(int x, int y, int z) {
            if (y < 0 || y >= worldHeight || sections == null) {
                return BlockCellType.UNLOADED;
            }
            long key = PackedPos.packSection(x >> 4, y >> 4, z >> 4);
            byte[] cells = sections.get(key);
            if (cells == null) {
                return BlockCellType.UNLOADED;
            }
            return cells[index(x & 15, y & 15, z & 15)];
        }
    }
}

