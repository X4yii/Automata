package com.x4yi.automata.movement;

import javax.annotation.Nullable;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.navigation.BlockCellType;

/**
 * Unified block/cell classification shared across world caching, pathfinding, and movement.
 */
public final class CellClassifier {

    private static final double PASSABLE_LAYER_MAX_Y = 0.25D;

    private CellClassifier() {}

    public static byte classify(World world, BlockPos pos, IBlockState state) {
        Material mat = state.getMaterial();
        if (mat == Material.LAVA || mat == Material.FIRE) {
            return BlockCellType.HAZARD;
        }
        if (mat == Material.WATER) {
            return BlockCellType.WATER;
        }
        if (mat == Material.AIR) {
            return BlockCellType.AIR;
        }
        byte base = classifyCollisionBox(state.getCollisionBoundingBox(world, pos));
        if (base == BlockCellType.SOLID
                && (state.getBlockHardness(world, pos) < 0.0F
                        || AutomataConfig.isBlockBlacklisted(state.getBlock()))) {
            return BlockCellType.UNBREAKABLE;
        }
        return base;
    }

    public static byte classifyCollisionBox(@Nullable AxisAlignedBB box) {
        if (box == null || box.maxY <= PASSABLE_LAYER_MAX_Y || box.maxX - box.minX <= 0.0D || box.maxZ - box.minZ <= 0.0D) {
            return BlockCellType.AIR;
        }
        if (box.maxY > 1.0D) {
            return BlockCellType.TALL_OBSTACLE;
        }
        return BlockCellType.SOLID;
    }

    public static boolean isSolidSupport(World world, BlockPos pos) {
        if (!world.isBlockLoaded(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        byte cell = classify(world, pos, state);
        return cell == BlockCellType.SOLID || cell == BlockCellType.UNBREAKABLE;
    }

    public static boolean isPassable(World world, BlockPos pos) {
        if (!world.isBlockLoaded(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        byte cell = classify(world, pos, state);
        return cell == BlockCellType.AIR || cell == BlockCellType.WATER;
    }

    public static boolean isHazard(IBlockState state) {
        Material mat = state.getMaterial();
        return mat == Material.LAVA || mat == Material.FIRE;
    }

    public static boolean isDoorOrGate(IBlockState state) {
        return state.getBlock() instanceof BlockDoor || state.getBlock() instanceof BlockFenceGate;
    }
}

