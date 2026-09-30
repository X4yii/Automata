package com.x4yi.automata.ai;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomatonTaskConfigTest {

    @Test
    void excavationJobAndNoLegacyFlattenAlias() {
        assertEquals(AutomatonJobType.EXCAVATION, AutomatonJobType.fromId("excavation"));
        assertEquals(AutomatonJobType.IDLE, AutomatonJobType.fromId("flatten"));
        assertEquals(AutomatonJobType.IDLE, AutomatonJobType.fromId("unknown"));
    }

    @Test
    void completionBehaviorRoundTripsInNbt() {
        AutomatonTaskConfig cfg = new AutomatonTaskConfig();
        cfg.setJobType(AutomatonJobType.EXCAVATION);
        cfg.setCompletionBehavior(TaskCompletionBehavior.RETURN_AND_STAY);

        NBTTagCompound nbt = cfg.serializeNBT();
        AutomatonTaskConfig restored = new AutomatonTaskConfig();
        restored.deserializeNBT(nbt);

        assertEquals(AutomatonJobType.EXCAVATION, restored.getJobType());
        assertEquals(TaskCompletionBehavior.RETURN_AND_STAY, restored.getCompletionBehavior());

        assertEquals(TaskCompletionBehavior.FOLLOW_OWNER, TaskCompletionBehavior.fromId("invalid"));
    }

    @Test
    void defaultBlacklistAndFilterFlagsRoundTrip() {
        AutomatonTaskConfig cfg = new AutomatonTaskConfig();
        assertTrue(cfg.isIgnoreAnimals());
        assertTrue(cfg.isIgnoreCalmNeutrals());
        assertTrue(cfg.getEntityFilterIds().contains("minecraft:villager"));

        cfg.setIgnoreAnimals(false);
        cfg.getEntityFilterIds().remove("minecraft:villager");
        cfg.getEntityFilterIds().add("minecraft:creeper");

        NBTTagCompound nbt = cfg.serializeNBT();
        AutomatonTaskConfig restored = new AutomatonTaskConfig();
        restored.deserializeNBT(nbt);

        assertFalse(restored.isIgnoreAnimals());
        assertTrue(restored.isIgnoreCalmNeutrals());
        assertFalse(restored.getEntityFilterIds().contains("minecraft:villager"));
        assertTrue(restored.getEntityFilterIds().contains("minecraft:creeper"));
    }
}

