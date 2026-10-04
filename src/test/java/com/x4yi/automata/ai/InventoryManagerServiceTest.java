package com.x4yi.automata.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

class InventoryManagerServiceTest {

    @Test
    void lootFlagsDefaultToTrueAndRoundTripInNbt() {
        AutomatonTaskConfig cfg = new AutomatonTaskConfig();
        assertTrue(cfg.isCanPickUpLoot());
        assertTrue(cfg.isSearchLoot());

        cfg.setCanPickUpLoot(false);
        cfg.setSearchLoot(false);
        NBTTagCompound tag = cfg.serializeNBT();

        AutomatonTaskConfig restored = new AutomatonTaskConfig();
        restored.deserializeNBT(tag);
        assertFalse(restored.isCanPickUpLoot());
        assertFalse(restored.isSearchLoot());
    }

    @Test
    void searchesLootOnlyWhenSafeAndOutOfCombat() {
        assertTrue(InventoryManagerService.isSafeToSeekLoot(true, true, false, false, 0.9F, 0, false, false));
        assertFalse(InventoryManagerService.isSafeToSeekLoot(false, true, false, false, 0.9F, 0, false, false));
        assertFalse(InventoryManagerService.isSafeToSeekLoot(true, false, false, false, 0.9F, 0, false, false));
        assertFalse(InventoryManagerService.isSafeToSeekLoot(true, true, true, false, 0.9F, 0, false, false));
        assertFalse(InventoryManagerService.isSafeToSeekLoot(true, true, false, true, 0.9F, 0, false, false));
        assertFalse(InventoryManagerService.isSafeToSeekLoot(true, true, false, false, 0.4F, 0, false, false));
        assertFalse(InventoryManagerService.isSafeToSeekLoot(true, true, false, false, 0.9F, 2, false, false));
        assertFalse(InventoryManagerService.isSafeToSeekLoot(true, true, false, false, 0.9F, 0, true, false));
        assertFalse(InventoryManagerService.isSafeToSeekLoot(true, true, false, false, 0.9F, 0, false, true));
    }
}

