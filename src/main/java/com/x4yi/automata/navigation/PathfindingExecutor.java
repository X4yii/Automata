
package com.x4yi.automata.navigation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.x4yi.automata.config.AutomataConfig;

public final class PathfindingExecutor {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static volatile ExecutorService executor;
    private static volatile int configuredThreads = -1;

    private PathfindingExecutor() {}

    private static synchronized ExecutorService getOrCreatePool() {
        int desired = Math.max(1, AutomataConfig.maxPathfindingThreads);
        if (executor == null || executor.isShutdown() || configuredThreads != desired) {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
            }
            configuredThreads = desired;
            ThreadFactory factory = r -> {
                Thread t = new Thread(r, "Automata-Pathfinder-" + THREAD_COUNTER.getAndIncrement());
                t.setDaemon(true);
                return t;
            };
            executor = Executors.newFixedThreadPool(desired, factory);
        }
        return executor;
    }

    public static CompletableFuture<PathResult> submitServerPath(
            World world,
            BlockPos start,
            BlockPos goal,
            int cacheChunkRadius,
            PathfindingSettings settings,
            AtomicBoolean cancelled) {
        if (world.isRemote) {
            return CompletableFuture.completedFuture(PathResult.EMPTY);
        }
        WorldCacheManager.ensureCorridorCached(world, start, goal, cacheChunkRadius);
        INavigationWorldView snapshot = WorldCacheManager.createSnapshotView(world.provider.getDimension());

        return CompletableFuture.supplyAsync(
                () -> AutomatonPathfinder.findPath(
                        snapshot,
                        start.getX(), start.getY(), start.getZ(),
                        goal.getX(), goal.getY(), goal.getZ(),
                        settings,
                        cancelled),
                getOrCreatePool());
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            configuredThreads = -1;
        }
    }
}

