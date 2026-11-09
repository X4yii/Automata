
package com.x4yi.automata.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        assertNotEquals(
                BudgetedTickScheduler.mixEntityId(2),
                BudgetedTickScheduler.mixEntityId(4));
    }

    @Test
    void scalesIntervalsByServerMsptThresholds() {
        assertEquals(20, BudgetedTickScheduler.scaleInterval(20, 12.0D));
        assertEquals(40, BudgetedTickScheduler.scaleInterval(20, 40.0D));
        assertEquals(60, BudgetedTickScheduler.scaleInterval(20, 55.0D));
        assertEquals(80, BudgetedTickScheduler.scaleInterval(20, 90.0D));
        assertEquals(1, BudgetedTickScheduler.scaleInterval(1, 90.0D));
    }
}

