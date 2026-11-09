package com.x4yi.automata.ai;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomatonTaskConfigTest {

    @Test
    void excavationJobAndCustomNamespaceRoundTrip() {
        AutomatonTaskConfig cfg = new AutomatonTaskConfig();
        cfg.setJobId(com.x4yi.automata.api.AutomatonBuiltInJobs.EXCAVATION);
        assertEquals(com.x4yi.automata.api.AutomatonBuiltInJobs.EXCAVATION, cfg.getJobId());

        net.minecraft.util.ResourceLocation addonJob = new net.minecraft.util.ResourceLocation("addonmod", "mine");
        cfg.setJobId(addonJob);
        assertEquals(addonJob, cfg.getJobId());
    }

    @Test
    void completionBehaviorRoundTripsInNbt() {
        AutomatonTaskConfig cfg = new AutomatonTaskConfig();
        cfg.setJobId(com.x4yi.automata.api.AutomatonBuiltInJobs.EXCAVATION);
        cfg.setCompletionBehavior(TaskCompletionBehavior.RETURN_AND_STAY);

        NBTTagCompound nbt = cfg.serializeNBT();
        AutomatonTaskConfig restored = new AutomatonTaskConfig();
        restored.deserializeNBT(nbt);

        assertEquals(com.x4yi.automata.api.AutomatonBuiltInJobs.EXCAVATION, restored.getJobId());
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

