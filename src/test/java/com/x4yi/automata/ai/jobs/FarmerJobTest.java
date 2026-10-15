package com.x4yi.automata.ai.jobs;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FarmerJobTest {

    @Test
    void computeRowIndexAlignsWithLongerFarmAxis() {
        assertEquals(12, FarmerJob.computeRowIndex(5, 12, 0, 10, 10, 14));
        assertEquals(5, FarmerJob.computeRowIndex(5, 12, 0, 4, 10, 20));
    }

    @Test
    void selectInterleavedSeedIndexAlternatesRowsAndAvoidsNeighbors() {
        List<String> crops = Arrays.asList("minecraft:potatoes", "minecraft:carrots");
        assertEquals(0, FarmerJob.selectInterleavedSeedIndex(0, crops, Collections.emptySet()));
        assertEquals(1, FarmerJob.selectInterleavedSeedIndex(1, crops, Collections.emptySet()));
        assertEquals(0, FarmerJob.selectInterleavedSeedIndex(2, crops, Collections.emptySet()));

        Set<String> neighborHasPotatoes = new HashSet<>(Collections.singletonList("minecraft:potatoes"));
        assertEquals(1, FarmerJob.selectInterleavedSeedIndex(0, crops, neighborHasPotatoes));
        assertEquals(-1, FarmerJob.selectInterleavedSeedIndex(0, Collections.emptyList(), Collections.emptySet()));
    }
}

