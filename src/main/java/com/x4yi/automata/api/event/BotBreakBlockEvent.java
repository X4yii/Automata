package com.x4yi.automata.api.event;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

import com.x4yi.automata.entity.EntityAutomaton;

@Cancelable
public class BotBreakBlockEvent extends Event {

    private final EntityAutomaton automaton;
    private final WorldServer world;
    private final BlockPos pos;
    private final IBlockState state;

    public BotBreakBlockEvent(EntityAutomaton automaton, WorldServer world, BlockPos pos, IBlockState state) {
        this.automaton = automaton;
        this.world = world;
        this.pos = pos;
        this.state = state;
    }

    public EntityAutomaton getAutomaton() {
        return automaton;
    }

    public WorldServer getWorld() {
        return world;
    }

    public BlockPos getPos() {
        return pos;
    }

    public IBlockState getState() {
        return state;
    }
}

