package com.x4yi.automata.client.debug;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.util.EnumFacing;

import com.x4yi.automata.navigation.PackedPos;

/**
 * Computes exterior-only edges and faces for a set of voxel cells so adjacent
 * debug blocks merge into clean connected volumes instead of noisy overlapping boxes.
 */
public final class VoxelOutline {

    @FunctionalInterface
    public interface EdgeConsumer {
        void accept(double x1, double y1, double z1, double x2, double y2, double z2);
    }

    @FunctionalInterface
    public interface FaceConsumer {
        void accept(int x, int y, int z, EnumFacing face);
    }

    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

    private VoxelOutline() {}

    public static void collectExteriorEdges(LongSet cells, EdgeConsumer out) {
        if (cells == null || cells.isEmpty()) {
            return;
        }
        LongOpenHashSet seenEdges = new LongOpenHashSet(cells.size() * 6);
        LongIterator it = cells.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int x = PackedPos.unpackX(packed);
            int y = PackedPos.unpackY(packed);
            int z = PackedPos.unpackZ(packed);

            // 4 X-aligned edges of (x, y, z): (x, y+dy, z+dz) -> (x+1, y+dy, z+dz)
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    int ey = y + dy;
                    int ez = z + dz;
                    long key = packEdgeKey(AXIS_X, x, ey, ez);
                    if (seenEdges.add(key) && isExteriorXEdge(cells, x, ey, ez)) {
                        out.accept(x, ey, ez, x + 1, ey, ez);
                    }
                }
            }

            // 4 Y-aligned edges of (x, y, z): (x+dx, y, z+dz) -> (x+dx, y+1, z+dz)
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    int ex = x + dx;
                    int ez = z + dz;
                    long key = packEdgeKey(AXIS_Y, ex, y, ez);
                    if (seenEdges.add(key) && isExteriorYEdge(cells, ex, y, ez)) {
                        out.accept(ex, y, ez, ex, y + 1, ez);
                    }
                }
            }

            // 4 Z-aligned edges of (x, y, z): (x+dx, y+dy, z) -> (x+dx, y+dy, z+1)
            for (int dx = 0; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    int ex = x + dx;
                    int ey = y + dy;
                    long key = packEdgeKey(AXIS_Z, ex, ey, z);
                    if (seenEdges.add(key) && isExteriorZEdge(cells, ex, ey, z)) {
                        out.accept(ex, ey, z, ex, ey, z + 1);
                    }
                }
            }
        }
    }

    public static void collectExteriorFaces(LongSet cells, FaceConsumer out) {
        if (cells == null || cells.isEmpty()) {
            return;
        }
        LongIterator it = cells.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int x = PackedPos.unpackX(packed);
            int y = PackedPos.unpackY(packed);
            int z = PackedPos.unpackZ(packed);
            for (EnumFacing face : EnumFacing.VALUES) {
                long neighbor = PackedPos.pack(x + face.getXOffset(), y + face.getYOffset(), z + face.getZOffset());
                if (!cells.contains(neighbor)) {
                    out.accept(x, y, z, face);
                }
            }
        }
    }

    private static boolean isExteriorXEdge(LongSet cells, int x, int ey, int ez) {
        boolean c00 = cells.contains(PackedPos.pack(x, ey - 1, ez - 1));
        boolean c10 = cells.contains(PackedPos.pack(x, ey, ez - 1));
        boolean c01 = cells.contains(PackedPos.pack(x, ey - 1, ez));
        boolean c11 = cells.contains(PackedPos.pack(x, ey, ez));
        return shouldEmitEdge(c00, c10, c01, c11);
    }

    private static boolean isExteriorYEdge(LongSet cells, int ex, int y, int ez) {
        boolean c00 = cells.contains(PackedPos.pack(ex - 1, y, ez - 1));
        boolean c10 = cells.contains(PackedPos.pack(ex, y, ez - 1));
        boolean c01 = cells.contains(PackedPos.pack(ex - 1, y, ez));
        boolean c11 = cells.contains(PackedPos.pack(ex, y, ez));
        return shouldEmitEdge(c00, c10, c01, c11);
    }

    private static boolean isExteriorZEdge(LongSet cells, int ex, int ey, int z) {
        boolean c00 = cells.contains(PackedPos.pack(ex - 1, ey - 1, z));
        boolean c10 = cells.contains(PackedPos.pack(ex, ey - 1, z));
        boolean c01 = cells.contains(PackedPos.pack(ex - 1, ey, z));
        boolean c11 = cells.contains(PackedPos.pack(ex, ey, z));
        return shouldEmitEdge(c00, c10, c01, c11);
    }

    private static boolean shouldEmitEdge(boolean c00, boolean c10, boolean c01, boolean c11) {
        int count = (c00 ? 1 : 0) + (c10 ? 1 : 0) + (c01 ? 1 : 0) + (c11 ? 1 : 0);
        return count == 1 || count == 3 || (count == 2 && c00 == c11);
    }

    private static long packEdgeKey(int axis, int x, int y, int z) {
        return (((long) axis) << 60)
                | (((long) (x & 0xFFFFFF)) << 36)
                | (((long) (y & 0xFFF)) << 24)
                | ((long) (z & 0xFFFFFF));
    }
}
