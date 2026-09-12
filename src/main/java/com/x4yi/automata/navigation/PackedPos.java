
package com.x4yi.automata.navigation;

public final class PackedPos {

    private static final int NUM_X_BITS = 26;
    private static final int NUM_Z_BITS = 26;
    private static final int NUM_Y_BITS = 12;

    private static final int Y_SHIFT = NUM_Z_BITS;
    private static final int X_SHIFT = Y_SHIFT + NUM_Y_BITS;

    private static final long X_MASK = (1L << NUM_X_BITS) - 1L;
    private static final long Y_MASK = (1L << NUM_Y_BITS) - 1L;
    private static final long Z_MASK = (1L << NUM_Z_BITS) - 1L;

    private PackedPos() {}

    public static long pack(int x, int y, int z) {
        return (((long) x & X_MASK) << X_SHIFT)
                | (((long) y & Y_MASK) << Y_SHIFT)
                | ((long) z & Z_MASK);
    }

    public static int unpackX(long packed) {
        return (int) (packed << (64 - X_SHIFT - NUM_X_BITS) >> (64 - NUM_X_BITS));
    }

    public static int unpackY(long packed) {
        return (int) (packed << (64 - Y_SHIFT - NUM_Y_BITS) >> (64 - NUM_Y_BITS));
    }

    public static int unpackZ(long packed) {
        return (int) (packed << (64 - NUM_Z_BITS) >> (64 - NUM_Z_BITS));
    }

    public static long packSection(int chunkX, int sectionY, int chunkZ) {
        return pack(chunkX, sectionY, chunkZ);
    }
}

