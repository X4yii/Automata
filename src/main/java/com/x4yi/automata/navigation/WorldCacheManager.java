
package com.x4yi.automata.navigation;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

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
    private static final long SECTION_REFRESH_INTERVAL_MS = 1500L;
    private static final byte[] EMPTY_SECTION = new byte[SECTION_VOLUME];

    private static final Map<Integer, ConcurrentHashMap<Long, byte[]>> DIMENSION_SECTIONS =
            new ConcurrentHashMap<>();
    private static final Map<Integer, ConcurrentHashMap<Long, Long>> DIMENSION_SECTION_TIMESTAMPS =
            new ConcurrentHashMap<>();

    private WorldCacheManager() {}

    public static INavigationWorldView createSnapshotView(int dimensionId) {
        ConcurrentHashMap<Long, byte[]> sections = DIMENSION_SECTIONS.get(dimensionId);
        return new SnapshotView(sections);
    }

    public static void ensureRegionCached(World world, BlockPos center, int chunkRadius) {
        if (world.isRemote) {
            return;
        }
        int dim = world.provider.getDimension();
        ConcurrentHashMap<Long, byte[]> sections = DIMENSION_SECTIONS.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        ConcurrentHashMap<Long, Long> timestamps = DIMENSION_SECTION_TIMESTAMPS.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        long nowMs = System.currentTimeMillis();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                cacheChunkIfLoaded(world, sections, timestamps, centerChunkX + dx, centerChunkZ + dz, nowMs);
            }
        }
        evictOldestIfOversized(sections, timestamps);
    }

    public static void ensureCorridorCached(World world, BlockPos start, BlockPos goal, int paddingChunks) {
        if (world.isRemote) {
            return;
        }
        int dim = world.provider.getDimension();
        ConcurrentHashMap<Long, byte[]> sections = DIMENSION_SECTIONS.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        ConcurrentHashMap<Long, Long> timestamps = DIMENSION_SECTION_TIMESTAMPS.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        long nowMs = System.currentTimeMillis();
        int minCx = (Math.min(start.getX(), goal.getX()) >> 4) - paddingChunks;
        int maxCx = (Math.max(start.getX(), goal.getX()) >> 4) + paddingChunks;
        int minCz = (Math.min(start.getZ(), goal.getZ()) >> 4) - paddingChunks;
        int maxCz = (Math.max(start.getZ(), goal.getZ()) >> 4) + paddingChunks;

        int spanX = maxCx - minCx + 1;
        int spanZ = maxCz - minCz + 1;
        if (spanX * spanZ > 121) {
            ensureRegionCached(world, start, 2);
            ensureRegionCached(world, goal, 2);
            return;
        }
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                cacheChunkIfLoaded(world, sections, timestamps, cx, cz, nowMs);
            }
        }
        refreshStartChunk(world, sections, timestamps, start.getX() >> 4, start.getZ() >> 4, nowMs);
        evictOldestIfOversized(sections, timestamps);
    }

    private static void evictOldestIfOversized(
            ConcurrentHashMap<Long, byte[]> sections,
            ConcurrentHashMap<Long, Long> timestamps) {
        int excess = sections.size() - MAX_SECTIONS_PER_DIMENSION;
        if (excess <= 0) {
            return;
        }
        int targetRemove = excess + (MAX_SECTIONS_PER_DIMENSION / 4);
        java.util.List<Map.Entry<Long, Long>> entries = new java.util.ArrayList<>(timestamps.entrySet());
        entries.sort(Map.Entry.comparingByValue());
        int removed = 0;
        for (Map.Entry<Long, Long> entry : entries) {
            if (removed >= targetRemove) {
                break;
            }
            Long key = entry.getKey();
            sections.remove(key);
            timestamps.remove(key);
            removed++;
        }
    }

    private static void refreshStartChunk(
            World world,
            ConcurrentHashMap<Long, byte[]> sections,
            ConcurrentHashMap<Long, Long> timestamps,
            int cx,
            int cz,
            long nowMs) {
        if (!world.isBlockLoaded(new BlockPos((cx << 4) + 8, 64, (cz << 4) + 8))) {
            return;
        }
        Chunk chunk = world.getChunk(cx, cz);
        for (int sy = 0; sy < SECTIONS_PER_CHUNK; sy++) {
            long key = PackedPos.packSection(cx, sy, cz);
            sections.put(key, snapshotSection(world, chunk, sy));
            timestamps.put(key, nowMs);
        }
    }

    private static void cacheChunkIfLoaded(
            World world,
            ConcurrentHashMap<Long, byte[]> sections,
            ConcurrentHashMap<Long, Long> timestamps,
            int cx,
            int cz,
            long nowMs) {
        if (!world.isBlockLoaded(new BlockPos((cx << 4) + 8, 64, (cz << 4) + 8))) {
            return;
        }
        Chunk chunk = world.getChunk(cx, cz);
        for (int sy = 0; sy < SECTIONS_PER_CHUNK; sy++) {
            long key = PackedPos.packSection(cx, sy, cz);
            Long lastRefresh = timestamps.get(key);
            if (!sections.containsKey(key) || lastRefresh == null || nowMs - lastRefresh >= SECTION_REFRESH_INTERVAL_MS) {
                sections.put(key, snapshotSection(world, chunk, sy));
                timestamps.put(key, nowMs);
            }
        }
    }

    public static void invalidateBlock(World world, BlockPos pos) {
        if (world.isRemote || pos.getY() < 0 || pos.getY() >= 256) {
            return;
        }
        int dim = world.provider.getDimension();
        ConcurrentHashMap<Long, byte[]> sections = DIMENSION_SECTIONS.get(dim);
        if (sections == null) {
            return;
        }
        int cx = pos.getX() >> 4;
        int sy = pos.getY() >> 4;
        int cz = pos.getZ() >> 4;
        long key = PackedPos.packSection(cx, sy, cz);
        byte[] existing = sections.get(key);
        if (existing != null && world.isBlockLoaded(pos)) {
            byte nextCell = classifyBlock(world, pos, world.getBlockState(pos));
            int cellIdx = index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            if (existing == EMPTY_SECTION) {
                if (nextCell == BlockCellType.AIR) {
                    return;
                }
                byte[] copy = new byte[SECTION_VOLUME];
                copy[cellIdx] = nextCell;
                sections.put(key, copy);
            } else if (existing[cellIdx] != nextCell) {
                byte[] copy = Arrays.copyOf(existing, existing.length);
                copy[cellIdx] = nextCell;
                sections.put(key, copy);
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
        Material mat = state.getMaterial();
        if (mat == Material.LAVA || mat == Material.FIRE) {
            return BlockCellType.HAZARD;
        }
        if (mat == Material.WATER) {
            return BlockCellType.WATER;
        }
        if (mat == Material.AIR) {
            return BlockCellType.AIR;
        }
        return classifyCollisionBox(state.getCollisionBoundingBox(world, pos));
    }

    static byte classifyCollisionBox(@Nullable AxisAlignedBB box) {
        if (box == null || box.maxY <= PASSABLE_LAYER_MAX_Y || box.maxX - box.minX <= 0.0D || box.maxZ - box.minZ <= 0.0D) {
            return BlockCellType.AIR;
        }
        if (box.maxY > 1.0D) {
            return BlockCellType.TALL_OBSTACLE;
        }
        return BlockCellType.SOLID;
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
        ConcurrentHashMap<Long, byte[]> sections = DIMENSION_SECTIONS.get(world.provider.getDimension());
        ConcurrentHashMap<Long, Long> timestamps = DIMENSION_SECTION_TIMESTAMPS.get(world.provider.getDimension());
        if (sections == null && timestamps == null) {
            return;
        }
        Chunk chunk = event.getChunk();
        for (int sy = 0; sy < SECTIONS_PER_CHUNK; sy++) {
            long key = PackedPos.packSection(chunk.x, sy, chunk.z);
            if (sections != null) {
                sections.remove(key);
            }
            if (timestamps != null) {
                timestamps.remove(key);
            }
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (!world.isRemote) {
            int dim = world.provider.getDimension();
            DIMENSION_SECTIONS.remove(dim);
            DIMENSION_SECTION_TIMESTAMPS.remove(dim);
        }
    }

    private static final class SnapshotView implements INavigationWorldView {
        private final ConcurrentHashMap<Long, byte[]> sections;

        SnapshotView(ConcurrentHashMap<Long, byte[]> sections) {
            this.sections = sections;
        }

        @Override
        public byte getCellType(int x, int y, int z) {
            if (y < 0 || y >= 256 || sections == null) {
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

