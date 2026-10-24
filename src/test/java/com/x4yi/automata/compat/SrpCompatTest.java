package com.x4yi.automata.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SrpCompatTest {

    @Test
    void updatesNativeCothImmuneListInBlacklistAndWhitelistModes() {
        String[] base = {"minecraft:villager_golem", "minecraft:creeper"};

        String[] added = SrpCompat.updateCothImmuneList(base, false, true);
        assertTrue(Arrays.asList(added).contains(SrpCompat.AUTOMATON_ENTITY_ID));

        String[] removed = SrpCompat.updateCothImmuneList(added, false, false);
        assertFalse(Arrays.asList(removed).contains(SrpCompat.AUTOMATON_ENTITY_ID));
        assertEquals(2, removed.length);

        // Inverted mode (whitelist): immune means NOT in the infectable whitelist.
        String[] whitelistImmune = SrpCompat.updateCothImmuneList(added, true, true);
        assertFalse(Arrays.asList(whitelistImmune).contains(SrpCompat.AUTOMATON_ENTITY_ID));

        String[] whitelistSusceptible = SrpCompat.updateCothImmuneList(base, true, false);
        assertTrue(Arrays.asList(whitelistSusceptible).contains(SrpCompat.AUTOMATON_ENTITY_ID));
    }
}

