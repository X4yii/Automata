package com.x4yi.automata.command;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.x4yi.automata.entity.EntityAutomaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomatonTeleportHandlerTest {

    @Test
    void requiresLeadingDotPrefixForBotLookup() {
        assertEquals("jesi", AutomatonTeleportHandler.parseDotBotName(".jesi"));
        assertEquals("Bot_01", AutomatonTeleportHandler.parseDotBotName(".Bot_01"));
        assertNull(AutomatonTeleportHandler.parseDotBotName("jesi"));
        assertNull(AutomatonTeleportHandler.parseDotBotName("."));
        assertNull(AutomatonTeleportHandler.parseDotBotName(""));
        assertNull(AutomatonTeleportHandler.parseDotBotName(null));
    }

    @Test
    void detectsDotTokensInCommandArguments() {
        assertTrue(AutomatonTeleportHandler.hasDotBotToken(new String[] {".jesi"}));
        assertTrue(AutomatonTeleportHandler.hasDotBotToken(new String[] {"Steve", ".jesi"}));
        assertFalse(AutomatonTeleportHandler.hasDotBotToken(new String[] {"Steve", "Alex"}));
    }

    @Test
    void normalizesAutomatonNameTrimmingLeadingDotsAndLength() {
        assertEquals("jesi", EntityAutomaton.normalizeAutomatonName(" .jesi "));
        assertEquals("", EntityAutomaton.normalizeAutomatonName("   "));
        assertEquals("12345678901234567890123456789012",
                EntityAutomaton.normalizeAutomatonName("123456789012345678901234567890123456"));
    }

    @Test
    void filtersDotBotTabSuggestionsForFirstAndSecondTeleportArgs() {
        List<String> bots = Arrays.asList("jesi", "alex_bot", "miner");
        assertEquals(Arrays.asList(".jesi"),
                AutomatonTeleportHandler.filterDotBotSuggestions(bots, new String[] {".je"}));
        assertEquals(Arrays.asList(".jesi", ".alex_bot", ".miner"),
                AutomatonTeleportHandler.filterDotBotSuggestions(bots, new String[] {"."}));
        assertEquals(Arrays.asList(".miner"),
                AutomatonTeleportHandler.filterDotBotSuggestions(bots, new String[] {"Steve", ".mi"}));
        assertTrue(AutomatonTeleportHandler.filterDotBotSuggestions(bots, new String[] {"Steve", "10", "64"}).isEmpty());
    }
}
