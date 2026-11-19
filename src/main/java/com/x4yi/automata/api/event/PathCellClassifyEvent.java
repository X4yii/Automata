package com.x4yi.automata.api.event;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.Event;

public class PathCellClassifyEvent extends Event {

    private final World world;
    private final BlockPos pos;
    private final IBlockState state;
    private byte cellType;

    public PathCellClassifyEvent(World world, BlockPos pos, IBlockState state, byte cellType) {
        this.world = world;
        this.pos = pos;
        this.state = state;
        this.cellType = cellType;
    }

    public World getWorld() {
        return world;
    }

    public BlockPos getPos() {
        return pos;
    }

    public IBlockState getState() {
        return state;
    }

    public byte getCellType() {
        return cellType;
    }

    public void setCellType(byte cellType) {
        this.cellType = cellType;
    }
}

