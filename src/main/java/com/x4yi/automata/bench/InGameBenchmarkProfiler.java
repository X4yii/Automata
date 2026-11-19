package com.x4yi.automata.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.entity.EntityAutomaton;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class InGameBenchmarkProfiler {

    public enum Phase {
        LIVING_UPDATE("EntityAutomaton.onLivingUpdate (main)", true),
        BRAIN_TOTAL("  - AutomatonBrain.tickServer", false),
        BRAIN_SURVIVAL("    - Survival & Hazard 20Hz", false),
        BRAIN_DECISION_AND_JOB("    - UtilityArbiter & ActiveJob", false),
        BRAIN_COMBAT_20HZ("    - Combat 20Hz", false),
        BRAIN_MOVEMENT_20HZ("    - Movement & Separation 20Hz", false),
        WORLD_CACHE_CORRIDOR("    - WorldCache corridor snapshot", false),
        PATHFINDER_ASYNC("AutomatonPathfinder.findPath (async pool)", false);

        final String label;
        final boolean mainThreadTopLevel;

        Phase(String label, boolean mainThreadTopLevel) {
            this.label = label;
            this.mainThreadTopLevel = mainThreadTopLevel;
        }
    }

    public static final int DEFAULT_SECONDS = 10;
    public static final int MAX_SECONDS = 3600;
    public static final int DEFAULT_TICKS = DEFAULT_SECONDS * 20;
    public static final int MAX_TICKS = MAX_SECONDS * 20;
    private static final int INITIAL_MANUAL_CAPACITY = 1200;
    private static final int RESERVOIR_CAPACITY = 8192;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static volatile boolean active = false;
    private static final Object SESSION_LOCK = new Object();

    private static int targetTicks = DEFAULT_TICKS;
    private static boolean manualMode = false;
    private static int recordedTicks = 0;
    private static long tickStartNanos = 0L;
    private static long[] serverTickNanos = new long[0];
    private static long[] mainThreadAutomataNanos = new long[0];
    private static int[] aliveBotCounts = new int[0];
    private static int[] downedBotCounts = new int[0];
    private static int[] activeSquadCounts = new int[0];
    private static int[] loadedEntityCounts = new int[0];
    private static int[] loadedChunkCounts = new int[0];
    private static final java.util.Set<String> SQUAD_SCRATCH = new java.util.HashSet<>();
    private static final AtomicLong currentTickAutomataNanos = new AtomicLong(0L);

    private static Path outputDirectory = Paths.get("automata", "bench");
    private static Consumer<String> completionCallback = null;

    private static final PhaseStats[] PHASE_STATS = new PhaseStats[Phase.values().length];

    static {
        for (int i = 0; i < PHASE_STATS.length; i++) {
            PHASE_STATS[i] = new PhaseStats();
        }
    }

    private InGameBenchmarkProfiler() {}

    public static boolean isActive() {
        return active;
    }

    public static int getRecordedTicks() {
        synchronized (SESSION_LOCK) {
            return recordedTicks;
        }
    }

    public static int getTargetTicks() {
        synchronized (SESSION_LOCK) {
            return targetTicks;
        }
    }

    public static boolean isManualMode() {
        synchronized (SESSION_LOCK) {
            return manualMode;
        }
    }

    public static double getElapsedSeconds() {
        synchronized (SESSION_LOCK) {
            return recordedTicks / 20.0D;
        }
    }

    public static double getTargetSeconds() {
        synchronized (SESSION_LOCK) {
            return manualMode ? 0.0D : (targetTicks / 20.0D);
        }
    }

    public static boolean startSeconds(int seconds, Path benchDir, Consumer<String> onComplete) {
        if (seconds <= 0) {
            return startInternal(0, true, benchDir, onComplete);
        }
        int clampedSeconds = Math.min(MAX_SECONDS, seconds);
        return startInternal(clampedSeconds * 20, false, benchDir, onComplete);
    }

    public static boolean start(int ticks, Path benchDir, Consumer<String> onComplete) {
        if (ticks <= 0) {
            return startInternal(0, true, benchDir, onComplete);
        }
        return startInternal(Math.min(MAX_TICKS, ticks), false, benchDir, onComplete);
    }

    private static boolean startInternal(int ticks, boolean manual, Path benchDir, Consumer<String> onComplete) {
        int initialCapacity = manual ? INITIAL_MANUAL_CAPACITY : Math.max(1, ticks);
        synchronized (SESSION_LOCK) {
            if (active) {
                return false;
            }
            manualMode = manual;
            targetTicks = manual ? MAX_TICKS : ticks;
            recordedTicks = 0;
            tickStartNanos = 0L;
            serverTickNanos = new long[initialCapacity];
            mainThreadAutomataNanos = new long[initialCapacity];
            aliveBotCounts = new int[initialCapacity];
            downedBotCounts = new int[initialCapacity];
            activeSquadCounts = new int[initialCapacity];
            loadedEntityCounts = new int[initialCapacity];
            loadedChunkCounts = new int[initialCapacity];
            currentTickAutomataNanos.set(0L);
            outputDirectory = benchDir != null ? benchDir : Paths.get("automata", "bench");
            completionCallback = onComplete;
            for (PhaseStats stats : PHASE_STATS) {
                stats.reset();
            }
            active = true;
            return true;
        }
    }

    public static void record(Phase phase, long elapsedNanos) {
        if (!active || elapsedNanos < 0L) {
            return;
        }
        PHASE_STATS[phase.ordinal()].record(elapsedNanos);
        if (phase.mainThreadTopLevel) {
            currentTickAutomataNanos.addAndGet(elapsedNanos);
        }
    }

    public static Path stopAndWriteReport() {
        SessionSnapshot snapshot;
        Consumer<String> callback;
        synchronized (SESSION_LOCK) {
            if (!active) {
                return null;
            }
            active = false;
            int n = Math.min(recordedTicks, serverTickNanos.length);
            snapshot = new SessionSnapshot(
                    n,
                    targetTicks,
                    Arrays.copyOf(serverTickNanos, n),
                    Arrays.copyOf(mainThreadAutomataNanos, n),
                    Arrays.copyOf(aliveBotCounts, n),
                    Arrays.copyOf(downedBotCounts, n),
                    Arrays.copyOf(activeSquadCounts, n),
                    Arrays.copyOf(loadedEntityCounts, n),
                    Arrays.copyOf(loadedChunkCounts, n),
                    capturePhaseSummaries(),
                    outputDirectory);
            callback = completionCallback;
            completionCallback = null;
        }

        Path writtenPath = writeSnapshotReport(snapshot);
        if (callback != null) {
            String summary = formatChatSummary(snapshot, writtenPath);
            callback.accept(summary);
        }
        return writtenPath;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!active) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            tickStartNanos = System.nanoTime();
            currentTickAutomataNanos.set(0L);
            return;
        }
        if (event.phase != TickEvent.Phase.END || tickStartNanos == 0L) {
            return;
        }

        long tickElapsed = Math.max(0L, System.nanoTime() - tickStartNanos);
        tickStartNanos = 0L;

        int bots = 0;
        int downedBots = 0;
        int entities = 0;
        int chunks = 0;
        SQUAD_SCRATCH.clear();
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            for (WorldServer world : server.worlds) {
                if (world == null) {
                    continue;
                }
                entities += world.loadedEntityList.size();
                chunks += world.getChunkProvider().getLoadedChunkCount();
                for (net.minecraft.entity.Entity entity : world.loadedEntityList) {
                    if (entity instanceof EntityAutomaton bot && !entity.isDead) {
                        if (bot.isDowned()) {
                            downedBots++;
                        } else if (bot.isEntityAlive()) {
                            bots++;
                        }
                        java.util.UUID owner = bot.getOwnerUuid();
                        String channel = bot.getTaskConfig().getSquadChannel();
                        String normChannel = channel == null ? "" : channel.trim();
                        SQUAD_SCRATCH.add((owner != null ? owner.toString() : "no-owner") + ":" + normChannel);
                    }
                }
            }
        }

        recordCompletedTick(
                tickElapsed,
                currentTickAutomataNanos.getAndSet(0L),
                bots,
                downedBots,
                SQUAD_SCRATCH.size(),
                entities,
                chunks);
    }

    static void recordCompletedTick(
            long serverTickElapsedNanos,
            long automataMainNanos,
            int aliveBots,
            int loadedEntities,
            int loadedChunks) {
        recordCompletedTick(serverTickElapsedNanos, automataMainNanos, aliveBots, 0, 0, loadedEntities, loadedChunks);
    }

    static void recordCompletedTick(
            long serverTickElapsedNanos,
            long automataMainNanos,
            int aliveBots,
            int downedBots,
            int activeSquads,
            int loadedEntities,
            int loadedChunks) {
        boolean shouldFinish = false;
        synchronized (SESSION_LOCK) {
            if (!active) {
                return;
            }
            if (recordedTicks >= serverTickNanos.length && serverTickNanos.length < MAX_TICKS) {
                int nextCap = Math.min(MAX_TICKS, Math.max(recordedTicks + 1, serverTickNanos.length * 2));
                serverTickNanos = Arrays.copyOf(serverTickNanos, nextCap);
                mainThreadAutomataNanos = Arrays.copyOf(mainThreadAutomataNanos, nextCap);
                aliveBotCounts = Arrays.copyOf(aliveBotCounts, nextCap);
                downedBotCounts = Arrays.copyOf(downedBotCounts, nextCap);
                activeSquadCounts = Arrays.copyOf(activeSquadCounts, nextCap);
                loadedEntityCounts = Arrays.copyOf(loadedEntityCounts, nextCap);
                loadedChunkCounts = Arrays.copyOf(loadedChunkCounts, nextCap);
            }
            if (recordedTicks < serverTickNanos.length) {
                serverTickNanos[recordedTicks] = serverTickElapsedNanos;
                mainThreadAutomataNanos[recordedTicks] = automataMainNanos;
                aliveBotCounts[recordedTicks] = aliveBots;
                downedBotCounts[recordedTicks] = downedBots;
                activeSquadCounts[recordedTicks] = activeSquads;
                loadedEntityCounts[recordedTicks] = loadedEntities;
                loadedChunkCounts[recordedTicks] = loadedChunks;
                recordedTicks++;
            }
            if ((!manualMode && recordedTicks >= targetTicks) || recordedTicks >= MAX_TICKS) {
                shouldFinish = true;
            }
        }
        if (shouldFinish) {
            stopAndWriteReport();
        }
    }

    private static PhaseSummary[] capturePhaseSummaries() {
        Phase[] phases = Phase.values();
        PhaseSummary[] summaries = new PhaseSummary[phases.length];
        for (int i = 0; i < phases.length; i++) {
            summaries[i] = PHASE_STATS[i].snapshot(phases[i]);
        }
        return summaries;
    }

    private static Path writeSnapshotReport(SessionSnapshot snapshot) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
        Path dir = snapshot.outputDirectory;
        Path outFile = dir.resolve(timestamp + "_ingame.log");
        String report = buildReportText(timestamp, snapshot);
        try {
            Files.createDirectories(dir);
            Files.write(outFile, report.getBytes(StandardCharsets.UTF_8));
            return outFile;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String buildReportText(String timestamp, SessionSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("=== Automata In-Game Performance Benchmark (").append(timestamp).append(") ===").append('\n');
        sb.append(String.format(
                Locale.ROOT,
                "Duration: %.1fs (%d ticks) | Avg bots: %.1f alive, %.1f downed | Avg squads: %.1f | Avg loaded entities: %.1f | Avg loaded chunks: %.1f%n",
                snapshot.sampledTicks / 20.0D,
                snapshot.sampledTicks,
                meanInt(snapshot.aliveBots),
                meanInt(snapshot.downedBots),
                meanInt(snapshot.activeSquads),
                meanInt(snapshot.loadedEntities),
                meanInt(snapshot.loadedChunks)));

        TimingStats serverStats = computeTimingStats(snapshot.serverTickNanos);
        TimingStats automataStats = computeTimingStats(snapshot.mainThreadAutomataNanos);
        double budgetPct = serverStats.avgMs > 0.0D ? (automataStats.avgMs / 50.0D) * 100.0D : 0.0D;
        double shareOfServerPct = serverStats.avgMs > 0.0D ? (automataStats.avgMs / serverStats.avgMs) * 100.0D : 0.0D;

        sb.append(String.format(
                Locale.ROOT,
                "Server Tick (MSPT, all mods + vanilla) | avg: %7.3f ms | p95: %7.3f ms | max: %7.3f ms | 50ms budget: %5.1f%%%n",
                serverStats.avgMs,
                serverStats.p95Ms,
                serverStats.maxMs,
                (serverStats.avgMs / 50.0D) * 100.0D));
        sb.append(String.format(
                Locale.ROOT,
                "Automata Main-Thread Total             | avg: %7.3f ms | p95: %7.3f ms | max: %7.3f ms | 50ms budget: %5.1f%% (%.1f%% of server tick)%n",
                automataStats.avgMs,
                automataStats.p95Ms,
                automataStats.maxMs,
                budgetPct,
                shareOfServerPct));
        sb.append('\n');
        sb.append("Per-Phase Breakdown (per call / per path):").append('\n');
        for (PhaseSummary phase : snapshot.phases) {
            double avgPerTickMs = snapshot.sampledTicks > 0
                    ? (phase.totalNanos / 1_000_000.0D) / snapshot.sampledTicks
                    : 0.0D;
            sb.append(String.format(
                    Locale.ROOT,
                    "%-45s | calls: %7d | avg/call: %7.2f us | p95: %7.2f us | max: %7.2f us | avg/tick: %6.3f ms%n",
                    phase.phase.label,
                    phase.count,
                    phase.avgNanos / 1000.0D,
                    phase.p95Nanos / 1000.0D,
                    phase.maxNanos / 1000.0D,
                    avgPerTickMs));
        }
        return sb.toString();
    }

    private static String formatChatSummary(SessionSnapshot snapshot, Path writtenPath) {
        TimingStats serverStats = computeTimingStats(snapshot.serverTickNanos);
        TimingStats automataStats = computeTimingStats(snapshot.mainThreadAutomataNanos);
        String pathStr = writtenPath != null ? writtenPath.toString() : "(failed to write file)";
        return String.format(
                Locale.ROOT,
                "Bench done (%.1fs / %d ticks, %.1f bots, %.1f downed, %.1f squads, %.0f entities) | MSPT avg %.2f ms (p95 %.2f ms) | Automata %.2f ms/tick | Log: %s",
                snapshot.sampledTicks / 20.0D,
                snapshot.sampledTicks,
                meanInt(snapshot.aliveBots),
                meanInt(snapshot.downedBots),
                meanInt(snapshot.activeSquads),
                meanInt(snapshot.loadedEntities),
                serverStats.avgMs,
                serverStats.p95Ms,
                automataStats.avgMs,
                pathStr);
    }

    private static double meanInt(int[] values) {
        if (values.length == 0) {
            return 0.0D;
        }
        long sum = 0L;
        for (int v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }

    private static TimingStats computeTimingStats(long[] nanos) {
        if (nanos.length == 0) {
            return new TimingStats(0.0D, 0.0D, 0.0D);
        }
        long sum = 0L;
        long max = 0L;
        for (long v : nanos) {
            sum += v;
            if (v > max) {
                max = v;
            }
        }
        long[] sorted = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(sorted);
        int p95Index = Math.min(sorted.length - 1, (int) Math.floor((sorted.length - 1) * 0.95D));
        return new TimingStats(
                (sum / (double) nanos.length) / 1_000_000.0D,
                sorted[p95Index] / 1_000_000.0D,
                max / 1_000_000.0D);
    }

    private static final class TimingStats {
        final double avgMs;
        final double p95Ms;
        final double maxMs;

        TimingStats(double avgMs, double p95Ms, double maxMs) {
            this.avgMs = avgMs;
            this.p95Ms = p95Ms;
            this.maxMs = maxMs;
        }
    }

    private static final class PhaseStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final long[] reservoir = new long[RESERVOIR_CAPACITY];
        private int reservoirCount = 0;
        private long maxNanos = 0L;
        private long rngState = 0x9E3779B97F4A7C15L;

        synchronized void reset() {
            count.reset();
            totalNanos.reset();
            reservoirCount = 0;
            maxNanos = 0L;
        }

        void record(long elapsedNanos) {
            count.increment();
            totalNanos.add(elapsedNanos);
            synchronized (this) {
                if (elapsedNanos > maxNanos) {
                    maxNanos = elapsedNanos;
                }
                if (reservoirCount < RESERVOIR_CAPACITY) {
                    reservoir[reservoirCount++] = elapsedNanos;
                } else {
                    long total = count.sum();
                    rngState ^= (rngState << 13);
                    rngState ^= (rngState >>> 7);
                    rngState ^= (rngState << 17);
                    long idx = (rngState & Long.MAX_VALUE) % Math.max(1L, total);
                    if (idx < RESERVOIR_CAPACITY) {
                        reservoir[(int) idx] = elapsedNanos;
                    }
                }
            }
        }

        synchronized PhaseSummary snapshot(Phase phase) {
            long totalCalls = count.sum();
            long totalTime = totalNanos.sum();
            double avg = totalCalls > 0 ? (double) totalTime / totalCalls : 0.0D;
            double p95 = 0.0D;
            if (reservoirCount > 0) {
                long[] sorted = Arrays.copyOf(reservoir, reservoirCount);
                Arrays.sort(sorted);
                int idx = Math.min(sorted.length - 1, (int) Math.floor((sorted.length - 1) * 0.95D));
                p95 = sorted[idx];
            }
            return new PhaseSummary(phase, totalCalls, totalTime, avg, p95, maxNanos);
        }
    }

    private static final class PhaseSummary {
        final Phase phase;
        final long count;
        final long totalNanos;
        final double avgNanos;
        final double p95Nanos;
        final long maxNanos;

        PhaseSummary(Phase phase, long count, long totalNanos, double avgNanos, double p95Nanos, long maxNanos) {
            this.phase = phase;
            this.count = count;
            this.totalNanos = totalNanos;
            this.avgNanos = avgNanos;
            this.p95Nanos = p95Nanos;
            this.maxNanos = maxNanos;
        }
    }

    private static final class SessionSnapshot {
        final int sampledTicks;
        final int targetTicks;
        final long[] serverTickNanos;
        final long[] mainThreadAutomataNanos;
        final int[] aliveBots;
        final int[] downedBots;
        final int[] activeSquads;
        final int[] loadedEntities;
        final int[] loadedChunks;
        final PhaseSummary[] phases;
        final Path outputDirectory;

        SessionSnapshot(
                int sampledTicks,
                int targetTicks,
                long[] serverTickNanos,
                long[] mainThreadAutomataNanos,
                int[] aliveBots,
                int[] downedBots,
                int[] activeSquads,
                int[] loadedEntities,
                int[] loadedChunks,
                PhaseSummary[] phases,
                Path outputDirectory) {
            this.sampledTicks = sampledTicks;
            this.targetTicks = targetTicks;
            this.serverTickNanos = serverTickNanos;
            this.mainThreadAutomataNanos = mainThreadAutomataNanos;
            this.aliveBots = aliveBots;
            this.downedBots = downedBots;
            this.activeSquads = activeSquads;
            this.loadedEntities = loadedEntities;
            this.loadedChunks = loadedChunks;
            this.phases = phases;
            this.outputDirectory = outputDirectory;
        }
    }
}
