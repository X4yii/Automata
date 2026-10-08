package com.x4yi.automata.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.x4yi.automata.ai.SurvivalEvaluator.HazardResponse;

class SurvivalEvaluatorTest {

    @Test
    void prioritizesLavaPillarThenWaterThenShadeThenFlee() {
        assertEquals(HazardResponse.LAVA_PILLAR_AND_ESCAPE,
                SurvivalEvaluator.classifyHazardResponse(true, true, true));
        assertEquals(HazardResponse.SEEK_WATER,
                SurvivalEvaluator.classifyHazardResponse(false, true, true));
        assertEquals(HazardResponse.SEEK_SHADE_COVER,
                SurvivalEvaluator.classifyHazardResponse(false, false, true));
        assertEquals(HazardResponse.FLEE_HAZARD,
                SurvivalEvaluator.classifyHazardResponse(false, false, false));
    }
}

