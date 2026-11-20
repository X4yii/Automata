package com.x4yi.automata.compat;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.inventory.AutomatonInventory;

/**
 * Reflection-backed compatibility for Baubles (7 accessory slots) and Baubles-based
 * artifact mods such as RLArtifacts / Artifacts.
 */
public final class BaublesCompat {

    public static final int BAUBLE_SLOT_COUNT = 7;
    private static final String MOD_ID = "baubles";

    private static boolean reflectionInit;
    @Nullable
    private static Class<?> iBaubleClass;
    @Nullable
    private static Method getBaublesHandlerMethod;
    @Nullable
    private static Method getBaubleTypeMethod;
    @Nullable
    private static Method onWornTickMethod;
    @Nullable
    private static Method onEquippedMethod;
    @Nullable
    private static Method onUnequippedMethod;
    @Nullable
    private static Method canEquipMethod;
    @Nullable
    private static Method handlerGetSlotsMethod;
    @Nullable
    private static Method handlerGetStackMethod;
    @Nullable
    private static Method handlerSetStackMethod;

    private BaublesCompat() {}

    public static boolean isLoaded() {
        try {
            return Loader.isModLoaded(MOD_ID);
        } catch (Throwable ignored) {
            return false;
        }
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
            iBaubleClass = Class.forName("baubles.api.IBauble");
            getBaubleTypeMethod = iBaubleClass.getMethod("getBaubleType", ItemStack.class);
            onWornTickMethod = iBaubleClass.getMethod("onWornTick", ItemStack.class, EntityLivingBase.class);
            onEquippedMethod = iBaubleClass.getMethod("onEquipped", ItemStack.class, EntityLivingBase.class);
            onUnequippedMethod = iBaubleClass.getMethod("onUnequipped", ItemStack.class, EntityLivingBase.class);
            canEquipMethod = iBaubleClass.getMethod("canEquip", ItemStack.class, EntityLivingBase.class);

            Class<?> apiClass = Class.forName("baubles.api.BaublesApi");
            getBaublesHandlerMethod = apiClass.getMethod("getBaublesHandler", EntityPlayer.class);

            Class<?> handlerClass = Class.forName("baubles.api.cap.IBaublesItemHandler");
            handlerGetSlotsMethod = handlerClass.getMethod("getSlots");
            handlerGetStackMethod = handlerClass.getMethod("getStackInSlot", int.class);
            handlerSetStackMethod = handlerClass.getMethod("setStackInSlot", int.class, ItemStack.class);
        } catch (Throwable ignored) {
            iBaubleClass = null;
        }
    }

    public static boolean isBaubleItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        return iBaubleClass != null && iBaubleClass.isInstance(stack.getItem());
    }

    public static boolean canEquipInSlot(ItemStack stack, int slot, @Nullable EntityLivingBase wearer) {
        if (stack.isEmpty() || slot < 0 || slot >= BAUBLE_SLOT_COUNT || !isBaubleItem(stack)) {
            return false;
        }
        try {
            if (wearer != null && canEquipMethod != null) {
                Object allowed = canEquipMethod.invoke(stack.getItem(), stack, wearer);
                if (allowed instanceof Boolean b && !b) {
                    return false;
                }
            }
            if (getBaubleTypeMethod != null) {
                Object typeObj = getBaubleTypeMethod.invoke(stack.getItem(), stack);
                if (typeObj != null) {
                    String typeName = typeObj.toString();
                    return switch (typeName) {
                        case "TRINKET" -> true;
                        case "AMULET" -> slot == 0;
                        case "RING" -> slot == 1 || slot == 2;
                        case "BELT" -> slot == 3;
                        case "HEAD" -> slot == 4;
                        case "BODY" -> slot == 5;
                        case "CHARM" -> slot == 6;
                        default -> false;
                    };
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static void syncToFakePlayer(AutomatonInventory inv, FakePlayer fake) {
        ensureReflection();
        if (getBaublesHandlerMethod == null || handlerSetStackMethod == null || handlerGetSlotsMethod == null) {
            return;
        }
        try {
            Object handler = getBaublesHandlerMethod.invoke(null, fake);
            if (handler == null) {
                return;
            }
            int remoteSlots = (Integer) handlerGetSlotsMethod.invoke(handler);
            IItemHandlerModifiable local = inv.getBaublesInventory();
            int count = Math.min(remoteSlots, local.getSlots());
            for (int i = 0; i < count; i++) {
                ItemStack localStack = local.getStackInSlot(i);
                ItemStack remoteStack = handlerGetStackMethod != null
                        ? (ItemStack) handlerGetStackMethod.invoke(handler, i)
                        : ItemStack.EMPTY;
                if (!ItemStack.areItemStacksEqual(localStack, remoteStack)) {
                    handlerSetStackMethod.invoke(handler, i, localStack.copy());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void tickWornBaubles(AutomatonInventory inv, FakePlayer fake) {
        if (!isLoaded()) {
            return;
        }
        ensureReflection();
        if (onWornTickMethod == null) {
            return;
        }
        IItemHandlerModifiable local = inv.getBaublesInventory();
        for (int i = 0; i < local.getSlots(); i++) {
            ItemStack stack = local.getStackInSlot(i);
            if (!stack.isEmpty() && isBaubleItem(stack)) {
                try {
                    onWornTickMethod.invoke(stack.getItem(), stack, fake);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static boolean hasAnyWornBauble(AutomatonInventory inv) {
        IItemHandlerModifiable local = inv.getBaublesInventory();
        for (int i = 0; i < local.getSlots(); i++) {
            if (!local.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
