package com.x4yi.automata.ai.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WeaponCombatProfileTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void resolvesVanillaBowWithFull20TickCharge() {
        WeaponCombatProfile bowProfile = WeaponCombatProfile.resolve(new ItemStack(Items.BOW));
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.BOW_MARKSMAN, bowProfile.getStyle());
        assertEquals(20L, bowProfile.getRangedChargeTicks());
    }

    @Test
    void classifiesWeaponStylesFromStatsWithoutHardcodedIds() {
        WeaponCombatProfile polearm = WeaponCombatProfile.fromStats(1.5D, -2.6D, false, false);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.POLEARM_REACH, polearm.getStyle());
        assertEquals(4.5D, polearm.getAttackRange(), 1.0E-5D);
        assertTrue(polearm.getIdealMinRangeSq() > 4.0D);

        WeaponCombatProfile duelist = WeaponCombatProfile.fromStats(0.0D, -1.8D, false, true);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.DUELIST_AGGRESSIVE, duelist.getStyle());
        assertTrue(duelist.prefersSprintHits());
        assertFalse(duelist.prefersCrits());

        WeaponCombatProfile heavy = WeaponCombatProfile.fromStats(0.0D, -3.1D, true, false);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.HEAVY_BREAKER, heavy.getStyle());
        assertTrue(heavy.prefersCrits());
        assertFalse(heavy.prefersSprintHits());

        WeaponCombatProfile reachOne = WeaponCombatProfile.fromStats(1.0D, -2.5D, false, false);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.POLEARM_REACH, reachOne.getStyle());
        assertEquals(3.25D * 3.25D, reachOne.getIdealMinRangeSq(), 1.0E-4D);

        WeaponCombatProfile reachTwo = WeaponCombatProfile.fromStats(2.0D, -2.5D, false, false);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.POLEARM_REACH, reachTwo.getStyle());
        assertEquals(4.25D * 4.25D, reachTwo.getIdealMinRangeSq(), 1.0E-4D);

        WeaponCombatProfile standard = WeaponCombatProfile.fromStats(0.0D, -2.4D, false, false);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.STANDARD_MELEE, standard.getStyle());
        assertFalse(standard.prefersSprintHits());

        WeaponCombatProfile caestus = WeaponCombatProfile.fromStats(0.0D, -0.5D, false, false, true);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.RAPID_BRAWLER, caestus.getStyle());
        assertTrue(caestus.prefersSprintHits());
        assertFalse(caestus.prefersCrits());

        WeaponCombatProfile fastSpamBySpeed = WeaponCombatProfile.fromStats(0.0D, -0.8D, false, false, false);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.RAPID_BRAWLER, fastSpamBySpeed.getStyle());
    }

    @Test
    void mapsSrpWeaponKindsToDistinctPlaystyles() {
        WeaponCombatProfile scythe = WeaponCombatProfile.fromSrpKind(
                com.x4yi.automata.compat.SrpCompat.SrpWeaponKind.SCYTHE, 1.5D, -2.6D);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.POLEARM_REACH, scythe.getStyle());
        assertFalse(scythe.prefersSprintHits());
        assertEquals(4.5D, scythe.getAttackRange(), 1.0E-5D);

        WeaponCombatProfile lance = WeaponCombatProfile.fromSrpKind(
                com.x4yi.automata.compat.SrpCompat.SrpWeaponKind.LANCE, 2.0D, -2.5D);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.POLEARM_REACH, lance.getStyle());
        assertTrue(lance.prefersSprintHits());

        WeaponCombatProfile cleaver = WeaponCombatProfile.fromSrpKind(
                com.x4yi.automata.compat.SrpCompat.SrpWeaponKind.CLEAVER, 0.0D, -2.9D);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.HEAVY_BREAKER, cleaver.getStyle());
        assertTrue(cleaver.prefersCrits());

        WeaponCombatProfile book = WeaponCombatProfile.fromSrpKind(
                com.x4yi.automata.compat.SrpCompat.SrpWeaponKind.BOOK_OF_VENGEANCE, 0.0D, 0.0D);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.VENGEANCE_CASTER, book.getStyle());
        assertEquals(32.0D, book.getAttackRange(), 1.0E-5D);
    }
}

