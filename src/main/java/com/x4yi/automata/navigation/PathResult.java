
package com.x4yi.automata.navigation;

public final class PathResult {

    public static final PathResult EMPTY = new PathResult(new long[0], new byte[0], false, 0, 0L);

    private final long[] packedSteps;
    private final byte[] stepActions;
    private final boolean complete;
    private final int expandedNodes;
    private final long elapsedNanos;

    public PathResult(
            long[] packedSteps,
            byte[] stepActions,
            boolean complete,
            int expandedNodes,
            long elapsedNanos) {
        this.packedSteps = packedSteps;
        this.stepActions = stepActions;
        this.complete = complete;
        this.expandedNodes = expandedNodes;
        this.elapsedNanos = elapsedNanos;
    }

    public boolean isEmpty() {
        return packedSteps.length == 0;
    }

    public int length() {
        return packedSteps.length;
    }

    public long getStep(int index) {
        return packedSteps[index];
    }

    public byte getAction(int index) {
        return stepActions[index];
    }

    public boolean isComplete() {
        return complete;
    }

    public int getExpandedNodes() {
        return expandedNodes;
    }

    public long getElapsedNanos() {
        return elapsedNanos;
    }
}

