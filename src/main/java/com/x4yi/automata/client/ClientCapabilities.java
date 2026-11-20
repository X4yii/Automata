package com.x4yi.automata.client;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import com.x4yi.x4ui.common.network.UINetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.config.AutomataConfig;

/**
 * Detects client-side dynamic lighting capabilities strictly via lifecycle and GUI events (no polling)
 * and notifies the server only when the effective state changes.
 */
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID, value = Side.CLIENT)
public final class ClientCapabilities {

    @Nullable
    private static Boolean lastSentDynamicLights;
    private static boolean pendingSettingsReeval = false;

    private ClientCapabilities() {}

    @SubscribeEvent
    public static void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        lastSentDynamicLights = null;
        pendingSettingsReeval = false;
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!event.getWorld().isRemote) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null && event.getEntity() == mc.player) {
            syncIfChanged(false);
        }
    }

    @SubscribeEvent
    public static void onGuiOpen(GuiOpenEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen current = mc.currentScreen;
        GuiScreen next = event.getGui();

        if (isSettingsScreen(current)) {
            pendingSettingsReeval = true;
        }
        if (next == null && pendingSettingsReeval) {
            pendingSettingsReeval = false;
            syncIfChanged(false);
        }
    }

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        String modId = event.getModID();
        if (AutomataMod.MOD_ID.equals(modId) || matchesAnyProviderMod(modId)) {
            syncIfChanged(false);
        }
    }

    public static void syncIfChanged(boolean force) {
        if (!UINetwork.isServerSupported()) {
            return;
        }
        boolean current = detectDynamicLightsEnabled();
        if (!force && lastSentDynamicLights != null && lastSentDynamicLights.booleanValue() == current) {
            return;
        }
        lastSentDynamicLights = current;
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("dynamicLights", current);
        UINetwork.sendActionToServer("automata:client_caps", tag);
    }

    public static boolean detectDynamicLightsEnabled() {
        String[] providers = AutomataConfig.dynamicLightProviders;
        if (providers == null) {
            return false;
        }
        for (String raw : providers) {
            if (isProviderActive(raw)) {
                return true;
            }
        }
        return false;
    }

    static boolean isProviderActive(@Nullable String spec) {
        if (spec == null) {
            return false;
        }
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        int colon = trimmed.indexOf(':');
        String modId = colon >= 0 ? trimmed.substring(0, colon).trim() : trimmed;
        String probe = colon >= 0 ? trimmed.substring(colon + 1).trim() : "";

        // OptiFine is loaded as a tweaker/jar without a standard Forge modid in some setups.
        if (!"optifine".equalsIgnoreCase(modId) && !Loader.isModLoaded(modId)) {
            return false;
        }
        if (probe.isEmpty()) {
            return Loader.isModLoaded(modId);
        }

        int hash = probe.indexOf('#');
        String className = hash >= 0 ? probe.substring(0, hash).trim() : probe;
        String methodName = hash >= 0 ? probe.substring(hash + 1).trim() : "";
        try {
            Class<?> clazz = Class.forName(className, false, ClientCapabilities.class.getClassLoader());
            if (methodName.isEmpty()) {
                return true;
            }
            Method method = clazz.getMethod(methodName);
            Object result = method.invoke(null);
            return result instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean matchesAnyProviderMod(@Nullable String modId) {
        if (modId == null || AutomataConfig.dynamicLightProviders == null) {
            return false;
        }
        for (String raw : AutomataConfig.dynamicLightProviders) {
            if (raw == null) {
                continue;
            }
            int colon = raw.indexOf(':');
            String entryMod = (colon >= 0 ? raw.substring(0, colon) : raw).trim();
            if (modId.equalsIgnoreCase(entryMod)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSettingsScreen(@Nullable GuiScreen screen) {
        if (screen == null) {
            return false;
        }
        if (screen instanceof GuiVideoSettings || screen instanceof GuiOptions) {
            return true;
        }
        String name = screen.getClass().getName();
        return name.startsWith("net.optifine.") || name.contains("VideoSettings") || name.contains("GuiModList");
    }
}
