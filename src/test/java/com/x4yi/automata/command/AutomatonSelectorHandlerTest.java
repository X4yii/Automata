package com.x4yi.automata.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AutomatonSelectorHandlerTest {

    @Test
    void expandsPlainAndFilteredAtSelectors() {
        assertEquals(
                "@e[type=" + AutomatonSelectorHandler.AUTOMATON_ENTITY_TYPE + "]",
                AutomatonSelectorHandler.expandSelectorToken("@at"));
        assertEquals(
                "@e[type=" + AutomatonSelectorHandler.AUTOMATON_ENTITY_TYPE + "]",
                AutomatonSelectorHandler.expandSelectorToken("@at[]"));
        assertEquals(
                "@e[type=" + AutomatonSelectorHandler.AUTOMATON_ENTITY_TYPE + ",r=20,name=Miner]",
                AutomatonSelectorHandler.expandSelectorToken("@at[r=20,name=Miner]"));
    }

    @Test
    void ignoresVanillaAndUnrelatedTokens() {
        assertNull(AutomatonSelectorHandler.expandSelectorToken("@a"));
        assertNull(AutomatonSelectorHandler.expandSelectorToken("@e"));
        assertNull(AutomatonSelectorHandler.expandSelectorToken("@p"));
        assertNull(AutomatonSelectorHandler.expandSelectorToken("@attacker"));
        assertNull(AutomatonSelectorHandler.expandSelectorToken("foo@at"));
        assertNull(AutomatonSelectorHandler.expandSelectorToken(null));
    }

    @Test
    void rewritesOnlyArgsContainingAtSelector() {
        String[] unchanged = {"@a", "minecraft:speed", "30"};
        assertNull(AutomatonSelectorHandler.rewriteArgs(unchanged));

        String[] withAt = {"@at[r=15]", "@p"};
        assertArrayEquals(
                new String[]{"@e[type=" + AutomatonSelectorHandler.AUTOMATON_ENTITY_TYPE + ",r=15]", "@p"},
                AutomatonSelectorHandler.rewriteArgs(withAt));
    }
}

