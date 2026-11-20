package com.x4yi.automata.compat;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Server-side compatibility for the Wings mod (me.paulf.wings).
 * Detects equipped or carried wings and enforces hunger/satiation thresholds
 * matching Wings' WingSettings rules without touching client-only Wings classes.
 */
public final class WingsCompat {

    private static final String MOD_ID = "wings";
    public static final int DEFAULT_REQUIRED_FLIGHT_SATIATION = 6;
    public static final int DEFAULT_REQUIRED_LAND_SATIATION = 2;

    @Nullable
    private static Class<?> itemWingsClass;
    private static boolean reflectionInit;

    private WingsCompat() {}

    public static boolean isLoaded() {
        try {
            return Loader.isModLoaded(MOD_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isWingsItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        if (itemWingsClass != null && itemWingsClass.isInstance(stack.getItem())) {
            return true;
        }
        ResourceLocation id = stack.getItem().getRegistryName();
        return id != null && MOD_ID.equals(id.getNamespace()) && id.getPath().contains("wings");
    }

    public static boolean hasUsableWings(EntityAutomaton bot) {
        if (!isLoaded()) {
            return false;
        }
        IItemHandlerModifiable baubles = bot.getInventory().getBaublesInventory();
        for (int i = 0; i < baubles.getSlots(); i++) {
            if (isWingsItem(baubles.getStackInSlot(i))) {
                return true;
            }
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isWingsItem(inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean canFly(EntityAutomaton bot) {
        return hasUsableWings(bot) && bot.getHunger() >= DEFAULT_REQUIRED_FLIGHT_SATIATION;
    }

    public static boolean canLand(EntityAutomaton bot) {
        return hasUsableWings(bot) && bot.getHunger() >= DEFAULT_REQUIRED_LAND_SATIATION;
    }

    private static void ensureReflection() {
        if (reflectionInit) {
            return;
        }
        reflectionInit = true;
        if (!isLoaded()) {
            return;
        }
        try {
            itemWingsClass = Class.forName("me.paulf.wings.server.item.ItemWings");
        } catch (Throwable ignored) {
            itemWingsClass = null;
        }
    }
}
