package com.x4yi.automata.ai.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;

class ModdedOreCatalogTest {

    @AfterEach
    void resetCatalog() {
        ModdedOreCatalog.resetDefaultsForTest();
    }

    @Test
    void supportsConfigPatternsMetaAndBlacklist(@TempDir Path tempDir) {
        ModdedOreCatalog.init(tempDir.toFile());
        assertTrue(tempDir.resolve("automata_ores.cfg").toFile().isFile());
        assertTrue(ModdedOreCatalog.isKnownModdedOreId("thermalfoundation:ore"));

        ModdedOreCatalog.applyRulesForTest(
                new String[] {"custom:mineral@3"},
                new String[] {"*:*_ore"},
                new String[] {"custom:fake_ore", "custom:mineral@2"});
        assertTrue(ModdedOreCatalog.isKnownModdedOreId("custom:mineral", 3));
        assertFalse(ModdedOreCatalog.isKnownModdedOreId("custom:mineral", 2));
        assertTrue(ModdedOreCatalog.isKnownModdedOreId("some_mod:mythril_ore", 0));
        assertFalse(ModdedOreCatalog.isKnownModdedOreId("custom:fake_ore", 0));
    }

    @Test
    void recognizesKnownModdedOresAndRejectsNonOres() {
        assertTrue(ModdedOreCatalog.isKnownModdedOreId("thermalfoundation:ore"));
        assertTrue(ModdedOreCatalog.isKnownModdedOreId("mekanism:oreblock"));
        assertTrue(ModdedOreCatalog.isKnownModdedOreId("appliedenergistics2:quartz_ore"));
        assertTrue(ModdedOreCatalog.isKnownModdedOreId("draconicevolution:draconium_ore"));

        assertFalse(ModdedOreCatalog.isKnownModdedOreId("minecraft:stone"));
        assertFalse(ModdedOreCatalog.isKnownModdedOreId("minecraft:cobblestone"));
        assertFalse(ModdedOreCatalog.isKnownModdedOreId("thermalfoundation:storage"));
    }

    @Test
    void mineJobIsRegisteredInBuiltInRegistry() {
        assertNotNull(AutomataAPI.getJob(AutomatonBuiltInJobs.MINE));
        assertTrue(MineJob.isAdjacentInVein(new BlockPos(10, 64, 10), new BlockPos(11, 65, 9)));
        assertFalse(MineJob.isAdjacentInVein(new BlockPos(10, 64, 10), new BlockPos(10, 64, 10)));
        assertFalse(MineJob.isAdjacentInVein(new BlockPos(10, 64, 10), new BlockPos(12, 64, 10)));

        JobBlockSupport.AreaBounds clampedMin = MineJob.resolveMineBounds(new BlockPos(0, 10, 0), 1);
        assertEquals(-4, clampedMin.minX());
        assertEquals(4, clampedMin.maxX());

        JobBlockSupport.AreaBounds clampedMax = MineJob.resolveMineBounds(new BlockPos(0, 10, 0), 120);
        assertEquals(-48, clampedMax.minX());
        assertEquals(48, clampedMax.maxX());

        assertTrue(MineJob.shouldFollowOwnerFirst(true, 150.0D, false));
        assertFalse(MineJob.shouldFollowOwnerFirst(true, 150.0D, true));
        assertFalse(MineJob.shouldFollowOwnerFirst(true, 50.0D, false));
        assertTrue(MineJob.shouldKeepVeinAliveOnStall(0));
        assertTrue(MineJob.shouldKeepVeinAliveOnStall(11));
        assertFalse(MineJob.shouldKeepVeinAliveOnStall(12));
        assertEquals("Hey, no me limites el terreno, no encontraré linduras como tu", MineJob.REJECT_ZONE_MESSAGE);
    }
}

