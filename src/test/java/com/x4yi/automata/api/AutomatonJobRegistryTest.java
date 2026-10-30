package com.x4yi.automata.api;

import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.movement.HumanMovementController.MovementMood;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomatonJobRegistryTest {

    @Test
    void registersAndPreservesOrder() {
        AutomatonJobRegistry registry = new AutomatonJobRegistry();
        ResourceLocation idA = new ResourceLocation("addon", "alpha");
        ResourceLocation idB = new ResourceLocation("addon", "beta");

        registry.register(JobDescriptor.builder(idA, DummyJob::new)
                .titleKey("Alpha")
                .movementMood(MovementMood.CHILL)
                .build());
        registry.register(JobDescriptor.builder(idB, DummyJob::new)
                .titleKey("Beta")
                .movementMood(MovementMood.TRYHARD)
                .logisticsEligible(false)
                .build());

        assertEquals(2, registry.size());
        assertEquals(0, registry.indexOf(idA));
        assertEquals(1, registry.indexOf(idB));
        assertEquals(idB, registry.getByIndex(1).getId());
        assertEquals(MovementMood.TRYHARD, registry.get(idB).getMovementMood());
        assertTrue(registry.get(idA).isLogisticsEligible());
    }

    @Test
    void rejectsDuplicateJobId() {
        AutomatonJobRegistry registry = new AutomatonJobRegistry();
        ResourceLocation id = new ResourceLocation("addon", "dup");
        JobDescriptor desc = JobDescriptor.builder(id, DummyJob::new).build();
        registry.register(desc);
        assertThrows(IllegalArgumentException.class, () -> registry.register(desc));
    }

    @Test
    void builtInsAreRegisteredInCanonicalOrder() {
        List<JobDescriptor> all = AutomataAPI.getAllJobs();
        assertTrue(all.size() >= 7);
        assertEquals(AutomatonBuiltInJobs.IDLE, all.get(0).getId());
        assertEquals(AutomatonBuiltInJobs.EXCAVATION, all.get(1).getId());
        assertNotNull(AutomataAPI.getJob(AutomatonBuiltInJobs.FARMER));
        assertTrue(AutomataAPI.getJob(AutomatonBuiltInJobs.DEFEND).isCombat());
        assertEquals(2, AutomataAPI.getJob(AutomatonBuiltInJobs.DEFEND).getDecisionIntervalTicks());
        assertEquals("automata.job.automata.mine.name", AutomataAPI.getJob(AutomatonBuiltInJobs.MINE).getTitleKey());
        assertThrows(IllegalArgumentException.class, () -> AutomataAPI.requireInternalContext(null));
    }

    @Test
    void addonJobCanUsePublicContextAndToolMatcherWithoutBuiltInEnum() {
        AutomatonJobRegistry registry = new AutomatonJobRegistry();
        ResourceLocation customId = new ResourceLocation("myaddon", "custom_miner");
        JobDescriptor descriptor = JobDescriptor.builder(customId, () -> new AddonOnlyJob(customId))
                .titleKey("Custom Miner")
                .toolMatcher(EquipmentSelector::isAxe)
                .build();
        registry.register(descriptor);

        Item moddedAxe = new Item();
        moddedAxe.setHarvestLevel("axe", 2);
        Item moddedHoe = new Item();
        moddedHoe.setHarvestLevel("hoe", 2);

        IAutomatonJob created = registry.get(customId).createJob();
        assertEquals(customId, created.getJobId());
        assertTrue(descriptor.hasToolMatcher());
        assertTrue(descriptor.needsTool(null));
        assertTrue(moddedAxe.getToolClasses(null).contains("axe"));
        assertTrue(moddedHoe.getToolClasses(null).contains("hoe"));
    }

    private static final class DummyJob implements IAutomatonJob {

        @Override
        public ResourceLocation getJobId() {
            return new ResourceLocation("addon", "dummy");
        }

        @Override
        public void tick(IAutomatonContext ctx) {
        }
    }

    private static final class AddonOnlyJob implements IAutomatonJob {

        private final ResourceLocation id;

        AddonOnlyJob(ResourceLocation id) {
            this.id = id;
        }

        @Override
        public ResourceLocation getJobId() {
            return id;
        }

        @Override
        public void tick(IAutomatonContext ctx) {
            ctx.setStatusMessage("Working");
        }
    }
}

