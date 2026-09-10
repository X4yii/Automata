package com.x4yi.automata.entity;

import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityAutomatonAttackTest {

    @Test
    void computesExpectedCooldownTicksForVanillaAndModdedSpeeds() {
        assertEquals(5.0D, EntityAutomaton.computeAttackCooldownTicks(4.0D), 0.001D);
        assertEquals(12.5D, EntityAutomaton.computeAttackCooldownTicks(1.6D), 0.001D);
        assertEquals(25.0D, EntityAutomaton.computeAttackCooldownTicks(0.8D), 0.001D);
        assertEquals(200.0D, EntityAutomaton.computeAttackCooldownTicks(0.0D), 0.001D);
        assertTrue(Double.isFinite(EntityAutomaton.computeAttackCooldownTicks(-5.0D)));
    }

    @Test
    void emptyStackEatDurationIsZero() {
        assertEquals(0, EntityAutomaton.computeEatDurationTicks(ItemStack.EMPTY));
    }
}

