package com.x4yi.automata.client.debug;

import java.util.concurrent.atomic.AtomicInteger;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.junit.jupiter.api.Test;

import com.x4yi.automata.navigation.PackedPos;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxelOutlineTest {

    @Test
    void singleBlockHasTwelveEdgesAndSixFaces() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(PackedPos.pack(0, 64, 0));

        assertEquals(12, countEdges(cells));
        assertEquals(6, countFaces(cells));
    }

    @Test
    void twoAdjacentBlocksMergeIntoSixteenUnitEdgesAndTenFaces() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(PackedPos.pack(0, 64, 0));
        cells.add(PackedPos.pack(1, 64, 0));

        assertEquals(16, countEdges(cells));
        assertEquals(10, countFaces(cells));
    }

    @Test
    void flatTwoByTwoSquareOmitsInteriorCenterAndCoplanarEdges() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(PackedPos.pack(0, 64, 0));
        cells.add(PackedPos.pack(1, 64, 0));
        cells.add(PackedPos.pack(0, 64, 1));
        cells.add(PackedPos.pack(1, 64, 1));

        assertEquals(20, countEdges(cells));
        assertEquals(16, countFaces(cells));
    }

    @Test
    void lShapePreservesInnerCornerEdge() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(PackedPos.pack(0, 64, 0));
        cells.add(PackedPos.pack(1, 64, 0));
        cells.add(PackedPos.pack(0, 64, 1));

        assertEquals(22, countEdges(cells));
        assertEquals(14, countFaces(cells));
    }

    private static int countEdges(LongOpenHashSet cells) {
        AtomicInteger count = new AtomicInteger();
        VoxelOutline.collectExteriorEdges(cells, (x1, y1, z1, x2, y2, z2) -> count.incrementAndGet());
        return count.get();
    }

    private static int countFaces(LongOpenHashSet cells) {
        AtomicInteger count = new AtomicInteger();
        VoxelOutline.collectExteriorFaces(cells, (x, y, z, face) -> count.incrementAndGet());
        return count.get();
    }
}
