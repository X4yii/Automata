
package com.x4yi.automata.world;

import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.api.event.BotAttackEvent;
import com.x4yi.automata.api.event.BotBreakBlockEvent;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.WorldCacheManager;

public final class FakePlayerService {

    private static final UUID DEFAULT_PROFILE_ID = UUID.fromString("6a4c5e9d-1b2a-4f3c-9d8e-7a6b5c4d3e2f");
    private static final String FIXED_PROFILE_NAME = "[Automata]";
    private static final GameProfile DEFAULT_PROFILE = new GameProfile(DEFAULT_PROFILE_ID, FIXED_PROFILE_NAME);
    private static final java.util.Map<UUID, GameProfile> OWNER_PROFILE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CACHED_OWNER_PROFILES = 256;
    private static final EntityEquipmentSlot[] ARMOR_SLOTS = {
            EntityEquipmentSlot.FEET,
            EntityEquipmentSlot.LEGS,
            EntityEquipmentSlot.CHEST,
            EntityEquipmentSlot.HEAD
    };
    private static final ThreadLocal<Integer> REENTRANCY_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static volatile boolean loggedSwingReflectionFailure;

    private FakePlayerService() {}

    public static boolean isAutomataFakePlayer(FakePlayer fake) {
        String name = fake.getName();
        return FIXED_PROFILE_NAME.equals(name) || name.startsWith("[Auto:");
    }

    static GameProfile resolveProfile(@Nullable UUID ownerUuid, boolean useOwnerIdentity) {
        if (useOwnerIdentity && ownerUuid != null) {
            GameProfile cached = OWNER_PROFILE_CACHE.get(ownerUuid);
            if (cached != null) {
                return cached;
            }
            if (OWNER_PROFILE_CACHE.size() >= MAX_CACHED_OWNER_PROFILES) {
                OWNER_PROFILE_CACHE.clear();
            }
            String shortTag = ownerUuid.toString().substring(0, 8);
            GameProfile created = new GameProfile(ownerUuid, "[Auto:" + shortTag + "]");
            OWNER_PROFILE_CACHE.put(ownerUuid, created);
            return created;
        }
        return DEFAULT_PROFILE;
    }

    public static FakePlayer acquireSynced(WorldServer world, EntityAutomaton automaton) {
        GameProfile profile = resolveProfile(
                automaton.getOwnerUuid(),
                com.x4yi.automata.config.AutomataConfig.useOwnerIdentity);
        FakePlayer fake = FakePlayerFactory.get(world, profile);
        ensureConnection(world, fake);
        fake.world = world;
        fake.interactionManager.setWorld(world);
        if (fake.interactionManager.getGameType() == GameType.NOT_SET
                || fake.interactionManager.getGameType() == GameType.SPECTATOR) {
            fake.interactionManager.setGameType(GameType.SURVIVAL);
        }
        fake.setPositionAndRotation(automaton.posX, automaton.posY, automaton.posZ, automaton.rotationYaw, automaton.rotationPitch);
        fake.rotationYawHead = automaton.rotationYawHead;
        fake.inventory.currentItem = 0;
        ItemStack prevMain = fake.inventory.mainInventory.get(0);
        ItemStack nextMain = automaton.getHeldItemMainhand();
        if (!ItemStack.areItemStacksEqual(prevMain, nextMain)) {
            if (!prevMain.isEmpty()) {
                fake.getAttributeMap().removeAttributeModifiers(prevMain.getAttributeModifiers(EntityEquipmentSlot.MAINHAND));
            }
            if (!nextMain.isEmpty()) {
                fake.getAttributeMap().applyAttributeModifiers(nextMain.getAttributeModifiers(EntityEquipmentSlot.MAINHAND));
            }
        }
        fake.inventory.mainInventory.set(0, nextMain);

        ItemStack prevOff = fake.inventory.offHandInventory.get(0);
        ItemStack nextOff = automaton.getHeldItemOffhand();
        if (!ItemStack.areItemStacksEqual(prevOff, nextOff)) {
            if (!prevOff.isEmpty()) {
                fake.getAttributeMap().removeAttributeModifiers(prevOff.getAttributeModifiers(EntityEquipmentSlot.OFFHAND));
            }
            if (!nextOff.isEmpty()) {
                fake.getAttributeMap().applyAttributeModifiers(nextOff.getAttributeModifiers(EntityEquipmentSlot.OFFHAND));
            }
        }
        fake.inventory.offHandInventory.set(0, nextOff);

        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack prevArmor = fake.inventory.armorInventory.get(i);
            ItemStack nextArmor = automaton.getItemStackFromSlot(ARMOR_SLOTS[i]);
            if (!ItemStack.areItemStacksEqual(prevArmor, nextArmor)) {
                if (!prevArmor.isEmpty()) {
                    fake.getAttributeMap().removeAttributeModifiers(prevArmor.getAttributeModifiers(ARMOR_SLOTS[i]));
                }
                if (!nextArmor.isEmpty()) {
                    fake.getAttributeMap().applyAttributeModifiers(nextArmor.getAttributeModifiers(ARMOR_SLOTS[i]));
                }
            }
            fake.inventory.armorInventory.set(i, nextArmor);
        }
        fake.setSneaking(automaton.isSneaking());
        fake.setSprinting(automaton.isSprinting());
        fake.fallDistance = automaton.fallDistance;
        fake.motionX = automaton.motionX;
        fake.motionY = automaton.motionY;
        fake.motionZ = automaton.motionZ;
        fake.onGround = automaton.onGround;
        com.x4yi.automata.compat.BaublesCompat.syncToFakePlayer(automaton.getInventory(), fake);
        return fake;
    }

    public static void syncEquipmentBack(FakePlayer fake, EntityAutomaton automaton) {
        ItemStack fakeMain = fake.inventory.mainInventory.get(0);
        if (automaton.getHeldItemMainhand() != fakeMain) {
            automaton.setHeldItem(EnumHand.MAIN_HAND, fakeMain);
        }
        ItemStack fakeOff = fake.inventory.offHandInventory.get(0);
        if (automaton.getHeldItemOffhand() != fakeOff) {
            automaton.setHeldItem(EnumHand.OFF_HAND, fakeOff);
        }
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack fakeArmor = fake.inventory.armorInventory.get(i);
            if (automaton.getItemStackFromSlot(ARMOR_SLOTS[i]) != fakeArmor) {
                automaton.setItemStackToSlot(ARMOR_SLOTS[i], fakeArmor);
            }
        }
    }

    static boolean ensureConnection(WorldServer world, FakePlayer fake) {
        if (fake.connection != null) {
            return true;
        }
        MinecraftServer server = world.getMinecraftServer();
        if (server == null) {
            return false;
        }
        fake.connection = new NoOpNetHandler(server, fake);
        return true;
    }

    static boolean tryEnterReentrancyGuard() {
        int depth = REENTRANCY_DEPTH.get();
        if (depth > 0) {
            return false;
        }
        REENTRANCY_DEPTH.set(1);
        return true;
    }

    static void exitReentrancyGuard() {
        REENTRANCY_DEPTH.remove();
    }

    static <T> T executeWithGuard(
            String contextLabel,
            T fallback,
            java.util.function.Supplier<T> action) {
        if (!tryEnterReentrancyGuard()) {
            AutomataMod.LOGGER.warn("Skipped reentrant FakePlayer action '{}'", contextLabel);
            return fallback;
        }
        try {
            return action.get();
        } catch (Exception e) {
            AutomataMod.LOGGER.warn("FakePlayer action '{}' failed", contextLabel, e);
            return fallback;
        } finally {
            exitReentrancyGuard();
        }
    }

    private static <T> T runGuarded(
            WorldServer world,
            EntityAutomaton automaton,
            String actionName,
            T fallback,
            java.util.function.Function<FakePlayer, T> action) {
        if (!tryEnterReentrancyGuard()) {
            AutomataMod.LOGGER.warn(
                    "Skipped reentrant FakePlayer action '{} for automaton {} (#{}) at {}'",
                    actionName, automaton.getName(), automaton.getEntityId(), automaton.getPosition());
            return fallback;
        }
        try {
            FakePlayer fake = acquireSynced(world, automaton);
            if (!ensureConnection(world, fake)) {
                AutomataMod.LOGGER.warn(
                        "Aborted FakePlayer action '{} for automaton {} (#{})': missing NetHandlerPlayServer",
                        actionName, automaton.getName(), automaton.getEntityId());
                return fallback;
            }
            return action.apply(fake);
        } catch (Exception e) {
            AutomataMod.LOGGER.warn(
                    "FakePlayer action '{} for automaton {} (#{}) at {}' failed",
                    actionName, automaton.getName(), automaton.getEntityId(), automaton.getPosition(), e);
            return fallback;
        } finally {
            exitReentrancyGuard();
        }
    }

    private static final java.util.Map<Class<?>, Boolean> ITEM_TICK_LOGIC_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(128);

    private static boolean hasCustomItemTickLogic(ItemStack stack, boolean isArmor) {
        if (stack.isEmpty()) {
            return false;
        }
        net.minecraft.item.Item item = stack.getItem();
        Class<?> cls = item.getClass();
        Boolean cached = ITEM_TICK_LOGIC_CACHE.get(cls);
        if (cached != null) {
            return cached;
        }
        boolean hasCustom = hasMethodOverride(cls, "onUpdate", ItemStack.class, net.minecraft.world.World.class, Entity.class, int.class, boolean.class)
                || hasMethodOverride(cls, "func_77663_a", ItemStack.class, net.minecraft.world.World.class, Entity.class, int.class, boolean.class);
        if (!hasCustom && isArmor) {
            hasCustom = hasMethodOverride(cls, "onArmorTick", net.minecraft.world.World.class, net.minecraft.entity.player.EntityPlayer.class, ItemStack.class);
        }
        ITEM_TICK_LOGIC_CACHE.putIfAbsent(cls, hasCustom);
        return hasCustom;
    }

    private static boolean hasMethodOverride(Class<?> cls, String name, Class<?>... params) {
        try {
            java.lang.reflect.Method m = cls.getMethod(name, params);
            return m.getDeclaringClass() != net.minecraft.item.Item.class;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    public static void tickEquippedItemEffects(WorldServer world, EntityAutomaton automaton) {
        ItemStack main = automaton.getHeldItemMainhand();
        ItemStack off = automaton.getHeldItemOffhand();
        boolean hasBaubles = com.x4yi.automata.compat.BaublesCompat.isLoaded()
                && com.x4yi.automata.compat.BaublesCompat.hasAnyWornBauble(automaton.getInventory());
        boolean needsTick = hasBaubles || hasCustomItemTickLogic(main, false) || hasCustomItemTickLogic(off, false);
        if (!needsTick) {
            for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
                if (hasCustomItemTickLogic(automaton.getItemStackFromSlot(slot), true)) {
                    needsTick = true;
                    break;
                }
            }
        }
        if (!needsTick) {
            return;
        }
        boolean hasBadPotion = false;
        for (net.minecraft.potion.PotionEffect effect : automaton.getActivePotionEffects()) {
            if (effect != null && effect.getPotion().isBadEffect()) {
                hasBadPotion = true;
                break;
            }
        }
        final boolean sneakForArmorSetBonus = hasBadPotion;
        runGuarded(world, automaton, "tickEquippedItemEffects", null, fake -> {
            ItemStack syncedMain = fake.inventory.mainInventory.get(0);
            if (hasCustomItemTickLogic(syncedMain, false)) {
                syncedMain.getItem().onUpdate(syncedMain, world, fake, 0, true);
            }
            ItemStack syncedOff = fake.inventory.offHandInventory.get(0);
            if (hasCustomItemTickLogic(syncedOff, false)) {
                syncedOff.getItem().onUpdate(syncedOff, world, fake, 40, false);
            }
            boolean prevSneak = fake.isSneaking();
            if (sneakForArmorSetBonus) {
                fake.setSneaking(true);
            }
            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                ItemStack armor = fake.inventory.armorInventory.get(i);
                if (hasCustomItemTickLogic(armor, true)) {
                    armor.getItem().onArmorTick(world, fake, armor);
                }
            }
            if (sneakForArmorSetBonus) {
                fake.setSneaking(prevSneak);
            }
            com.x4yi.automata.compat.BaublesCompat.tickWornBaubles(automaton.getInventory(), fake);
            syncEquipmentBack(fake, automaton);
            return null;
        });
    }

    public static float processSrpArmorAdaptation(
            WorldServer world,
            EntityAutomaton automaton,
            net.minecraft.util.DamageSource source,
            float amount) {
        if (amount <= 0.0F || source == null) {
            return amount;
        }
        return runGuarded(world, automaton, "processSrpArmorAdaptation", amount, fake -> {
            float adapted = com.x4yi.automata.compat.SrpCompat.applyPlayerArmorAdaptation(fake, amount, source);
            syncEquipmentBack(fake, automaton);
            return adapted;
        });
    }

    public static boolean tryBreakBlock(WorldServer world, EntityAutomaton automaton, BlockPos pos) {
        if (!world.isBlockLoaded(pos) || world.isAirBlock(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getBlockHardness(world, pos) < 0.0F
                || com.x4yi.automata.ai.jobs.JobBlockSupport.isBlockBlacklisted(state)) {
            return false;
        }
        if (MinecraftForge.EVENT_BUS.post(new BotBreakBlockEvent(automaton, world, pos, state))) {
            return false;
        }
        return runGuarded(world, automaton, "tryBreakBlock", Boolean.FALSE, fake -> {
            boolean harvested = fake.interactionManager.tryHarvestBlock(pos);
            syncEquipmentBack(fake, automaton);
            if (harvested) {
                WorldCacheManager.invalidateBlock(world, pos);
            }
            return harvested;
        });
    }

    public static EnumActionResult tryUseItemOnBlock(
            WorldServer world,
            EntityAutomaton automaton,
            EnumHand hand,
            BlockPos pos,
            EnumFacing facing,
            float hitX,
            float hitY,
            float hitZ) {
        if (!world.isBlockLoaded(pos)) {
            return EnumActionResult.FAIL;
        }
        return runGuarded(world, automaton, "tryUseItemOnBlock", EnumActionResult.FAIL, fake -> {
            ItemStack stack = fake.getHeldItem(hand);
            if (stack.isEmpty()) {
                return EnumActionResult.PASS;
            }
            EnumActionResult result = fake.interactionManager.processRightClickBlock(
                    fake, world, stack, hand, pos, facing, hitX, hitY, hitZ);
            syncEquipmentBack(fake, automaton);
            if (result == EnumActionResult.SUCCESS) {
                WorldCacheManager.invalidateBlock(world, pos.offset(facing));
            }
            return result;
        });
    }

    @Nullable
    private static java.lang.reflect.Field ticksSinceLastSwingField;
    private static boolean swingFieldResolved;

    @Nullable
    private static java.lang.reflect.Field getSwingField() {
        if (!swingFieldResolved) {
            swingFieldResolved = true;
            ticksSinceLastSwingField = resolveSwingField();
        }
        return ticksSinceLastSwingField;
    }

    @Nullable
    private static java.lang.reflect.Field resolveSwingField() {
        for (String name : new String[] {"ticksSinceLastSwing", "field_184617_aD"}) {
            try {
                java.lang.reflect.Field field = net.minecraft.entity.EntityLivingBase.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                logSwingReflectionFailureOnce(t);
                return null;
            }
        }
        logSwingReflectionFailureOnce(new NoSuchFieldException("ticksSinceLastSwing / field_184617_aD"));
        return null;
    }

    private static void logSwingReflectionFailureOnce(Throwable cause) {
        if (!loggedSwingReflectionFailure) {
            loggedSwingReflectionFailure = true;
            AutomataMod.LOGGER.warn("Failed to access EntityLivingBase.ticksSinceLastSwing; FakePlayer attack cooldown sync disabled", cause);
        }
    }

    private static void applyAttackCooldownTicks(FakePlayer fake, int ticks) {
        java.lang.reflect.Field field = getSwingField();
        if (field == null) {
            return;
        }
        try {
            field.setInt(fake, ticks);
        } catch (IllegalAccessException e) {
            logSwingReflectionFailureOnce(e);
        }
    }

    public static void attackEntity(WorldServer world, EntityAutomaton automaton, @Nullable Entity target) {
        if (target == null || target.isDead) {
            return;
        }
        if (MinecraftForge.EVENT_BUS.post(new BotAttackEvent(automaton, world, target))) {
            return;
        }
        runGuarded(world, automaton, "attackEntity", null, fake -> {
            boolean isCrit = !automaton.onGround && automaton.fallDistance > 0.0F;
            fake.setSprinting(!isCrit);
            applyAttackCooldownTicks(fake, automaton.getTicksSinceLastAttack());
            fake.attackTargetEntityWithCurrentItem(target);
            if (target instanceof net.minecraft.entity.EntityLivingBase livingTarget) {
                if (livingTarget.getRevengeTarget() == fake) {
                    livingTarget.setRevengeTarget(automaton);
                }
                if (livingTarget instanceof net.minecraft.entity.EntityLiving mob && mob.getAttackTarget() == fake) {
                    mob.setAttackTarget(automaton);
                }
            }
            automaton.resetAttackCooldown();
            syncEquipmentBack(fake, automaton);
            return null;
        });
    }

    public static ItemStack consumeFoodLikePlayer(WorldServer world, EntityAutomaton automaton, ItemStack foodStack) {
        if (foodStack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return runGuarded(world, automaton, "consumeFoodLikePlayer", foodStack, fake -> {
            fake.clearActivePotions();
            float prevHealth = automaton.getHealth();
            fake.setHealth(prevHealth);
            net.minecraft.nbt.NBTTagCompound foodNbt = new net.minecraft.nbt.NBTTagCompound();
            fake.getFoodStats().writeNBT(foodNbt);
            foodNbt.setInteger("foodLevel", Math.round(automaton.getHunger()));
            foodNbt.setFloat("foodSaturationLevel", automaton.getSaturation());
            fake.getFoodStats().readNBT(foodNbt);

            ItemStack result = foodStack.onItemUseFinish(world, fake);

            automaton.setHunger(fake.getFoodStats().getFoodLevel());
            automaton.setSaturation(fake.getFoodStats().getSaturationLevel());
            if (fake.getHealth() > prevHealth) {
                automaton.heal(fake.getHealth() - prevHealth);
            }
            for (net.minecraft.potion.PotionEffect effect : fake.getActivePotionEffects()) {
                automaton.addPotionEffect(new net.minecraft.potion.PotionEffect(effect));
            }
            fake.clearActivePotions();
            return result;
        });
    }

    public static boolean releaseBowShot(
            WorldServer world,
            EntityAutomaton automaton,
            net.minecraftforge.items.IItemHandlerModifiable inv,
            int arrowSlot,
            int chargeTicks) {
        ItemStack bow = automaton.getHeldItemMainhand();
        if (bow.isEmpty()) {
            return false;
        }
        return runGuarded(world, automaton, "releaseBowShot", Boolean.FALSE, fake -> {
            fake.motionX = 0.0D;
            fake.motionY = 0.0D;
            fake.motionZ = 0.0D;
            ItemStack arrowBackup = ItemStack.EMPTY;
            if (arrowSlot >= 0 && arrowSlot < inv.getSlots()) {
                arrowBackup = inv.getStackInSlot(arrowSlot).copy();
                fake.inventory.mainInventory.set(1, arrowBackup);
            } else if (com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesSelfAmmo(bow)) {
                fake.inventory.mainInventory.set(0, bow);
            }
            int timeLeft = Math.max(0, bow.getMaxItemUseDuration() - chargeTicks);
            if (com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesTickRangedWeapon(bow)) {
                bow.getItem().onUsingTick(bow, fake, timeLeft);
            } else {
                bow.onPlayerStoppedUsing(world, fake, timeLeft);
            }
            rebindSpawnedProjectiles(world, fake, automaton);
            if (arrowSlot >= 0 && arrowSlot < inv.getSlots()) {
                ItemStack afterArrow = fake.inventory.mainInventory.get(1);
                int consumed = Math.max(0, arrowBackup.getCount() - afterArrow.getCount());
                if (consumed > 0) {
                    inv.extractItem(arrowSlot, consumed, false);
                }
                fake.inventory.mainInventory.set(1, ItemStack.EMPTY);
            }
            syncEquipmentBack(fake, automaton);
            return true;
        });
    }

    public static boolean fireOrLoadCrossbow(
            WorldServer world,
            EntityAutomaton automaton,
            net.minecraftforge.items.IItemHandlerModifiable inv,
            int boltSlot,
            int chargeTicks) {
        ItemStack crossbow = automaton.getHeldItemMainhand();
        if (crossbow.isEmpty()) {
            return false;
        }
        return runGuarded(world, automaton, "fireOrLoadCrossbow", Boolean.FALSE, fake -> {
            fake.motionX = 0.0D;
            fake.motionY = 0.0D;
            fake.motionZ = 0.0D;
            boolean alreadyLoaded = com.x4yi.automata.compat.SpartanWeaponryCompat.isCrossbowLoaded(crossbow);
            if (alreadyLoaded) {
                int aimTicks = Math.max(chargeTicks, com.x4yi.automata.compat.SpartanWeaponryCompat.getCrossbowAimTicks(crossbow));
                int timeLeft = Math.max(0, crossbow.getMaxItemUseDuration() - aimTicks);
                crossbow.onPlayerStoppedUsing(world, fake, timeLeft);
                rebindSpawnedProjectiles(world, fake, automaton);
                syncEquipmentBack(fake, automaton);
                return true;
            }
            ItemStack boltBackup = ItemStack.EMPTY;
            if (boltSlot >= 0 && boltSlot < inv.getSlots()) {
                boltBackup = inv.getStackInSlot(boltSlot).copy();
                fake.inventory.mainInventory.set(1, boltBackup);
            }
            ItemStack finished = crossbow.onItemUseFinish(world, fake);
            if (!finished.isEmpty()) {
                fake.setHeldItem(EnumHand.MAIN_HAND, finished);
            }
            if (boltSlot >= 0 && boltSlot < inv.getSlots()) {
                ItemStack afterBolt = fake.inventory.mainInventory.get(1);
                int consumed = Math.max(0, boltBackup.getCount() - afterBolt.getCount());
                if (consumed > 0) {
                    inv.extractItem(boltSlot, consumed, false);
                }
                fake.inventory.mainInventory.set(1, ItemStack.EMPTY);
            }
            syncEquipmentBack(fake, automaton);
            return true;
        });
    }

    public static boolean pickupThrownProjectileAsFakePlayer(
            WorldServer world,
            EntityAutomaton automaton,
            net.minecraft.entity.projectile.EntityArrow thrown) {
        if (thrown == null || thrown.isDead) {
            return false;
        }
        return runGuarded(world, automaton, "pickupThrownProjectile", Boolean.FALSE, fake -> {
            fake.setPosition(automaton.posX, automaton.posY, automaton.posZ);
            net.minecraftforge.items.IItemHandlerModifiable inv = automaton.getInventory().getCombinedInventory();
            int copySlots = Math.min(inv.getSlots(), fake.inventory.mainInventory.size() - 1);
            for (int i = 0; i < copySlots; i++) {
                fake.inventory.mainInventory.set(i + 1, inv.getStackInSlot(i).copy());
            }
            thrown.onCollideWithPlayer(fake);
            for (int i = 0; i < copySlots; i++) {
                inv.setStackInSlot(i, fake.inventory.mainInventory.get(i + 1).copy());
                fake.inventory.mainInventory.set(i + 1, ItemStack.EMPTY);
            }
            syncEquipmentBack(fake, automaton);
            return thrown.isDead;
        });
    }

    private static void rebindSpawnedProjectiles(WorldServer world, FakePlayer fake, EntityAutomaton automaton) {
        net.minecraft.util.math.AxisAlignedBB box = automaton.getEntityBoundingBox().grow(3.0D);
        for (net.minecraft.entity.projectile.EntityArrow arrow : world.getEntitiesWithinAABB(
                net.minecraft.entity.projectile.EntityArrow.class, box)) {
            if (arrow.shootingEntity == fake) {
                arrow.shootingEntity = automaton;
                com.x4yi.automata.compat.SpartanWeaponryCompat.tagProjectileOwner(arrow, automaton);
            }
        }
        for (net.minecraft.entity.projectile.EntityThrowable proj : world.getEntitiesWithinAABB(
                net.minecraft.entity.projectile.EntityThrowable.class, box)) {
            if (proj.getThrower() == fake) {
                try {
                    java.lang.reflect.Field throwerField = net.minecraft.entity.projectile.EntityThrowable.class
                            .getDeclaredField("thrower");
                    throwerField.setAccessible(true);
                    throwerField.set(proj, automaton);
                } catch (Throwable ignored) {
                    try {
                        java.lang.reflect.Field obfField = net.minecraft.entity.projectile.EntityThrowable.class
                                .getDeclaredField("field_70192_c");
                        obfField.setAccessible(true);
                        obfField.set(proj, automaton);
                    } catch (Throwable ignored2) {
                    }
                }
            }
        }
    }

    private static final class NoOpNetHandler extends NetHandlerPlayServer {

        NoOpNetHandler(MinecraftServer server, FakePlayer player) {
            super(server, new NetworkManager(EnumPacketDirection.SERVERBOUND), player);
        }

        @Override
        public void sendPacket(Packet<?> packetIn) {
        }

        @Override
        public void disconnect(ITextComponent textComponent) {
        }

        @Override
        public void update() {
        }
    }
}
