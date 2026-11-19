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

public final class SpartanWeaponryCompat {

    public static final String MOD_ID = "spartanweaponry";

    private static volatile boolean reflectionInitialized = false;
    @Nullable
    private static Class<?> throwingWeaponClass;
    @Nullable
    private static Class<?> caestusClass;
    @Nullable
    private static Class<?> weaponBaseClass;
    @Nullable
    private static Class<?> thrownWeaponEntityClass;
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
    @Nullable
    private static Method crossbowGetReloadTicksMethod;
    @Nullable
    private static Method crossbowGetAimTicksMethod;
    @Nullable
    private static Method crossbowGetBoltSpeedMethod;
    @Nullable
    private static Method thrownWeaponGetStackMethod;

    public static final String NBT_OWNER_BOT_UUID = "AutomataOwnerBot";

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
                caestusClass = findClass("com.oblivioussp.spartanweaponry.item.ItemCaestus");
                weaponBaseClass = findClass("com.oblivioussp.spartanweaponry.item.ItemWeaponBase");
                if (throwingWeaponClass != null) {
                    getMaxAmmoMethod = findMethod(throwingWeaponClass, "getMaxAmmo", ItemStack.class);
                }
                thrownWeaponEntityClass = findClass("com.oblivioussp.spartanweaponry.entity.projectile.EntityThrownWeapon");
                if (thrownWeaponEntityClass != null) {
                    thrownWeaponGetStackMethod = findMethod(thrownWeaponEntityClass, "getWeaponStack");
                }
                boomerangClass = findClass("com.oblivioussp.spartanweaponry.item.ItemBoomerang");
                boomerangEntityClass = findClass("com.oblivioussp.spartanweaponry.entity.projectile.EntityBoomerang");
                crossbowClass = findClass("com.oblivioussp.spartanweaponry.item.ItemCrossbow");
                if (crossbowClass != null) {
                    crossbowGetReloadTicksMethod = findMethod(crossbowClass, "getReloadTicks", ItemStack.class);
                    crossbowGetAimTicksMethod = findMethod(crossbowClass, "getAimTicks", ItemStack.class);
                    crossbowGetBoltSpeedMethod = findMethod(crossbowClass, "getBoltSpeed");
                }
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

    public static boolean isCaestus(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        Item item = stack.getItem();
        if (caestusClass != null && caestusClass.isInstance(item)) {
            return true;
        }
        ResourceLocation key = item.getRegistryName();
        if (key == null) {
            return false;
        }
        String path = key.getPath().toLowerCase(Locale.ROOT);
        return path.contains("caestus") || path.contains("cestus");
    }

    public static boolean isQuickStrikeWeapon(ItemStack stack) {
        return isCaestus(stack) || hasWeaponProperty(stack, "quick_strike");
    }

    public static boolean isSpartanMeleeWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflection();
        Item item = stack.getItem();
        if ((weaponBaseClass != null && weaponBaseClass.isInstance(item))
                || (caestusClass != null && caestusClass.isInstance(item))) {
            return true;
        }
        ResourceLocation key = item.getRegistryName();
        return key != null
                && MOD_ID.equals(key.getNamespace())
                && !isCrossbow(stack)
                && !isBoltItem(stack)
                && !isBoomerang(stack)
                && !isThrowableWeapon(stack)
                && !(item instanceof ItemBow);
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
        if (nbt.getBoolean("isLoaded") || nbt.getBoolean("Loaded")) {
            return true;
        }
        if (nbt.hasKey("ammoStack", 10)) {
            ItemStack loadedBolt = new ItemStack(nbt.getCompoundTag("ammoStack"));
            return !loadedBolt.isEmpty();
        }
        return false;
    }

    public static int getCrossbowReloadTicks(ItemStack stack) {
        if (stack.isEmpty()) {
            return 35;
        }
        ensureReflection();
        if (crossbowGetReloadTicksMethod != null && crossbowClass != null && crossbowClass.isInstance(stack.getItem())) {
            try {
                Object res = crossbowGetReloadTicksMethod.invoke(stack.getItem(), stack);
                if (res instanceof Number num && num.intValue() > 0) {
                    return num.intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return 35;
    }

    public static int getCrossbowAimTicks(ItemStack stack) {
        if (stack.isEmpty()) {
            return 10;
        }
        ensureReflection();
        if (crossbowGetAimTicksMethod != null && crossbowClass != null && crossbowClass.isInstance(stack.getItem())) {
            try {
                Object res = crossbowGetAimTicksMethod.invoke(stack.getItem(), stack);
                if (res instanceof Number num && num.intValue() > 0) {
                    return num.intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return 10;
    }

    public static double getCrossbowBoltSpeed(ItemStack stack) {
        if (stack.isEmpty()) {
            return 1.5D;
        }
        ensureReflection();
        if (crossbowGetBoltSpeedMethod != null && crossbowClass != null && crossbowClass.isInstance(stack.getItem())) {
            try {
                Object res = crossbowGetBoltSpeedMethod.invoke(stack.getItem());
                if (res instanceof Number num && num.doubleValue() > 0.1D) {
                    return num.doubleValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return 1.5D;
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
        return ModdedWeaponCompat.getExtraReachBonus(stack);
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
        return hasWeaponProperty(stack, "two_handed")
                || hasWeaponProperty(stack, "twohanded")
                || ModdedWeaponCompat.isBetterSurvivalTwoHanded(stack);
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

    public static boolean isThrownWeaponEntity(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflection();
        if (thrownWeaponEntityClass != null && thrownWeaponEntityClass.isInstance(entity)) {
            return true;
        }
        String name = entity.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return name.contains("thrownweapon") || name.contains("thrownjavelin");
    }

    public static void tagProjectileOwner(Entity projectile, EntityAutomaton owner) {
        if (projectile == null || owner == null) {
            return;
        }
        projectile.getEntityData().setUniqueId(NBT_OWNER_BOT_UUID, owner.getUniqueID());
        if (owner.world != null && (isBoomerangEntity(projectile) || isThrownWeaponEntity(projectile))) {
            owner.markThrownProjectileActive(owner.world.getTotalWorldTime() + 200L);
        }
    }

    public static boolean isOwnedByBot(EntityAutomaton bot, @Nullable Entity entity) {
        if (bot == null || entity == null) {
            return false;
        }
        if (entity instanceof EntityArrow arrow && arrow.shootingEntity == bot) {
            return true;
        }
        NBTTagCompound data = entity.getEntityData();
        return data.hasUniqueId(NBT_OWNER_BOT_UUID) && bot.getUniqueID().equals(data.getUniqueId(NBT_OWNER_BOT_UUID));
    }

    public static boolean isOwnedByOtherBot(EntityAutomaton self, @Nullable Entity entity) {
        if (self == null || entity == null) {
            return false;
        }
        NBTTagCompound data = entity.getEntityData();
        if (data.hasUniqueId(NBT_OWNER_BOT_UUID) && !self.getUniqueID().equals(data.getUniqueId(NBT_OWNER_BOT_UUID))) {
            return true;
        }
        if (entity instanceof EntityArrow arrow && arrow.shootingEntity instanceof EntityAutomaton other && other != self) {
            return true;
        }
        if (entity instanceof net.minecraft.entity.item.EntityItem itemEntity) {
            ItemStack stack = itemEntity.getItem();
            if (isThrowableWeapon(stack) && stack.hasTagCompound() && stack.getTagCompound().hasUniqueId("UUID")) {
                java.util.UUID stackUuid = stack.getTagCompound().getUniqueId("UUID");
                return !botInventoryContainsThrowingUuid(self, stackUuid);
            }
        }
        return false;
    }

    private static boolean botInventoryContainsThrowingUuid(EntityAutomaton bot, java.util.UUID weaponUuid) {
        if (matchesThrowingUuid(bot.getHeldItemMainhand(), weaponUuid)
                || matchesThrowingUuid(bot.getHeldItemOffhand(), weaponUuid)) {
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (matchesThrowingUuid(inv.getStackInSlot(i), weaponUuid)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesThrowingUuid(ItemStack stack, java.util.UUID weaponUuid) {
        return !stack.isEmpty()
                && stack.hasTagCompound()
                && stack.getTagCompound().hasUniqueId("UUID")
                && weaponUuid.equals(stack.getTagCompound().getUniqueId("UUID"));
    }

    @Nullable
    public static EntityArrow findNearestOwnedDroppedThrowingWeapon(EntityAutomaton bot, double radius) {
        if (!isLoaded() || bot.world == null) {
            return null;
        }
        EntityArrow best = null;
        double bestDistSq = radius * radius;
        for (EntityArrow arrow : bot.world.getEntitiesWithinAABB(EntityArrow.class, bot.getEntityBoundingBox().grow(radius, 4.0D, radius))) {
            if (!arrow.isEntityAlive() || !isOwnedByBot(bot, arrow)) {
                continue;
            }
            boolean thrown = isThrownWeaponEntity(arrow);
            boolean groundedBoomerang = isBoomerangEntity(arrow)
                    && arrow.ticksExisted >= 20
                    && (arrow.motionX * arrow.motionX + arrow.motionZ * arrow.motionZ) < 0.01D;
            if ((!thrown && !groundedBoomerang) || arrow.ticksExisted < 8) {
                continue;
            }
            double dSq = bot.getDistanceSq(arrow);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = arrow;
            }
        }
        return best;
    }

    @Nullable
    public static net.minecraft.util.math.BlockPos findNearestOwnedDroppedWeaponPos(EntityAutomaton bot, double radius) {
        if (!isLoaded() || bot.world == null) {
            return null;
        }
        double bestDistSq = radius * radius;
        net.minecraft.util.math.BlockPos bestPos = null;
        EntityArrow arrow = findNearestOwnedDroppedThrowingWeapon(bot, radius);
        if (arrow != null) {
            bestDistSq = bot.getDistanceSq(arrow);
            bestPos = arrow.getPosition();
        }
        if (!hasDepletedThrowingOrBoomerang(bot)) {
            return bestPos;
        }
        for (net.minecraft.entity.item.EntityItem itemEntity : bot.world.getEntitiesWithinAABB(
                net.minecraft.entity.item.EntityItem.class,
                bot.getEntityBoundingBox().grow(radius, 4.0D, radius))) {
            if (!itemEntity.isEntityAlive() || itemEntity.cannotPickup() || isOwnedByOtherBot(bot, itemEntity)) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            if (!isThrowableWeapon(stack) && !isBoomerang(stack)) {
                continue;
            }
            double dSq = bot.getDistanceSq(itemEntity);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestPos = itemEntity.getPosition();
            }
        }
        return bestPos;
    }

    private static boolean hasDepletedThrowingOrBoomerang(EntityAutomaton bot) {
        ItemStack held = bot.getHeldItemMainhand();
        if ((isThrowableWeapon(held) || isBoomerang(held))
                && getRemainingThrowingAmmo(held) < getMaxThrowingAmmo(held)) {
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if ((isThrowableWeapon(s) || isBoomerang(s))
                    && getRemainingThrowingAmmo(s) < getMaxThrowingAmmo(s)) {
                return true;
            }
        }
        return false;
    }

    public static void tickThrownWeaponRecovery(EntityAutomaton bot) {
        if (!isLoaded() || bot.world == null || bot.world.isRemote || !(bot.world instanceof net.minecraft.world.WorldServer worldServer)) {
            return;
        }
        long now = bot.world.getTotalWorldTime();
        if (!bot.hasActiveThrownProjectile(now) && !hasDepletedThrowingOrBoomerang(bot)) {
            return;
        }
        for (EntityArrow arrow : bot.world.getEntitiesWithinAABB(EntityArrow.class, bot.getEntityBoundingBox().grow(4.0D, 2.5D, 4.0D))) {
            if (!arrow.isEntityAlive() || !isOwnedByBot(bot, arrow) || arrow.ticksExisted < 8) {
                continue;
            }
            if (isBoomerangEntity(arrow)) {
                if (arrow.ticksExisted >= 20 && bot.getDistanceSq(arrow) <= 6.25D) {
                    if (refillBoomerangPlaceholder(bot, null, 1)) {
                        arrow.setDead();
                    }
                }
                continue;
            }
            if (!isThrownWeaponEntity(arrow)) {
                continue;
            }
            if (bot.getDistanceSq(arrow) <= 6.25D) {
                com.x4yi.automata.world.FakePlayerService.pickupThrownProjectileAsFakePlayer(worldServer, bot, arrow);
            }
        }
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
        long now = bot.world.getTotalWorldTime();
        if (!bot.hasActiveThrownProjectile(now) && !hasDepletedThrowingOrBoomerang(bot)) {
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

