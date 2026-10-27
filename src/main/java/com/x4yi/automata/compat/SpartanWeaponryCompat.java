package com.x4yi.automata.compat;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Dynamic, reflection-based integration with Spartan Weaponry (1.12.2) and
 * attribute-driven weapon inspection without hardcoded item tables.
 */
public final class SpartanWeaponryCompat {

    public static final String MOD_ID = "spartanweaponry";

    private static volatile boolean reflectionInitialized = false;
    @Nullable
    private static Class<?> throwingWeaponClass;
    @Nullable
    private static Class<?> boomerangClass;
    @Nullable
    private static Class<?> boomerangEntityClass;
    @Nullable
    private static Class<?> crossbowClass;
    @Nullable
    private static Class<?> boltClass;
    @Nullable
    private static Class<?> propertyContainerClass;
    @Nullable
    private static Method getAllPropertiesMethod;
    @Nullable
    private static Method getPropertyTypeMethod;
    @Nullable
    private static Method getPropertyMagnitudeMethod;
    @Nullable
    private static Method getMaxAmmoMethod;

    private SpartanWeaponryCompat() {}

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
        synchronized (SpartanWeaponryCompat.class) {
            if (reflectionInitialized) {
                return;
            }
            if (isLoaded()) {
                throwingWeaponClass = findClass("com.oblivioussp.spartanweaponry.item.ItemThrowingWeapon");
                if (throwingWeaponClass != null) {
                    getMaxAmmoMethod = findMethod(throwingWeaponClass, "getMaxAmmo", ItemStack.class);
                }
                boomerangClass = findClass("com.oblivioussp.spartanweaponry.item.ItemBoomerang");
                boomerangEntityClass = findClass("com.oblivioussp.spartanweaponry.entity.projectile.EntityBoomerang");
                crossbowClass = findClass("com.oblivioussp.spartanweaponry.item.ItemCrossbow");
                boltClass = findClass("com.oblivioussp.spartanweaponry.item.ItemBolt");
                propertyContainerClass = findClass("com.oblivioussp.spartanweaponry.api.IWeaponPropertyContainer");
                if (propertyContainerClass != null) {
                    getAllPropertiesMethod = findMethod(propertyContainerClass, "getAllWeaponProperties");
                }
                Class<?> propClass = findClass("com.oblivioussp.spartanweaponry.api.weaponproperty.WeaponProperty");
                if (propClass != null) {
                    getPropertyTypeMethod = findMethod(propClass, "getType");
                    getPropertyMagnitudeMethod = findMethod(propClass, "getMagnitude");
                }
            }
            reflectionInitialized = true;
        }
    }

    @Nullable
    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isCrossbow(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        Item item = stack.getItem();
        if (crossbowClass != null && crossbowClass.isInstance(item)) {
            return true;
        }
        ResourceLocation key = item.getRegistryName();
        return stack.getItemUseAction() == EnumAction.BOW
                && key != null
                && key.getPath().toLowerCase(Locale.ROOT).contains("crossbow");
    }

    public static boolean isBoltItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        Item item = stack.getItem();
        if (boltClass != null && boltClass.isInstance(item)) {
            return true;
        }
        ResourceLocation key = item.getRegistryName();
        return key != null && key.getPath().toLowerCase(Locale.ROOT).contains("bolt");
    }

    public static boolean isThrowableWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        Item item = stack.getItem();
        if (throwingWeaponClass != null && throwingWeaponClass.isInstance(item)) {
            return true;
        }
        return hasWeaponProperty(stack, "throwable");
    }

    public static boolean isBoomerang(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        Item item = stack.getItem();
        if (boomerangClass != null && boomerangClass.isInstance(item)) {
            return true;
        }
        ResourceLocation key = item.getRegistryName();
        return key != null && key.getPath().toLowerCase(Locale.ROOT).contains("boomerang");
    }

    public static int getMaxThrowingAmmo(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        ensureReflection();
        if (getMaxAmmoMethod != null && throwingWeaponClass != null && throwingWeaponClass.isInstance(stack.getItem())) {
            try {
                Object res = getMaxAmmoMethod.invoke(stack.getItem(), stack);
                if (res instanceof Number num && num.intValue() > 0) {
                    return num.intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return isBoomerang(stack) ? 1 : Math.max(1, stack.getCount());
    }

    public static int getRemainingThrowingAmmo(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        NBTTagCompound nbt = stack.getTagCompound();
        if (nbt != null) {
            if (nbt.hasKey("AmmoUsed")) {
                int max = getMaxThrowingAmmo(stack);
                return Math.max(0, max - nbt.getInteger("AmmoUsed"));
            }
            if (nbt.hasKey("Ammo")) {
                return Math.max(0, nbt.getInteger("Ammo"));
            }
        }
        return stack.getCount();
    }

    public static boolean isCrossbowLoaded(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        NBTTagCompound nbt = stack.getTagCompound();
        if (nbt == null) {
            return false;
        }
        if (nbt.getBoolean("Loaded")) {
            return true;
        }
        if (nbt.hasKey("ammoStack", 10)) {
            ItemStack loadedBolt = new ItemStack(nbt.getCompoundTag("ammoStack"));
            return !loadedBolt.isEmpty();
        }
        return false;
    }

    public static double getReachBonus(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double reachBonus = 0.0D;
        Collection<AttributeModifier> reachMods =
                stack.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                        .get(EntityPlayer.REACH_DISTANCE.getName());
        for (AttributeModifier mod : reachMods) {
            if (mod.getOperation() == 0) {
                reachBonus += mod.getAmount();
            }
        }
        if (reachBonus > 0.0D) {
            return reachBonus;
        }
        double propMagnitude = getWeaponPropertyMagnitude(stack, "reach");
        if (propMagnitude > 0.0D) {
            return Math.max(0.0D, propMagnitude - 5.0D);
        }
        return 0.0D;
    }

    public static double getAttackSpeedModifier(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double total = 0.0D;
        Collection<AttributeModifier> mods =
                stack.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                        .get(SharedMonsterAttributes.ATTACK_SPEED.getName());
        for (AttributeModifier mod : mods) {
            total += mod.getAmount();
        }
        return total;
    }

    public static boolean isTwoHanded(ItemStack stack) {
        return hasWeaponProperty(stack, "two_handed") || hasWeaponProperty(stack, "twohanded");
    }

    public static boolean hasWeaponProperty(ItemStack stack, String propertyTypeSubstring) {
        return getWeaponPropertyMagnitude(stack, propertyTypeSubstring) >= 0.0D;
    }

    public static double getWeaponPropertyMagnitude(ItemStack stack, String propertyTypeSubstring) {
        if (stack.isEmpty()) {
            return -1.0D;
        }
        ensureReflection();
        Item item = stack.getItem();
        if (propertyContainerClass == null
                || getAllPropertiesMethod == null
                || getPropertyTypeMethod == null
                || !propertyContainerClass.isInstance(item)) {
            return -1.0D;
        }
        try {
            Object rawList = getAllPropertiesMethod.invoke(item);
            if (!(rawList instanceof List<?> list)) {
                return -1.0D;
            }
            String needle = propertyTypeSubstring.toLowerCase(Locale.ROOT);
            for (Object prop : list) {
                if (prop == null) {
                    continue;
                }
                Object typeObj = getPropertyTypeMethod.invoke(prop);
                if (typeObj != null && typeObj.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                    if (getPropertyMagnitudeMethod != null) {
                        Object mag = getPropertyMagnitudeMethod.invoke(prop);
                        if (mag instanceof Number num) {
                            return num.doubleValue();
                        }
                    }
                    return 1.0D;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1.0D;
    }

    public static boolean isSpartanRangedItem(ItemStack stack) {
        return isBoomerang(stack) || isThrowableWeapon(stack) || isCrossbow(stack)
                || (!stack.isEmpty() && stack.getItem() instanceof ItemBow);
    }

    public static boolean isBoomerangEntity(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflection();
        if (boomerangEntityClass != null && boomerangEntityClass.isInstance(entity)) {
            return true;
        }
        return entity.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("boomerang");
    }

    public static boolean hasBoomerangInFlight(EntityAutomaton bot) {
        if (!isLoaded() || bot.world == null) {
            return false;
        }
        for (EntityArrow arrow : bot.world.getEntitiesWithinAABB(EntityArrow.class, bot.getEntityBoundingBox().grow(48.0D))) {
            if (arrow.isEntityAlive() && arrow.shootingEntity == bot && isBoomerangEntity(arrow)) {
                return true;
            }
        }
        return false;
    }

    public static void tickBoomerangReturn(EntityAutomaton bot) {
        if (!isLoaded() || bot.world == null || bot.world.isRemote) {
            return;
        }
        for (EntityArrow arrow : bot.world.getEntitiesWithinAABB(EntityArrow.class, bot.getEntityBoundingBox().grow(48.0D))) {
            if (!arrow.isEntityAlive() || arrow.shootingEntity != bot || !isBoomerangEntity(arrow)) {
                continue;
            }
            if (arrow.ticksExisted >= 10 && bot.getDistanceSq(arrow) <= 5.25D) {
                if (refillBoomerangPlaceholder(bot, null, 1)) {
                    arrow.setDead();
                    continue;
                }
            }
            if (arrow.ticksExisted >= 14) {
                double dx = bot.posX - arrow.posX;
                double dy = (bot.posY + bot.getEyeHeight() * 0.7D) - arrow.posY;
                double dz = bot.posZ - arrow.posZ;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > 0.25D) {
                    double speed = 0.85D;
                    arrow.motionX = (dx / dist) * speed;
                    arrow.motionY = (dy / dist) * speed;
                    arrow.motionZ = (dz / dist) * speed;
                }
            }
        }
    }

    public static boolean tryAbsorbPickedBoomerang(EntityAutomaton bot, ItemStack picked) {
        if (picked.isEmpty() || !isBoomerang(picked)) {
            return false;
        }
        int pickedAmmo = getRemainingThrowingAmmo(picked);
        if (pickedAmmo <= 0) {
            return true;
        }
        return refillBoomerangPlaceholder(bot, picked.getItem(), pickedAmmo);
    }

    static boolean refillBoomerangPlaceholder(EntityAutomaton bot, @Nullable Item matchItem, int ammoAmount) {
        ItemStack main = bot.getHeldItemMainhand();
        if (isMatchingEmptyBoomerang(main, matchItem)) {
            setThrowingAmmo(main, ammoAmount);
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack slotStack = inv.getStackInSlot(i);
            if (isMatchingEmptyBoomerang(slotStack, matchItem)) {
                setThrowingAmmo(slotStack, ammoAmount);
                return true;
            }
        }
        return false;
    }

    private static boolean isMatchingEmptyBoomerang(ItemStack stack, @Nullable Item matchItem) {
        if (stack.isEmpty() || !isBoomerang(stack)) {
            return false;
        }
        if (matchItem != null && stack.getItem() != matchItem) {
            return false;
        }
        return getRemainingThrowingAmmo(stack) <= 0;
    }

    static void setThrowingAmmo(ItemStack stack, int ammo) {
        if (stack.isEmpty()) {
            return;
        }
        int clamped = Math.max(0, ammo);
        NBTTagCompound nbt = stack.getTagCompound();
        if (nbt == null) {
            nbt = new NBTTagCompound();
            stack.setTagCompound(nbt);
        }
        if (nbt.hasKey("AmmoUsed")) {
            int max = getMaxThrowingAmmo(stack);
            nbt.setInteger("AmmoUsed", Math.max(0, max - clamped));
        }
        nbt.setInteger("Ammo", clamped);
    }
}

