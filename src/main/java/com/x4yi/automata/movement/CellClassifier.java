package com.x4yi.automata.movement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

public final class CellClassifier {

    private static final double PASSABLE_LAYER_MAX_Y = 0.25D;
    private static final int MAX_PURE_STATE_CACHE = 4096;
    private static final Map<IBlockState, Byte> PURE_STATE_CACHE = new ConcurrentHashMap<>(512);

    private CellClassifier() {}

    public static void clearCache() {
        PURE_STATE_CACHE.clear();
    }

    public static byte classify(World world, BlockPos pos, IBlockState state) {
        Material mat = state.getMaterial();
        if (mat == Material.AIR) {
            return BlockCellType.AIR;
        }
        if (mat == Material.LAVA || mat == Material.FIRE) {
            return BlockCellType.HAZARD;
        }
        if (mat == Material.WATER) {
            return BlockCellType.WATER;
        }
        if (isDoorOrGate(state) && mat != Material.IRON) {
            net.minecraft.block.properties.PropertyBool openProp =
                    state.getBlock() instanceof BlockDoor ? BlockDoor.OPEN : BlockFenceGate.OPEN;
            if (Boolean.TRUE.equals(state.getValue(openProp))) {
                return BlockCellType.AIR;
            }
            return BlockCellType.DOOR_CLOSED;
        }
        if (state.isFullCube() && state.isOpaqueCube() && !state.getBlock().hasTileEntity(state)) {
            Byte cached = PURE_STATE_CACHE.get(state);
            if (cached != null) {
                return cached;
            }
            byte computed = classifyUncached(world, pos, state);
            if (PURE_STATE_CACHE.size() < MAX_PURE_STATE_CACHE) {
                PURE_STATE_CACHE.putIfAbsent(state, computed);
            }
            return computed;
        }
        return classifyUncached(world, pos, state);
    }

    private static byte classifyUncached(World world, BlockPos pos, IBlockState state) {
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

