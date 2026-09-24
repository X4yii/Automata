
package com.x4yi.automata.world;

import java.util.Random;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.IWorldGenerator;

import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;

public class AutomatonCampGenerator implements IWorldGenerator {

    @Override
    public void generate(
            Random random,
            int chunkX,
            int chunkZ,
            World world,
            IChunkGenerator chunkGenerator,
            IChunkProvider chunkProvider) {
        if (world.provider.getDimension() != 0) {
            return;
        }
        int rarity = AutomataConfig.campSpawnRarity;
        if (rarity <= 0 || random.nextInt(rarity) != 0) {
            return;
        }

        int baseX = (chunkX << 4) + 4 + random.nextInt(8);
        int baseZ = (chunkZ << 4) + 4 + random.nextInt(8);
        BlockPos surface = world.getTopSolidOrLiquidBlock(new BlockPos(baseX, 0, baseZ));
        if (surface.getY() < 58 || surface.getY() > 140) {
            return;
        }
        IBlockState ground = world.getBlockState(surface.down());
        if (!ground.getMaterial().isSolid() || ground.getMaterial().isLiquid()) {
            return;
        }

        buildCamp(world, surface, random);
    }

    private void buildCamp(World world, BlockPos center, Random random) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos floorPos = center.add(dx, -1, dz);
                if (world.isAirBlock(floorPos) || world.getBlockState(floorPos).getMaterial().isReplaceable()) {
                    world.setBlockState(floorPos, Blocks.COBBLESTONE.getDefaultState(), 2);
                }
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockToAir(center.add(dx, dy, dz));
                }
            }
        }

        world.setBlockState(center, Blocks.CRAFTING_TABLE.getDefaultState(), 2);
        BlockPos chestPos = center.east();
        world.setBlockState(chestPos, Blocks.CHEST.getDefaultState(), 2);
        TileEntity te = world.getTileEntity(chestPos);
        if (te instanceof TileEntityChest chest) {
            chest.setInventorySlotContents(0, new ItemStack(Items.BREAD, 2 + random.nextInt(4)));
            chest.setInventorySlotContents(1, new ItemStack(Items.APPLE, 1 + random.nextInt(3)));
            chest.setInventorySlotContents(2, new ItemStack(Items.WOODEN_PICKAXE, 1));
        }

        world.setBlockState(center.add(-2, 0, -2), Blocks.OAK_FENCE.getDefaultState(), 2);
        world.setBlockState(center.add(-2, 1, -2), Blocks.TORCH.getDefaultState(), 2);
        world.setBlockState(center.add(2, 0, 2), Blocks.OAK_FENCE.getDefaultState(), 2);
        world.setBlockState(center.add(2, 1, 2), Blocks.TORCH.getDefaultState(), 2);

        int count = 1 + random.nextInt(2);
        for (int i = 0; i < count; i++) {
            EntityAutomaton bot = new EntityAutomaton(world);
            double spawnX = center.getX() + 0.5D + (i == 0 ? -1.5D : 1.5D);
            double spawnZ = center.getZ() + 0.5D + (i == 0 ? 1.5D : -1.5D);
            bot.setPositionAndRotation(spawnX, center.getY(), spawnZ, random.nextFloat() * 360.0F, 0.0F);
            bot.onInitialSpawn(world.getDifficultyForLocation(center), null);
            world.spawnEntity(bot);
        }
    }
}

