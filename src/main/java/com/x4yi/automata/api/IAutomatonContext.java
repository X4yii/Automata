package com.x4yi.automata.api;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.PathfindingSettings;

/**
 * Public context exposed to custom and built-in automaton jobs.
 */
public interface IAutomatonContext {

    EntityAutomaton getAutomaton();

    WorldServer getWorld();

    AutomatonTaskConfig getTaskConfig();

    long getWorldTime();

    @Nullable
    BlockPos getActiveGoal();

    void requestPath(BlockPos goal, PathfindingSettings settings, boolean allowCooldownBypass);

    void cancelMovement();

    boolean isGoalOnCooldown(BlockPos goal, long now);

    void setStatusMessage(String status);

    String getStatusMessage();

    IItemHandlerModifiable getCombinedInventory();

    boolean tryReserveBlock(BlockPos pos, long ttlTicks);

    void releaseBlock(BlockPos pos);

    boolean isBlockReservedByOther(BlockPos pos);

    boolean stepBreakBlock(BlockPos pos);

    void resetMining();

    boolean isWithinReach(BlockPos pos);

    long consumeStuckGoal();

    void setUrgentMovement(boolean urgent);
}
