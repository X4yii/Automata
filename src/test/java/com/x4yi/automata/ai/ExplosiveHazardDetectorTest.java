package com.x4yi.automata.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

class ExplosiveHazardDetectorTest {

    @Test
    void detectsModdedExplosiveRegistryIds() {
        assertTrue(ExplosiveHazardDetector.isModdedExplosiveId(new ResourceLocation("modded", "primed_c4_charge")));
        assertTrue(ExplosiveHazardDetector.isModdedExplosiveId(new ResourceLocation("srparasites", "bomb_spore")));
        assertTrue(ExplosiveHazardDetector.isModdedExplosiveId(new ResourceLocation("tech", "nuke_core")));
        assertTrue(ExplosiveHazardDetector.isModdedExplosiveId(new ResourceLocation("tinkers", "dynamite_stick")));

        assertFalse(ExplosiveHazardDetector.isModdedExplosiveId(new ResourceLocation("minecraft", "cow")));
        assertFalse(ExplosiveHazardDetector.isModdedExplosiveId(null));
    }

    @Test
    void detectsModdedConsumableRegistryIdsForHealAndCure() {
        assertTrue(EquipmentSelector.isModdedConsumableId(
                new ResourceLocation("modded", "greater_healing_elixir"), true, false));
        assertTrue(EquipmentSelector.isModdedConsumableId(
                new ResourceLocation("modded", "field_medkit"), true, false));
        assertFalse(EquipmentSelector.isModdedConsumableId(
                new ResourceLocation("modded", "field_medkit"), false, true));

        assertTrue(EquipmentSelector.isModdedConsumableId(
                new ResourceLocation("modded", "universal_antidote"), false, true));
        assertTrue(EquipmentSelector.isModdedConsumableId(
                new ResourceLocation("modded", "coth_cure_potion"), false, true));
        assertFalse(EquipmentSelector.isModdedConsumableId(
                new ResourceLocation("minecraft", "apple"), true, true));
    }
}

