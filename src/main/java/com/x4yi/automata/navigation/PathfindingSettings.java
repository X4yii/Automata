package com.x4yi.automata.navigation;

public record PathfindingSettings(
        boolean allowBreakThrough,
        boolean allowSprintJump,
        boolean allowScaffolding,
        int maxExpandedNodes,
        int maxFallDistance,
        int availableScaffoldBlocks,
        long maxDurationNanos) {

    public static final int DEFAULT_MAX_NODES = 8192;
    public static final int DEFAULT_MAX_FALL = 4;
    public static final long DEFAULT_MAX_DURATION_NANOS = 35_000_000L;
    public static final long TACTICAL_MAX_DURATION_NANOS = 12_000_000L;

    public PathfindingSettings {
        maxExpandedNodes = Math.max(64, maxExpandedNodes);
        maxFallDistance = Math.max(1, maxFallDistance);
        availableScaffoldBlocks = Math.max(0, availableScaffoldBlocks);
        maxDurationNanos = maxDurationNanos <= 0L ? DEFAULT_MAX_DURATION_NANOS : maxDurationNanos;
    }

    public PathfindingSettings(
            boolean allowBreakThrough,
            boolean allowSprintJump,
            boolean allowScaffolding,
            int maxExpandedNodes,
            int maxFallDistance) {
        this(allowBreakThrough, allowSprintJump, allowScaffolding, maxExpandedNodes, maxFallDistance, Integer.MAX_VALUE, DEFAULT_MAX_DURATION_NANOS);
    }

    public PathfindingSettings(
            boolean allowBreakThrough,
            boolean allowSprintJump,
            boolean allowScaffolding,
            int maxExpandedNodes,
            int maxFallDistance,
            int availableScaffoldBlocks) {
        this(allowBreakThrough, allowSprintJump, allowScaffolding, maxExpandedNodes, maxFallDistance, availableScaffoldBlocks, DEFAULT_MAX_DURATION_NANOS);
    }

    public static PathfindingSettings defaults(boolean allowBreakThrough, boolean allowScaffolding) {
        return new PathfindingSettings(
                allowBreakThrough,
                true,
                allowScaffolding,
                DEFAULT_MAX_NODES,
                DEFAULT_MAX_FALL,
                Integer.MAX_VALUE,
                DEFAULT_MAX_DURATION_NANOS);
    }
}
