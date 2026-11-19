package com.x4yi.automata.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.annotation.Nonnull;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;

public final class UsefulBackpacksCompat {

    public static final String MOD_ID = "usefulbackpacks";

    private static volatile boolean reflectionInitialized = false;
    private static Class<?> itemBackPackClass;
    private static Class<?> enumBackPacksClass;
    private static Method byMetadataMethod;
    private static Method valuesMethod;
    private static Method getSizeXMethod;
    private static Method getSizeYMethod;
    private static Method getCountMethod;
    private static Field allowStackingField;

    private UsefulBackpacksCompat() {}

    public static boolean isLoaded() {
        try {
            return Loader.isModLoaded(MOD_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureReflection() {
        if (reflectionInitialized) {
            return;
        }
        synchronized (UsefulBackpacksCompat.class) {
            if (reflectionInitialized) {
                return;
            }
            try {
                itemBackPackClass = Class.forName("info.u_team.useful_backpacks.item.ItemBackPack");
                enumBackPacksClass = Class.forName("info.u_team.useful_backpacks.enums.EnumBackPacks");
                byMetadataMethod = enumBackPacksClass.getMethod("byMetadata", int.class);
                valuesMethod = enumBackPacksClass.getMethod("values");
                getSizeXMethod = enumBackPacksClass.getMethod("getSizeX");
                getSizeYMethod = enumBackPacksClass.getMethod("getSizeY");
                getCountMethod = enumBackPacksClass.getMethod("getCount");
                Class<?> commonCfg = Class.forName("info.u_team.useful_backpacks.config.CommonConfig");
                allowStackingField = commonCfg.getField("allowStackingBackpacks");
            } catch (Throwable ignored) {
            }
            reflectionInitialized = true;
        }
    }

    public static boolean isUsefulBackpack(@Nonnull ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        if (itemBackPackClass != null && itemBackPackClass.isInstance(stack.getItem())) {
            return true;
        }
        ResourceLocation key = stack.getItem().getRegistryName();
        return key != null && MOD_ID.equals(key.getNamespace()) && key.getPath().contains("backpack");
    }

    public static boolean isAllowStackingBackpacks() {
        ensureReflection();
        if (allowStackingField != null) {
            try {
                return allowStackingField.getBoolean(null);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static int[] getBackpackDimensions(@Nonnull ItemStack stack) {
        if (!isUsefulBackpack(stack)) {
            return new int[] {0, 0, 0};
        }
        ensureReflection();
        if (byMetadataMethod != null && getSizeXMethod != null && getSizeYMethod != null && getCountMethod != null) {
            try {
                Object enumVal = byMetadataMethod.invoke(null, stack.getMetadata());
                if (enumVal != null) {
                    int cols = ((Number) getSizeXMethod.invoke(enumVal)).intValue();
                    int rows = ((Number) getSizeYMethod.invoke(enumVal)).intValue();
                    int count = ((Number) getCountMethod.invoke(enumVal)).intValue();
                    if (cols > 0 && rows > 0 && count > 0) {
                        return new int[] {cols, rows, count};
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return switch (stack.getMetadata()) {
            case 1 -> new int[] {9, 6, 54};
            case 2 -> new int[] {13, 9, 117};
            default -> new int[] {5, 3, 15};
        };
    }

    public static int getMaxConfiguredBackpackSlots() {
        ensureReflection();
        if (valuesMethod != null && getCountMethod != null) {
            try {
                Object raw = valuesMethod.invoke(null);
                if (raw instanceof Object[] arr) {
                    int max = 0;
                    for (Object val : arr) {
                        int count = ((Number) getCountMethod.invoke(val)).intValue();
                        if (count > max) {
                            max = count;
                        }
                    }
                    if (max > 0) {
                        return max;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return 117;
    }
}

