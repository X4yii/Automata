package com.x4yi.automata.ai;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.entity.EntityAutomaton;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class WorldEntitySpatialIndex {

    private static final Int2ObjectOpenHashMap<DimensionIndex> INDICES = new Int2ObjectOpenHashMap<>();

    private WorldEntitySpatialIndex() {}

    @SubscribeEvent
    public static synchronized void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world != null && !world.isRemote) {
            INDICES.remove(world.provider.getDimension());
        }
    }

    public static synchronized void clearAll() {
        INDICES.clear();
    }

    public static void queryLiving(
            World world,
            double x,
            double y,
            double z,
            double radius,
            double verticalRadius,
            List<EntityLivingBase> out) {
        out.clear();
        if (world == null || world.isRemote) {
            return;
        }
        DimensionIndex index = getOrBuild(world);
        if (index.totalLiving == 0) {
            return;
        }
        int minCx = MathHelper.floor(x - radius) >> 4;
        int maxCx = MathHelper.floor(x + radius) >> 4;
        int minCz = MathHelper.floor(z - radius) >> 4;
        int maxCz = MathHelper.floor(z + radius) >> 4;
        double maxHorizSq = radius * radius;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                ChunkBucket bucket = index.chunks.get(packChunk(cx, cz));
                if (bucket == null) {
                    continue;
                }
                List<EntityLivingBase> list = bucket.living;
                for (int i = 0, n = list.size(); i < n; i++) {
                    EntityLivingBase e = list.get(i);
                    if (Math.abs(e.posY - y) <= verticalRadius) {
                        double dx = e.posX - x;
                        double dz = e.posZ - z;
                        if (dx * dx + dz * dz <= maxHorizSq) {
                            out.add(e);
                        }
                    }
                }
            }
        }
    }

    public static void queryHazards(
            World world,
            Entity exclude,
            double x,
            double y,
            double z,
            double radius,
            double verticalRadius,
            List<Entity> out) {
        out.clear();
        if (world == null || world.isRemote) {
            return;
        }
        DimensionIndex index = getOrBuild(world);
        if (index.totalHazards == 0) {
            return;
        }
        int minCx = MathHelper.floor(x - radius) >> 4;
        int maxCx = MathHelper.floor(x + radius) >> 4;
        int minCz = MathHelper.floor(z - radius) >> 4;
        int maxCz = MathHelper.floor(z + radius) >> 4;
        double maxHorizSq = radius * radius;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                ChunkBucket bucket = index.chunks.get(packChunk(cx, cz));
                if (bucket == null) {
                    continue;
                }
                List<Entity> list = bucket.hazards;
                for (int i = 0, n = list.size(); i < n; i++) {
                    Entity e = list.get(i);
                    if (e != exclude && Math.abs(e.posY - y) <= verticalRadius) {
                        double dx = e.posX - x;
                        double dz = e.posZ - z;
                        if (dx * dx + dz * dz <= maxHorizSq) {
                            out.add(e);
                        }
                    }
                }
            }
        }
    }

    public static List<EntityLivingBase> getFriendlies(World world) {
        if (world == null || world.isRemote) {
            return java.util.Collections.emptyList();
        }
        return getOrBuild(world).friendlies;
    }

    private static DimensionIndex getOrBuild(World world) {
        int dim = world.provider.getDimension();
        long now = world.getTotalWorldTime();
        DimensionIndex index;
        synchronized (WorldEntitySpatialIndex.class) {
            index = INDICES.get(dim);
            if (index == null) {
                index = new DimensionIndex();
                INDICES.put(dim, index);
            }
        }
        if (index.lastBuiltTick != now) {
            index.rebuild(world.loadedEntityList, now);
        }
        return index;
    }

    static long packChunk(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    static final class DimensionIndex {
        final Long2ObjectOpenHashMap<ChunkBucket> chunks = new Long2ObjectOpenHashMap<>(64);
        final List<ChunkBucket> activeBuckets = new ArrayList<>(64);
        final List<ChunkBucket> freeBuckets = new ArrayList<>(64);
        final List<EntityLivingBase> friendlies = new ArrayList<>(32);
        long lastBuiltTick = Long.MIN_VALUE;
        int totalLiving;
        int totalHazards;

        void rebuild(List<Entity> loadedEntities, long now) {
            lastBuiltTick = now;
            totalLiving = 0;
            totalHazards = 0;
            friendlies.clear();
            for (int i = 0, n = activeBuckets.size(); i < n; i++) {
                ChunkBucket bucket = activeBuckets.get(i);
                bucket.clear();
                freeBuckets.add(bucket);
            }
            activeBuckets.clear();
            chunks.clear();

            for (int i = 0, n = loadedEntities.size(); i < n; i++) {
                Entity entity = loadedEntities.get(i);
                if (entity == null || entity.isDead) {
                    continue;
                }
                boolean isLiving = entity instanceof EntityLivingBase living && living.isEntityAlive();
                boolean isHazard = ExplosiveHazardDetector.isHazardOrProjectileCandidate(entity);
                if (!isLiving && !isHazard) {
                    continue;
                }
                int cx = MathHelper.floor(entity.posX) >> 4;
                int cz = MathHelper.floor(entity.posZ) >> 4;
                long key = packChunk(cx, cz);
                ChunkBucket bucket = chunks.get(key);
                if (bucket == null) {
                    bucket = freeBuckets.isEmpty() ? new ChunkBucket() : freeBuckets.remove(freeBuckets.size() - 1);
                    chunks.put(key, bucket);
                    activeBuckets.add(bucket);
                }
                if (isLiving) {
                    EntityLivingBase living = (EntityLivingBase) entity;
                    bucket.living.add(living);
                    totalLiving++;
                    if (entity instanceof EntityAutomaton bot) {
                        if (!bot.isDowned()) {
                            friendlies.add(bot);
                        }
                    } else if (entity instanceof EntityPlayer) {
                        friendlies.add(living);
                    }
                }
                if (isHazard) {
                    bucket.hazards.add(entity);
                    totalHazards++;
                }
            }
        }
    }

    private static final class ChunkBucket {
        final List<EntityLivingBase> living = new ArrayList<>(8);
        final List<Entity> hazards = new ArrayList<>(4);

        void clear() {
            living.clear();
            hazards.clear();
        }
    }
}
