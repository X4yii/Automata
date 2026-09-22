/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.world;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Tracks gradual 20 Hz block breaking progress and client break-crack stages per automaton.
 */
public final class MiningProgressController {

    @Nullable
    private BlockPos activePos;
    private float progress;
    private int lastStage = -1;
    private long lastStepWorldTime = Long.MIN_VALUE;
    private long breakCooldownUntilTick = Long.MIN_VALUE;

    @Nullable
    public BlockPos getActivePos() {
        return activePos;
    }

    public boolean isMining() {
        return activePos != null;
    }

    public boolean stepBreakBlock(WorldServer world, EntityAutomaton automaton, BlockPos pos) {
        if (!world.isBlockLoaded(pos) || world.isAirBlock(pos)) {
            reset(world, automaton);
            return true;
        }
        long now = world.getTotalWorldTime();
        if (now == lastStepWorldTime || now < breakCooldownUntilTick) {
            return false;
        }
        lastStepWorldTime = now;
        IBlockState state = world.getBlockState(pos);
        if (state.getBlockHardness(world, pos) < 0.0F) {
            reset(world, automaton);
            return false;
        }
        if (activePos == null || !activePos.equals(pos)) {
            reset(world, automaton);
            activePos = pos.toImmutable();
            progress = 0.0F;
            lastStage = -1;
        }

        EquipmentSelector.equipBestToolFor(automaton, world, pos, state);
        automaton.getMovementController().lookAtBlockFace(pos);
        automaton.swingArm(EnumHand.MAIN_HAND);
        FakePlayer fake = FakePlayerService.acquireSynced(world, automaton);
        float delta = state.getPlayerRelativeBlockHardness(fake, world, pos);
        if (delta <= 0.0F) {
            return false;
        }
        progress = advanceProgress(progress, delta);
        if (progress >= 1.0F) {
            reset(world, automaton);
            breakCooldownUntilTick = now + 5L;
            return FakePlayerService.tryBreakBlock(world, automaton, pos);
        }
        int stage = computeBreakStage(progress);
        if (stage != lastStage) {
            lastStage = stage;
            world.sendBlockBreakProgress(automaton.getEntityId(), pos, stage);
        }
        return false;
    }

    public void reset(@Nullable WorldServer world, EntityAutomaton automaton) {
        if (activePos != null && world != null) {
            world.sendBlockBreakProgress(automaton.getEntityId(), activePos, -1);
        }
        activePos = null;
        progress = 0.0F;
        lastStage = -1;
    }

    public static float advanceProgress(float current, float delta) {
        if (delta <= 0.0F || Float.isNaN(delta)) {
            return Math.max(0.0F, Math.min(1.0F, current));
        }
        return Math.min(1.0F, Math.max(0.0F, current) + delta);
    }

    public static int computeBreakStage(float progress) {
        if (progress <= 0.0F || Float.isNaN(progress)) {
            return -1;
        }
        if (progress >= 1.0F) {
            return 9;
        }
        return Math.min(9, Math.max(0, (int) (progress * 10.0F)));
    }

    public static boolean shouldDeferMiningInAir(boolean onGround, boolean inWater, boolean onLadder) {
        return !onGround && !inWater && !onLadder;
    }
}

