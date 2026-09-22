/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.WorldCacheManager;

/**
 * Synchronizes a pooled {@link FakePlayer} with {@link EntityAutomaton} for world interactions and modded item parity.
 */
public final class FakePlayerService {

    private static final UUID DEFAULT_PROFILE_ID = UUID.fromString("6a4c5e9d-1b2a-4f3c-9d8e-7a6b5c4d3e2f");
    private static final String FIXED_PROFILE_NAME = "[Automata]";
    private static final EntityEquipmentSlot[] ARMOR_SLOTS = {
            EntityEquipmentSlot.FEET,
            EntityEquipmentSlot.LEGS,
            EntityEquipmentSlot.CHEST,
            EntityEquipmentSlot.HEAD
    };

    private FakePlayerService() {}

    public static boolean isAutomataFakePlayer(FakePlayer fake) {
        return FIXED_PROFILE_NAME.equals(fake.getName());
    }

    public static FakePlayer acquireSynced(WorldServer world, EntityAutomaton automaton) {
        UUID profileId = automaton.getOwnerUuid() != null ? automaton.getOwnerUuid() : DEFAULT_PROFILE_ID;
        FakePlayer fake = FakePlayerFactory.get(world, new GameProfile(profileId, FIXED_PROFILE_NAME));
        if (fake.connection == null && world.getMinecraftServer() != null) {
            fake.connection = new NoOpNetHandler(world.getMinecraftServer(), fake);
        }
        fake.world = world;
        fake.interactionManager.setWorld(world);
        if (fake.interactionManager.getGameType() == GameType.NOT_SET
                || fake.interactionManager.getGameType() == GameType.SPECTATOR) {
            fake.interactionManager.setGameType(GameType.SURVIVAL);
        }
        fake.setPositionAndRotation(automaton.posX, automaton.posY, automaton.posZ, automaton.rotationYaw, automaton.rotationPitch);
        fake.rotationYawHead = automaton.rotationYawHead;
        fake.inventory.currentItem = 0;
        fake.inventory.mainInventory.set(0, automaton.getHeldItemMainhand());
        fake.inventory.offHandInventory.set(0, automaton.getHeldItemOffhand());
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            fake.inventory.armorInventory.set(i, automaton.getItemStackFromSlot(ARMOR_SLOTS[i]));
        }
        fake.setSneaking(automaton.isSneaking());
        fake.setSprinting(automaton.isSprinting());
        fake.fallDistance = automaton.fallDistance;
        fake.motionX = automaton.motionX;
        fake.motionY = automaton.motionY;
        fake.motionZ = automaton.motionZ;
        fake.onGround = automaton.onGround;
        return fake;
    }

    public static void syncEquipmentBack(FakePlayer fake, EntityAutomaton automaton) {
        automaton.setHeldItem(EnumHand.MAIN_HAND, fake.inventory.mainInventory.get(0));
        automaton.setHeldItem(EnumHand.OFF_HAND, fake.inventory.offHandInventory.get(0));
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            automaton.setItemStackToSlot(ARMOR_SLOTS[i], fake.inventory.armorInventory.get(i));
        }
    }

    public static void tickEquippedItemEffects(WorldServer world, EntityAutomaton automaton) {
        ItemStack main = automaton.getHeldItemMainhand();
        ItemStack off = automaton.getHeldItemOffhand();
        boolean hasArmor = false;
        for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
            if (!automaton.getItemStackFromSlot(slot).isEmpty()) {
                hasArmor = true;
                break;
            }
        }
        if (main.isEmpty() && off.isEmpty() && !hasArmor) {
            return;
        }
        FakePlayer fake = acquireSynced(world, automaton);
        ItemStack syncedMain = fake.inventory.mainInventory.get(0);
        if (!syncedMain.isEmpty()) {
            syncedMain.getItem().onUpdate(syncedMain, world, fake, 0, true);
        }
        ItemStack syncedOff = fake.inventory.offHandInventory.get(0);
        if (!syncedOff.isEmpty()) {
            syncedOff.getItem().onUpdate(syncedOff, world, fake, 40, false);
        }
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack armor = fake.inventory.armorInventory.get(i);
            if (!armor.isEmpty()) {
                armor.getItem().onArmorTick(world, fake, armor);
            }
        }
        syncEquipmentBack(fake, automaton);
    }

    public static boolean tryBreakBlock(WorldServer world, EntityAutomaton automaton, BlockPos pos) {
        if (!world.isBlockLoaded(pos) || world.isAirBlock(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getBlockHardness(world, pos) < 0.0F) {
            return false;
        }
        FakePlayer fake = acquireSynced(world, automaton);
        boolean harvested = fake.interactionManager.tryHarvestBlock(pos);
        syncEquipmentBack(fake, automaton);
        if (harvested) {
            WorldCacheManager.invalidateBlock(world, pos);
        }
        return harvested;
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
        FakePlayer fake = acquireSynced(world, automaton);
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
    }

    private static final java.lang.reflect.Field TICKS_SINCE_LAST_SWING_FIELD = resolveSwingField();

    @Nullable
    private static java.lang.reflect.Field resolveSwingField() {
        try {
            return net.minecraftforge.fml.relauncher.ReflectionHelper.findField(
                    net.minecraft.entity.EntityLivingBase.class, "ticksSinceLastSwing", "field_184617_aD");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void applyAttackCooldownTicks(FakePlayer fake, int ticks) {
        if (TICKS_SINCE_LAST_SWING_FIELD == null) {
            return;
        }
        try {
            TICKS_SINCE_LAST_SWING_FIELD.setInt(fake, ticks);
        } catch (IllegalAccessException ignored) {
        }
    }

    public static void attackEntity(WorldServer world, EntityAutomaton automaton, @Nullable Entity target) {
        if (target == null || target.isDead) {
            return;
        }
        FakePlayer fake = acquireSynced(world, automaton);
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
    }

    public static ItemStack consumeFoodLikePlayer(WorldServer world, EntityAutomaton automaton, ItemStack foodStack) {
        if (foodStack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        FakePlayer fake = acquireSynced(world, automaton);
        fake.clearActivePotions();
        float prevHealth = automaton.getHealth();
        fake.setHealth(prevHealth);
        fake.getFoodStats().setFoodLevel(Math.round(automaton.getHunger()));
        fake.getFoodStats().setFoodSaturationLevel(automaton.getSaturation());

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
        FakePlayer fake = acquireSynced(world, automaton);
        fake.motionX = 0.0D;
        fake.motionY = 0.0D;
        fake.motionZ = 0.0D;
        ItemStack arrowBackup = ItemStack.EMPTY;
        if (arrowSlot >= 0 && arrowSlot < inv.getSlots()) {
            arrowBackup = inv.getStackInSlot(arrowSlot).copy();
            fake.inventory.mainInventory.set(1, arrowBackup);
        }
        int timeLeft = Math.max(0, bow.getMaxItemUseDuration() - chargeTicks);
        bow.onPlayerStoppedUsing(world, fake, timeLeft);
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
    }

    private static void rebindSpawnedProjectiles(WorldServer world, FakePlayer fake, EntityAutomaton automaton) {
        net.minecraft.util.math.AxisAlignedBB box = automaton.getEntityBoundingBox().grow(2.0D);
        for (net.minecraft.entity.projectile.EntityArrow arrow : world.getEntitiesWithinAABB(
                net.minecraft.entity.projectile.EntityArrow.class, box)) {
            if (arrow.shootingEntity == fake) {
                arrow.shootingEntity = automaton;
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
