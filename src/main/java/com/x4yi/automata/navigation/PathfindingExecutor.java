
package com.x4yi.automata.navigation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.x4yi.automata.config.AutomataConfig;

public final class PathfindingExecutor {

    private static final int MAX_QUEUED_PATHS = 64;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static volatile ThreadPoolExecutor executor;
    private static volatile int configuredThreads = -1;

    private PathfindingExecutor() {}

    private static ThreadPoolExecutor getOrCreatePool() {
        int desired = Math.max(1, AutomataConfig.maxPathfindingThreads);
        ThreadPoolExecutor current = executor;
        if (current != null && !current.isShutdown() && configuredThreads == desired) {
            return current;
        }
        synchronized (PathfindingExecutor.class) {
            current = executor;
            if (current == null || current.isShutdown() || configuredThreads != desired) {
                if (current != null && !current.isShutdown()) {
                    current.shutdownNow();
                }
                configuredThreads = desired;
                ThreadFactory factory = r -> {
                    Thread t = new Thread(r, "Automata-Pathfinder-" + THREAD_COUNTER.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                };
                executor = new ThreadPoolExecutor(
                        desired,
                        desired,
                        60L,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(MAX_QUEUED_PATHS),
                        factory,
                        (rejected, pool) -> {
                            if (pool.isShutdown()) {
                                cancelIfPathTask(rejected);
                                return;
                            }
                            Runnable oldest = pool.getQueue().poll();
                            cancelIfPathTask(oldest);
                            if (!pool.getQueue().offer(rejected)) {
                                cancelIfPathTask(rejected);
                            }
                        });
            }
            return executor;
        }
    }

    private static void cancelIfPathTask(Runnable runnable) {
        if (runnable instanceof PathTask task) {
            task.cancelAsEmpty();
        }
    }

    public static CompletableFuture<PathResult> submitServerPath(
            World world,
            BlockPos start,
            BlockPos goal,
            int cacheChunkRadius,
            PathfindingSettings settings,
            AtomicBoolean cancelled) {
        return submitServerPath(world, start, goal, cacheChunkRadius, settings, cancelled, null);
    }

    public static CompletableFuture<PathResult> submitServerPath(
            World world,
            BlockPos start,
            BlockPos goal,
            int cacheChunkRadius,
            PathfindingSettings settings,
            AtomicBoolean cancelled,
            it.unimi.dsi.fastutil.longs.Long2FloatMap nodePenalties) {
        if (world.isRemote) {
            return CompletableFuture.completedFuture(PathResult.EMPTY);
        }
        long benchCacheStart = com.x4yi.automata.bench.InGameBenchmarkProfiler.isActive() ? System.nanoTime() : 0L;
        WorldCacheManager.ensureCorridorCached(world, start, goal, cacheChunkRadius);
        INavigationWorldView snapshot = WorldCacheManager.createCorridorSnapshotView(world, start, goal, cacheChunkRadius);
        if (benchCacheStart != 0L) {
            com.x4yi.automata.bench.InGameBenchmarkProfiler.record(
                    com.x4yi.automata.bench.InGameBenchmarkProfiler.Phase.WORLD_CACHE_CORRIDOR,
                    System.nanoTime() - benchCacheStart);
        }

        CompletableFuture<PathResult> future = new CompletableFuture<>();
        getOrCreatePool().execute(new PathTask(snapshot, start, goal, settings, cancelled, future, nodePenalties));
        return future;
    }

    private static final class PathTask implements Runnable {
        private final INavigationWorldView snapshot;
        private final BlockPos start;
        private final BlockPos goal;
        private final PathfindingSettings settings;
        private final AtomicBoolean cancelled;
        private final CompletableFuture<PathResult> future;
        private final it.unimi.dsi.fastutil.longs.Long2FloatMap nodePenalties;

        PathTask(
                INavigationWorldView snapshot,
                BlockPos start,
                BlockPos goal,
                PathfindingSettings settings,
                AtomicBoolean cancelled,
                CompletableFuture<PathResult> future,
                it.unimi.dsi.fastutil.longs.Long2FloatMap nodePenalties) {
            this.snapshot = snapshot;
            this.start = start;
            this.goal = goal;
            this.settings = settings;
            this.cancelled = cancelled;
            this.future = future;
            this.nodePenalties = nodePenalties;
        }

        void cancelAsEmpty() {
            cancelled.set(true);
            future.complete(PathResult.EMPTY);
        }

        @Override
        public void run() {
            if (cancelled.get()) {
                future.complete(PathResult.EMPTY);
                return;
            }
            try {
                long benchPathStart = com.x4yi.automata.bench.InGameBenchmarkProfiler.isActive() ? System.nanoTime() : 0L;
                PathResult result = AutomatonPathfinder.findPath(
                        snapshot,
                        start.getX(), start.getY(), start.getZ(),
                        goal.getX(), goal.getY(), goal.getZ(),
                        settings,
                        cancelled,
                        nodePenalties);
                if (benchPathStart != 0L) {
                    com.x4yi.automata.bench.InGameBenchmarkProfiler.record(
                            com.x4yi.automata.bench.InGameBenchmarkProfiler.Phase.PATHFINDER_ASYNC,
                            System.nanoTime() - benchPathStart);
                }
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
    }

    public static void refreshPoolIfNeeded() {
        getOrCreatePool();
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            configuredThreads = -1;
        }
    }
}

