
package com.x4yi.automata.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BudgetedTickSchedulerTest {

    @Test
    void distributesTicksEvenlyAcrossEntities() {
        int interval = 5;
        int runsForBot1 = 0;
        int runsForBot2 = 0;

        for (long t = 0; t < 100; t++) {
            if (BudgetedTickScheduler.shouldRun(t, 1, interval)) {
                runsForBot1++;
            }
            if (BudgetedTickScheduler.shouldRun(t, 2, interval)) {
                runsForBot2++;
            }
        }

        assertEquals(20, runsForBot1);
        assertEquals(20, runsForBot2);
    }
}

