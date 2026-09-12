
package com.x4yi.automata.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackedPosTest {

    @Test
    void packAndUnpackRoundTrip() {
        int[][] cases = {
            {0, 0, 0},
            {123, 64, -456},
            {-30000000, 255, 30000000},
            {-1, 1, -1},
            {1024, 128, 2048}
        };

        for (int[] c : cases) {
            long packed = PackedPos.pack(c[0], c[1], c[2]);
            assertEquals(c[0], PackedPos.unpackX(packed));
            assertEquals(c[1], PackedPos.unpackY(packed));
            assertEquals(c[2], PackedPos.unpackZ(packed));
        }
    }
}

