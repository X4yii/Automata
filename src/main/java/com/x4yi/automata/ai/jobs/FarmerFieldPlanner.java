package com.x4yi.automata.ai.jobs;

import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockNetherWart;
import net.minecraft.block.BlockStem;
import net.minecraft.block.IGrowable;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.world.FakePlayerService;

final class FarmerFieldPlanner {

    private FarmerFieldPlanner() {}

    static boolean tryRightClickHarvestModdedCrop(AutomatonAIContext ctx, WorldServer world, EntityAutomaton bot, BlockPos cropPos) {
        IBlockState before = world.getBlockState(cropPos);
        Block block = before.getBlock();
        if (block instanceof BlockCrops || !(block instanceof IGrowable)) {
            return false;
        }
        ctx.getMovement().lookAtBlockFace(cropPos, EnumFacing.UP);
        EnumActionResult res = FakePlayerService.tryUseItemOnBlock(
                world, bot, EnumHand.MAIN_HAND, cropPos, EnumFacing.UP, 0.5F, 0.5F, 0.5F);
        if (res != EnumActionResult.SUCCESS) {
            return false;
        }
        IBlockState after = world.getBlockState(cropPos);
        return !isMatureCrop(world, cropPos, after);
    }

    static boolean isPlantableSoil(WorldServer world, BlockPos soil) {
        Block block = world.getBlockState(soil).getBlock();
        return block == Blocks.FARMLAND || block == Blocks.SOUL_SAND;
    }

    static boolean isClearableFoliage(WorldServer world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        return !world.isAirBlock(pos)
                && state.getMaterial().isReplaceable()
                && state.getMaterial() != Material.WATER
                && state.getMaterial() != Material.LAVA
                && state.getCollisionBoundingBox(world, pos) == null;
    }

    static boolean hasNearbyWater(WorldServer world, BlockPos center) {
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    check.setPos(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (world.isBlockLoaded(check) && world.getBlockState(check).getMaterial() == Material.WATER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean isMatureCrop(WorldServer world, BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        if (block instanceof BlockCrops crops) {
            return crops.isMaxAge(state);
        }
        if (block instanceof BlockNetherWart) {
            return state.getValue(BlockNetherWart.AGE) >= 3;
        }
        if (block == Blocks.REEDS) {
            return world.getBlockState(pos.down()).getBlock() == Blocks.REEDS;
        }
        if (block == Blocks.PUMPKIN || block == Blocks.MELON_BLOCK) {
            return hasAdjacentStem(world, pos);
        }
        if (block instanceof IGrowable growable && block != Blocks.GRASS && block != Blocks.TALLGRASS && block != Blocks.DOUBLE_PLANT) {
            return !growable.canGrow(world, pos, state, false);
        }
        return false;
    }

    private static boolean hasAdjacentStem(WorldServer world, BlockPos pos) {
        for (EnumFacing face : EnumFacing.HORIZONTALS) {
            BlockPos adj = pos.offset(face);
            if (world.isBlockLoaded(adj) && world.getBlockState(adj).getBlock() instanceof BlockStem) {
                return true;
            }
        }
        return false;
    }
}

