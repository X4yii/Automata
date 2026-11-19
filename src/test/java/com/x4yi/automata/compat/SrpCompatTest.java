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

        String[] whitelistImmune = SrpCompat.updateCothImmuneList(added, true, true);
        assertFalse(Arrays.asList(whitelistImmune).contains(SrpCompat.AUTOMATON_ENTITY_ID));

        String[] whitelistSusceptible = SrpCompat.updateCothImmuneList(base, true, false);
        assertTrue(Arrays.asList(whitelistSusceptible).contains(SrpCompat.AUTOMATON_ENTITY_ID));
    }

    @Test
    void resolvesSrpWeaponKindsAndBookOfVengeance() {
        assertEquals(SrpCompat.SrpWeaponKind.SCYTHE, SrpCompat.resolveSrpWeaponKindByPath("weapon_scythe"));
        assertEquals(SrpCompat.SrpWeaponKind.SCYTHE, SrpCompat.resolveSrpWeaponKindByPath("weapon_scythe_sentient"));
        assertEquals(SrpCompat.SrpWeaponKind.LANCE, SrpCompat.resolveSrpWeaponKindByPath("weapon_lance_sentient"));
        assertEquals(SrpCompat.SrpWeaponKind.CLEAVER, SrpCompat.resolveSrpWeaponKindByPath("weapon_cleaver"));
        assertEquals(SrpCompat.SrpWeaponKind.MAUL, SrpCompat.resolveSrpWeaponKindByPath("weapon_maul_sentient"));
        assertEquals(SrpCompat.SrpWeaponKind.BOW, SrpCompat.resolveSrpWeaponKindByPath("weapon_bow"));
        assertEquals(SrpCompat.SrpWeaponKind.BOOK_OF_VENGEANCE, SrpCompat.resolveSrpWeaponKindByPath("book_of_vengeance"));
        assertEquals(SrpCompat.SrpWeaponKind.BOOK_OF_VENGEANCE, SrpCompat.resolveSrpWeaponKindByPath("the_book_of_vengeance"));
        assertEquals(SrpCompat.SrpWeaponKind.NONE, SrpCompat.resolveSrpWeaponKindByPath("living_core"));
    }
}

