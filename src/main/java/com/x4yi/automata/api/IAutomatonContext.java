package com.x4yi.automata.api;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.ai.BotLoadoutProfile;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.movement.MovementPace;
import com.x4yi.automata.navigation.PathfindingSettings;

public interface IAutomatonContext {

    EntityAutomaton getAutomaton();

    WorldServer getWorld();

    AutomatonTaskConfig getTaskConfig();

    long getWorldTime();

    @Nullable
    BlockPos getActiveGoal();

    void requestPath(BlockPos goal, PathfindingSettings settings, boolean allowCooldownBypass);

    void cancelMovement();

    void pauseMovement();

    MovementPace getMovementPace();

    void setMovementPace(MovementPace pace);

    BotLoadoutProfile getLoadoutProfile();

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
