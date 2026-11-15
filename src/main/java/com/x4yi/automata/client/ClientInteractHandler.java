/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.client;

import java.util.List;

import javax.annotation.Nullable;

import com.x4yi.x4ui.common.network.UINetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.client.gui.AutomaStaffHudOverlay;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.item.AutomaStaffMode;
import com.x4yi.automata.item.ItemAutomaStaff;

/**
 * Enables opening an automaton GUI via right-click raycast up to 10 blocks away.
 */
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID, value = Side.CLIENT)
public final class ClientInteractHandler {

    private static final double REMOTE_RAYCAST_RANGE = 10.0D;

    private ClientInteractHandler() {}

    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        if (event.getHand() == EnumHand.MAIN_HAND) {
            tryOpenRemoteAutomatonGui(event.getEntityPlayer());
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntityPlayer().world.isRemote && event.getHand() == EnumHand.MAIN_HAND) {
            tryOpenRemoteAutomatonGui(event.getEntityPlayer());
        }
    }

    @SubscribeEvent
    public static void onMouseWheel(MouseEvent event) {
        if (event.getDwheel() == 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if (mc.currentScreen != null || player == null || !player.isSneaking()) {
            return;
        }
        ItemStack held = player.getHeldItemMainhand();
        if (!held.isEmpty() && held.getItem() instanceof ItemAutomaStaff) {
            event.setCanceled(true);
            AutomaStaffHudOverlay.cycleMode(event.getDwheel() > 0 ? -1 : 1);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        if (!player.world.isRemote) {
            return;
        }
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !(held.getItem() instanceof ItemAutomaStaff)) {
            return;
        }
        if (event.getTarget() instanceof EntityAutomaton bot) {
            event.setCanceled(true);
            dispatchStaffEntityClick(bot);
        }
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (event.getHand() != EnumHand.MAIN_HAND) {
            return;
        }
        EntityPlayer player = event.getEntityPlayer();
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !(held.getItem() instanceof ItemAutomaStaff)) {
            return;
        }
        EntityAutomaton target = raycastAutomaton(player, REMOTE_RAYCAST_RANGE);
        if (target != null) {
            dispatchStaffEntityClick(target);
            return;
        }
        if (AutomaStaffHudOverlay.getActiveMode() == AutomaStaffMode.SELECT_ZONE || ItemAutomaStaff.isMinerTargetMode(held)) {
            Vec3d eyes = player.getPositionEyes(1.0F);
            Vec3d look = player.getLook(1.0F);
            Vec3d end = eyes.add(look.x * REMOTE_RAYCAST_RANGE, look.y * REMOTE_RAYCAST_RANGE, look.z * REMOTE_RAYCAST_RANGE);
            RayTraceResult blockHit = player.world.rayTraceBlocks(eyes, end, false, true, false);
            if (blockHit != null && blockHit.typeOfHit == RayTraceResult.Type.BLOCK) {
                sendStaffLeftClickBlock(blockHit.getBlockPos().toLong(), player.isSneaking(), AutomaStaffHudOverlay.getActiveMode());
            }
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getWorld().isRemote || event.getHand() != EnumHand.MAIN_HAND) {
            return;
        }
        EntityPlayer player = event.getEntityPlayer();
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !(held.getItem() instanceof ItemAutomaStaff)) {
            return;
        }
        AutomaStaffMode mode = AutomaStaffHudOverlay.getActiveMode();
        if (mode == AutomaStaffMode.ASSIGN_CHEST || mode == AutomaStaffMode.SELECT_ZONE || ItemAutomaStaff.isMinerTargetMode(held)) {
            event.setCanceled(true);
            sendStaffLeftClickBlock(event.getPos().toLong(), player.isSneaking() || mode == AutomaStaffMode.ASSIGN_CHEST, mode);
        }
    }

    private static void dispatchStaffEntityClick(EntityAutomaton bot) {
        AutomaStaffMode mode = AutomaStaffHudOverlay.getActiveMode();
        boolean ctrlToggleFollow = mode == AutomaStaffMode.TOGGLE_FOLLOW;
        boolean sneakBind = mode == AutomaStaffMode.ASSIGN_CHEST;
        sendStaffLeftClickEntity(bot, ctrlToggleFollow, sneakBind, mode);
    }

    private static void sendStaffLeftClickEntity(EntityAutomaton bot, boolean ctrl, boolean sneak, AutomaStaffMode mode) {
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("entityId", bot.getEntityId());
        data.setBoolean("ctrl", ctrl);
        data.setBoolean("sneak", sneak);
        if (mode != null) {
            data.setString("staffMode", mode.name());
        }
        UINetwork.sendActionToServer("automata:staff_left_click_entity", data);
    }

    private static void sendStaffLeftClickBlock(long posLong, boolean sneak, AutomaStaffMode mode) {
        NBTTagCompound data = new NBTTagCompound();
        data.setLong("pos", posLong);
        data.setBoolean("sneak", sneak);
        if (mode != null) {
            data.setString("staffMode", mode.name());
        }
        UINetwork.sendActionToServer("automata:staff_left_click_block", data);
    }

    private static void tryOpenRemoteAutomatonGui(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || RegionSelectionController.isActive()) {
            return;
        }
        if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit instanceof EntityAutomaton) {
            return;
        }
        EntityAutomaton target = raycastAutomaton(player, REMOTE_RAYCAST_RANGE);
        if (target == null) {
            return;
        }
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("entityId", target.getEntityId());
        UINetwork.sendActionToServer("automata:open_remote_gui", data);
    }

    @Nullable
    public static EntityAutomaton raycastAutomaton(EntityPlayer player, double range) {
        Vec3d eyes = player.getPositionEyes(1.0F);
        Vec3d look = player.getLook(1.0F);
        Vec3d end = eyes.add(look.x * range, look.y * range, look.z * range);

        RayTraceResult blockHit = player.world.rayTraceBlocks(eyes, end, false, true, false);
        double bestDistSq = blockHit != null ? blockHit.hitVec.squareDistanceTo(eyes) : range * range;

        AxisAlignedBB searchBox = player.getEntityBoundingBox()
                .expand(look.x * range, look.y * range, look.z * range)
                .grow(1.0D);
        List<EntityAutomaton> candidates = player.world.getEntitiesWithinAABB(EntityAutomaton.class, searchBox);

        EntityAutomaton best = null;
        for (EntityAutomaton bot : candidates) {
            if (bot.isDead) {
                continue;
            }
            AxisAlignedBB hitBox = bot.getEntityBoundingBox().grow(0.3D);
            RayTraceResult intercept = hitBox.calculateIntercept(eyes, end);
            if (intercept != null) {
                double distSq = eyes.squareDistanceTo(intercept.hitVec);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = bot;
                }
            }
        }
        return best;
    }
}

