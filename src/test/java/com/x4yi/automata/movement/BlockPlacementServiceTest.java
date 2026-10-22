package com.x4yi.automata.movement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockPlacementServiceTest {

    @BeforeAll
    static void initBootstrap() {
        Bootstrap.register();
    }

    @Test
    void rejectsUnsafeBlocksAndAcceptsSolidScaffoldBlocks() {
        assertTrue(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.COBBLESTONE)));
        assertTrue(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.DIRT)));
        assertTrue(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.PLANKS)));

        assertFalse(BlockPlacementService.isValidScaffoldBlock(ItemStack.EMPTY));
        assertFalse(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.SAND)));
        assertFalse(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.GRAVEL)));
        assertFalse(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.TORCH)));
        assertFalse(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.SAPLING)));
        assertFalse(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.CHEST)));
        assertFalse(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.STONE_SLAB)));
        assertFalse(BlockPlacementService.isValidScaffoldBlock(new ItemStack(Blocks.BEDROCK)));
    }
}

