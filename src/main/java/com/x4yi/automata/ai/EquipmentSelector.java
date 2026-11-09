package com.x4yi.automata.ai;

import java.util.Collection;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.init.MobEffects;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemBucketMilk;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemShield;
import net.minecraft.item.ItemSplashPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.PotionEffect;
import net.minecraft.potion.PotionUtils;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.entity.EntityAutomaton;

public final class EquipmentSelector {

    private static final int MIN_DURABILITY_RESERVE = 3;

    private EquipmentSelector() {}

    public static boolean hasSafeDurability(ItemStack stack) {
        if (stack.isEmpty() || !stack.isItemStackDamageable()) {
            return true;
        }
        return (stack.getMaxDamage() - stack.getItemDamage()) > MIN_DURABILITY_RESERVE;
    }

    public static boolean isShieldItem(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof ItemShield || stack.getItemUseAction() == EnumAction.BLOCK);
    }

    public static boolean isBowItem(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof ItemBow || stack.getItemUseAction() == EnumAction.BOW);
    }

    public static void equipBestToolFor(EntityAutomaton automaton, IBlockState targetState) {
        equipBestToolFor(automaton, null, null, targetState);
    }

    public static void equipBestToolFor(
            EntityAutomaton automaton,
            @Nullable WorldServer world,
            @Nullable BlockPos pos,
            IBlockState targetState) {
        IItemHandlerModifiable inv = automaton.getInventory().getCombinedInventory();
        ItemStack current = automaton.getHeldItemMainhand();
        float bestScore = scoreToolForBlock(current, world, pos, targetState);
        int bestSlot = -1;

        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (candidate.isEmpty()) {
                continue;
            }
            float score = scoreToolForBlock(candidate, world, pos, targetState);
            if (score > bestScore + 0.01F) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            swapMainHandWithSlot(automaton, inv, bestSlot);
        }
    }

    public static float scoreToolForBlock(
            ItemStack stack,
            @Nullable WorldServer world,
            @Nullable BlockPos pos,
            IBlockState state) {
        if (stack.isEmpty()) {
            return 10.0F;
        }
        float speed = stack.getDestroySpeed(state);
        boolean effective = false;
        if (world != null && pos != null) {
            effective = ForgeHooks.isToolEffective(world, pos, stack);
        }
        String harvestTool = state.getBlock().getHarvestTool(state);
        if (!effective && harvestTool != null && stack.getItem().getToolClasses(stack).contains(harvestTool)) {
            effective = true;
        }
        if (!effective && stack.canHarvestBlock(state) && speed > 1.0F) {
            effective = true;
        }

        float score = speed * 10.0F;
        if (effective) {
            score += 1000.0F;
        } else if (stack.getItem() instanceof ItemSword || isHoe(stack)) {
            score -= 50.0F;
        }
        if (stack.isItemStackDamageable() && stack.getMaxDamage() - stack.getItemDamage() <= 1) {
            score -= 200.0F;
        }
        return score;
    }

    public static float getArmorScore(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemArmor armor)) {
            return 0.0F;
        }
        return armor.damageReduceAmount * 10.0F + armor.toughness * 4.0F;
    }

    public static void equipBestWeapon(EntityAutomaton automaton) {
        IItemHandlerModifiable inv = automaton.getInventory().getCombinedInventory();
        ItemStack current = automaton.getHeldItemMainhand();
        boolean currentSafe = hasSafeDurability(current);
        double bestScore = currentSafe ? scoreMeleeWeapon(current) : -1.0D;
        int bestSlot = -1;

        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (candidate.isEmpty() || !hasSafeDurability(candidate)) {
                continue;
            }
            double score = scoreMeleeWeapon(candidate);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            swapMainHandWithSlot(automaton, inv, bestSlot);
        }
    }

    private static double scoreMeleeWeapon(ItemStack stack) {
        if (com.x4yi.automata.compat.SpartanWeaponryCompat.isBoomerang(stack)
                || com.x4yi.automata.compat.SpartanWeaponryCompat.isCrossbow(stack)
                || com.x4yi.automata.compat.SpartanWeaponryCompat.isThrowableWeapon(stack)) {
            return 1.0D;
        }
        return getAttackDamage(stack) + com.x4yi.automata.compat.SpartanWeaponryCompat.getReachBonus(stack) * 1.25D;
    }

    public static int findHealingOrCureConsumableSlot(
            EntityAutomaton automaton,
            boolean needsHeal,
            boolean hasNegativeEffect) {
        if (!needsHeal && !hasNegativeEffect) {
            return -1;
        }
        IItemHandlerModifiable inv = automaton.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (isHealingOrCureConsumable(candidate, needsHeal, hasNegativeEffect)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isHealingOrCureConsumable(
            ItemStack stack,
            boolean needsHeal,
            boolean hasNegativeEffect) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (hasNegativeEffect && item instanceof ItemBucketMilk) {
            return true;
        }
        if (item instanceof ItemPotion && !(item instanceof ItemSplashPotion)) {
            for (PotionEffect effect : PotionUtils.getEffectsFromStack(stack)) {
                if (needsHeal
                        && (effect.getPotion() == MobEffects.INSTANT_HEALTH
                                || effect.getPotion() == MobEffects.REGENERATION
                                || effect.getPotion() == MobEffects.ABSORPTION)) {
                    return true;
                }
                if (!effect.getPotion().isBadEffect() && needsHeal) {
                    return true;
                }
            }
            return false;
        }
        EnumAction action = stack.getItemUseAction();
        if (action == EnumAction.DRINK || action == EnumAction.EAT) {
            ResourceLocation key = item.getRegistryName();
            if (isModdedConsumableId(key, needsHeal, hasNegativeEffect)) {
                return true;
            }
            if (needsHeal && item instanceof ItemFood) {
                for (PotionEffect effect : PotionUtils.getEffectsFromStack(stack)) {
                    if (!effect.getPotion().isBadEffect()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean isModdedConsumableId(
            ResourceLocation key,
            boolean needsHeal,
            boolean hasNegativeEffect) {
        if (key == null) {
            return false;
        }
        String path = key.getPath().toLowerCase(Locale.ROOT);
        if (hasNegativeEffect && (path.contains("milk") || path.contains("antidote") || path.contains("cure"))) {
            return true;
        }
        if (needsHeal
                && (path.contains("heal")
                        || path.contains("medkit")
                        || path.contains("bandage")
                        || path.contains("elixir")
                        || path.contains("potion"))) {
            return true;
        }
        return false;
    }

    public static boolean equipBuildingBlock(EntityAutomaton automaton) {
        ItemStack current = automaton.getHeldItemMainhand();
        if (com.x4yi.automata.movement.BlockPlacementService.isValidScaffoldBlock(current)) {
            return true;
        }
        IItemHandlerModifiable inv = automaton.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (com.x4yi.automata.movement.BlockPlacementService.isValidScaffoldBlock(candidate)) {
                swapMainHandWithSlot(automaton, inv, i);
                return true;
            }
        }
        return false;
    }

    public static boolean hasBuildingBlocks(EntityAutomaton automaton) {
        return countScaffoldBlocks(automaton) > 0;
    }

    public static int countScaffoldBlocks(EntityAutomaton automaton) {
        int total = 0;
        ItemStack current = automaton.getHeldItemMainhand();
        if (com.x4yi.automata.movement.BlockPlacementService.isValidScaffoldBlock(current)) {
            total += current.getCount();
        }
        IItemHandlerModifiable inv = automaton.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (com.x4yi.automata.movement.BlockPlacementService.isValidScaffoldBlock(candidate)) {
                total += candidate.getCount();
            }
        }
        return total;
    }

    private static double getAttackDamage(ItemStack stack) {
        if (stack.isEmpty()) {
            return 1.0D;
        }
        double total = 1.0D;
        Collection<AttributeModifier> modifiers =
                stack.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                        .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName());
        for (AttributeModifier mod : modifiers) {
            total += mod.getAmount();
        }
        return total;
    }

    public static boolean isHoe(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof ItemHoe
                || stack.getItem().getToolClasses(stack).contains("hoe");
    }

    public static boolean isAxe(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof ItemAxe
                || stack.getItem().getToolClasses(stack).contains("axe");
    }

    public static boolean isPickaxe(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof net.minecraft.item.ItemPickaxe
                || stack.getItem().getToolClasses(stack).contains("pickaxe")
                || stack.getItem().getHarvestLevel(stack, "pickaxe", null, null) >= 0;
    }

    public static boolean isExcavationTool(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return isPickaxe(stack)
                || stack.getItem() instanceof net.minecraft.item.ItemSpade
                || stack.getItem().getToolClasses(stack).contains("shovel")
                || stack.getItem().getHarvestLevel(stack, "shovel", null, null) >= 0;
    }

    private static void swapMainHandWithSlot(EntityAutomaton automaton, IItemHandlerModifiable inv, int slot) {
        ItemStack current = automaton.getHeldItemMainhand().copy();
        ItemStack chosen = inv.extractItem(slot, inv.getStackInSlot(slot).getCount(), false);
        automaton.setHeldItem(EnumHand.MAIN_HAND, chosen);
        if (!current.isEmpty()) {
            ItemStack remainder = inv.insertItem(slot, current, false);
            if (!remainder.isEmpty()) {
                for (int i = 0; i < inv.getSlots() && !remainder.isEmpty(); i++) {
                    remainder = inv.insertItem(i, remainder, false);
                }
                if (!remainder.isEmpty()) {
                    automaton.entityDropItem(remainder, 0.0F);
                }
            }
        }
    }
}
