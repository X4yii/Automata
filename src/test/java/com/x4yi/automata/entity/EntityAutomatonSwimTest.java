package com.x4yi.automata.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EntityAutomatonSwimTest {

    @Test
    void stepsAndClampsSwimAnimationBetweenZeroAndOne() {
        float anim = 0.0F;
        for (int i = 0; i < 20; i++) {
            anim = EntityAutomaton.stepSwimAnimation(anim, true);
        }
        assertEquals(1.0F, anim, 1.0E-5F);

        float decremented = EntityAutomaton.stepSwimAnimation(anim, false);
        assertEquals(0.91F, decremented, 1.0E-5F);

        for (int i = 0; i < 20; i++) {
            anim = EntityAutomaton.stepSwimAnimation(anim, false);
        }
        assertEquals(0.0F, anim, 1.0E-5F);
    }
}

