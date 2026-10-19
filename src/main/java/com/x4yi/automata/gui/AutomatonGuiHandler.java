/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.gui;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Forge {@link IGuiHandler} for opening {@link ContainerAutomaton} and the client X4UI screen
 * without exposing client-only classes to dedicated servers.
 */
public class AutomatonGuiHandler implements IGuiHandler {

    public static final int GUI_AUTOMATON = 1;

    @Nullable
    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != GUI_AUTOMATON) {
            return null;
        }
        Entity entity = world.getEntityByID(x);
        if (entity instanceof EntityAutomaton bot && (bot.isOwnedBy(player) || player.canUseCommand(2, ""))) {
            return new ContainerAutomaton(bot, player);
        }
        return null;
    }

    @Nullable
    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != GUI_AUTOMATON || !world.isRemote) {
            return null;
        }
        Entity entity = world.getEntityByID(x);
        if (!(entity instanceof EntityAutomaton bot)) {
            return null;
        }
        try {
            Class<?> screenClass = Class.forName("com.x4yi.automata.client.gui.GuiAutomatonScreen");
            return screenClass
                    .getConstructor(ContainerAutomaton.class)
                    .newInstance(new ContainerAutomaton(bot, player));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to construct GuiAutomatonScreen", e);
        }
    }
}

