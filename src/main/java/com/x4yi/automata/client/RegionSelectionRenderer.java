/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.item.ItemAutomaStaff;

/**
 * Renders live A/B selection previews and a subtle outline of the configured automaton work zone.
 */
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID, value = Side.CLIENT)
public final class RegionSelectionRenderer {

    public static final class ZoneBounds {
        public final BlockPos min;
        public final BlockPos max;

        ZoneBounds(BlockPos min, BlockPos max) {
            this.min = min;
            this.max = max;
        }
    }

    private static final Map<Integer, ZoneBounds> CONFIGURED_ZONES = new ConcurrentHashMap<>();
    private static int activeEntityId = -1;

    private RegionSelectionRenderer() {}

    public static void setLastConfiguredZone(int entityId, @Nullable BlockPos p1, @Nullable BlockPos p2) {
        if (entityId < 0) {
            return;
        }
        if (p1 == null || p2 == null) {
            CONFIGURED_ZONES.remove(entityId);
            if (activeEntityId == entityId) {
                activeEntityId = -1;
            }
            return;
        }
        BlockPos min = new BlockPos(
                Math.min(p1.getX(), p2.getX()),
                Math.min(p1.getY(), p2.getY()),
                Math.min(p1.getZ(), p2.getZ()));
        BlockPos max = new BlockPos(
                Math.max(p1.getX(), p2.getX()),
                Math.max(p1.getY(), p2.getY()),
                Math.max(p1.getZ(), p2.getZ()));
        CONFIGURED_ZONES.put(entityId, new ZoneBounds(min, max));
        activeEntityId = entityId;
    }

    @Nullable
    public static ZoneBounds getConfiguredZone(int entityId) {
        return CONFIGURED_ZONES.get(entityId);
    }

    public static void clearAllZones() {
        CONFIGURED_ZONES.clear();
        activeEntityId = -1;
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if (player == null) {
            return;
        }
        double camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * event.getPartialTicks();
        double camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * event.getPartialTicks();
        double camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * event.getPartialTicks();

        if (RegionSelectionController.isActive()) {
            BlockPos p1 = RegionSelectionController.getPointA();
            BlockPos p2 = RegionSelectionController.getPointB();
            if (p1 != null) {
                BlockPos end = p2 != null && RegionSelectionController.isSelectingB() ? p2 : p1;
                drawBox(camX, camY, camZ, p1, end, 2.5F, 0.35F, 0.85F, 1.0F, 0.90F);
                drawBox(camX, camY, camZ, p1, p1, 3.0F, 0.25F, 0.95F, 0.50F, 0.95F);
            }
            return;
        }

        ItemStack held = findLinkerStack(player);
        if (!held.isEmpty()) {
            BlockPos p1 = ItemAutomaStaff.getPos1(held);
            BlockPos p2 = ItemAutomaStaff.getPos2(held);
            if (p1 != null || p2 != null) {
                BlockPos minPos = p1 != null ? p1 : p2;
                BlockPos maxPos = p2 != null ? p2 : p1;
                drawBox(camX, camY, camZ, minPos, maxPos, 2.2F, 1.0F, 0.85F, 0.1F, 0.85F);
            }
        }

        for (Map.Entry<Integer, ZoneBounds> entry : CONFIGURED_ZONES.entrySet()) {
            ZoneBounds bounds = entry.getValue();
            if (player.getDistanceSqToCenter(bounds.min) <= 4096.0D) {
                boolean isFocused = entry.getKey() == activeEntityId;
                drawBox(
                        camX, camY, camZ,
                        bounds.min, bounds.max,
                        isFocused ? 1.5F : 1.0F,
                        isFocused ? 0.38F : 0.78F,
                        isFocused ? 0.65F : 0.84F,
                        isFocused ? 0.98F : 0.90F,
                        isFocused ? 0.45F : 0.24F);
            }
        }
    }

    private static void drawBox(
            double camX,
            double camY,
            double camZ,
            BlockPos p1,
            BlockPos p2,
            float lineWidth,
            float r,
            float g,
            float b,
            float a) {
        AxisAlignedBB box = new AxisAlignedBB(
                Math.min(p1.getX(), p2.getX()),
                Math.min(p1.getY(), p2.getY()),
                Math.min(p1.getZ(), p2.getZ()),
                Math.max(p1.getX(), p2.getX()) + 1.0D,
                Math.max(p1.getY(), p2.getY()) + 1.0D,
                Math.max(p1.getZ(), p2.getZ()) + 1.0D);

        GlStateManager.pushMatrix();
        GlStateManager.translate(-camX, -camY, -camZ);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.glLineWidth(lineWidth);

        RenderGlobal.drawSelectionBoundingBox(box, r, g, b, a);

        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static ItemStack findLinkerStack(EntityPlayer player) {
        ItemStack main = player.getHeldItemMainhand();
        if (main.getItem() instanceof ItemAutomaStaff) {
            return main;
        }
        ItemStack off = player.getHeldItemOffhand();
        if (off.getItem() instanceof ItemAutomaStaff) {
            return off;
        }
        return ItemStack.EMPTY;
    }
}
