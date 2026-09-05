
package com.x4yi.automata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SanityTest {

    @Test
    void modernJavaRuntimeAvailable() {
        assertTrue(Runtime.version().feature() >= 21);
    }
}

