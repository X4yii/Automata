/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.client;

import javax.annotation.Nullable;

import org.lwjgl.input.Keyboard;

import com.x4yi.x4ui.client.gui.input.KeyCodes;
import com.x4yi.x4ui.client.gui.input.UIInput;
import com.x4yi.x4ui.common.network.UINetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;

import com.x4yi.automata.AutomataMod;

/**
 * Handles interactive in-world Point A -> Point B region selection triggered from the automaton GUI.
 */
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID, value = Side.CLIENT)
public final class RegionSelectionController {

    private static int activeEntityId = -1;
    private static boolean selectingB = false;
    @Nullable
    private static BlockPos pointA;
    @Nullable
    private static BlockPos pointB;

    private RegionSelectionController() {}

    public static void startSession(int entityId, @Nullable BlockPos initialA, @Nullable BlockPos initialB) {
        activeEntityId = entityId;
        selectingB = false;
        pointA = initialA;
        pointB = initialB;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null) {
            mc.displayGuiScreen(null);
        }
        sendStatus("Right-Click block for Point A, then press ENTER to confirm");
    }

    public static void cancelSession() {
        if (activeEntityId != -1) {
            activeEntityId = -1;
            selectingB = false;
            sendStatus("Zone selection cancelled");
        }
    }

    public static boolean isActive() {
        return activeEntityId != -1;
    }

    public static boolean isSelectingB() {
        return selectingB;
    }

    @Nullable
    public static BlockPos getPointA() {
        return pointA;
    }

    @Nullable
    public static BlockPos getPointB() {
        return pointB;
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (activeEntityId == -1 || !event.getWorld().isRemote || event.getHand() != EnumHand.MAIN_HAND) {
            return;
        }
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null || !player.equals(event.getEntityPlayer())) {
            return;
        }
        event.setCanceled(true);
        BlockPos pos = event.getPos().toImmutable();
        if (!selectingB) {
            pointA = pos;
            sendStatus(String.format("Point A: %d, %d, %d — Press ENTER to confirm", pos.getX(), pos.getY(), pos.getZ()));
        } else {
            pointB = pos;
            sendStatus(String.format("Point B: %d, %d, %d — Press ENTER to apply", pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (activeEntityId != -1 && event.getWorld().isRemote && UIInput.isShiftDown()) {
            event.setCanceled(true);
            cancelSession();
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        if (activeEntityId == -1 || Minecraft.getMinecraft().currentScreen != null) {
            return;
        }
        if (!Keyboard.getEventKeyState()) {
            return;
        }
        int key = Keyboard.getEventKey();
        if (key == KeyCodes.ESCAPE) {
            cancelSession();
            return;
        }
        if (key == KeyCodes.ENTER || key == KeyCodes.NUMPAD_ENTER) {
            if (!selectingB) {
                if (pointA == null) {
                    sendStatus("Right-Click a block to pick Point A first");
                    return;
                }
                selectingB = true;
                sendStatus("Point A locked! Right-Click block for Point B, then press ENTER");
            } else {
                if (pointB == null) {
                    sendStatus("Right-Click a block to pick Point B first");
                    return;
                }
                commitSelection();
            }
        }
    }

    private static void commitSelection() {
        if (pointA == null || pointB == null) {
            return;
        }
        int entityId = activeEntityId;
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("entityId", entityId);
        tag.setLong("pos1", pointA.toLong());
        tag.setLong("pos2", pointB.toLong());
        RegionSelectionRenderer.setLastConfiguredZone(entityId, pointA, pointB);
        activeEntityId = -1;
        selectingB = false;
        UINetwork.sendActionToServer("automata:set_zone_ab", tag);
    }

    private static void sendStatus(String msg) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player != null) {
            player.sendStatusMessage(new TextComponentString("[Automata] " + msg), true);
        }
    }
}

