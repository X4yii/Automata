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

        WeaponCombatProfile standard = WeaponCombatProfile.fromStats(0.0D, -2.4D, false, false);
        assertEquals(WeaponCombatProfile.WeaponCombatStyle.STANDARD_MELEE, standard.getStyle());
    }
}

