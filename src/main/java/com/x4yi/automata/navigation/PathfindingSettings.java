package com.x4yi.automata.navigation;

public record PathfindingSettings(
        boolean allowBreakThrough,
        boolean allowSprintJump,
        boolean allowScaffolding,
        int maxExpandedNodes,
        int maxFallDistance) {

    public static final int DEFAULT_MAX_NODES = 8192;
    public static final int DEFAULT_MAX_FALL = 4;

    public PathfindingSettings {
        maxExpandedNodes = Math.max(64, maxExpandedNodes);
        maxFallDistance = Math.max(1, maxFallDistance);
    }

    public static PathfindingSettings defaults(boolean allowBreakThrough, boolean allowScaffolding) {
        return new PathfindingSettings(
                allowBreakThrough,
                true,
                allowScaffolding,
                DEFAULT_MAX_NODES,
                DEFAULT_MAX_FALL);
    }
}
