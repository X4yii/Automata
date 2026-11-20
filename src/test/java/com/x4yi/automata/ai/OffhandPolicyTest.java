package com.x4yi.automata.ai;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.x4yi.automata.ai.OffhandPolicy.DesiredOffhand;
import com.x4yi.automata.world.OwnerCapabilityRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffhandPolicyTest {

    @Test
    void twoHandedWeaponAlwaysClearsOffhand() {
        assertEquals(
                DesiredOffhand.EMPTY_TWO_HANDED,
                OffhandPolicy.evaluateDesired(true, true, true, true, true, true));
        assertEquals(
                DesiredOffhand.EMPTY_TWO_HANDED,
                OffhandPolicy.evaluateDesired(true, false, false, false, true, true));
    }

    @Test
    void combatPrioritizesShieldOverTorchAndKeepsCurrentWhenNoShield() {
        assertEquals(
                DesiredOffhand.SHIELD,
                OffhandPolicy.evaluateDesired(false, true, true, false, true, true));
        assertEquals(
                DesiredOffhand.KEEP_CURRENT,
                OffhandPolicy.evaluateDesired(false, true, false, false, true, true));
    }

    @Test
    void peaceEquipsTorchOnlyWhenOwnerEnabledAndTotemAbsent() {
        assertEquals(
                DesiredOffhand.TORCH,
                OffhandPolicy.evaluateDesired(false, false, true, false, true, true));
        assertEquals(
                DesiredOffhand.KEEP_CURRENT,
                OffhandPolicy.evaluateDesired(false, false, true, true, true, true));
        assertEquals(
                DesiredOffhand.KEEP_CURRENT,
                OffhandPolicy.evaluateDesired(false, false, true, false, false, true));
        assertEquals(
                DesiredOffhand.KEEP_CURRENT,
                OffhandPolicy.evaluateDesired(false, false, true, false, true, false));
    }

    @Test
    void ownerCapabilityRegistryTracksAndClearsDynamicLights() {
        UUID owner = UUID.randomUUID();
        OwnerCapabilityRegistry.clearAll();
        assertFalse(OwnerCapabilityRegistry.hasDynamicLights(owner));

        OwnerCapabilityRegistry.setDynamicLights(owner, true);
        assertTrue(OwnerCapabilityRegistry.hasDynamicLights(owner));

        OwnerCapabilityRegistry.setDynamicLights(owner, false);
        assertFalse(OwnerCapabilityRegistry.hasDynamicLights(owner));

        OwnerCapabilityRegistry.setDynamicLights(owner, true);
        OwnerCapabilityRegistry.clearPlayer(owner);
        assertFalse(OwnerCapabilityRegistry.hasDynamicLights(owner));
    }
}
