package com.x4yi.automata.ai;

import java.util.Collection;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemShield;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.entity.EntityAutomaton;

public final class EquipmentSelector {

    private EquipmentSelector() {}

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
        double bestDamage = getAttackDamage(current);
        int bestSlot = -1;

        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (candidate.isEmpty()) {
                continue;
            }
            double damage = getAttackDamage(candidate);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            swapMainHandWithSlot(automaton, inv, bestSlot);
        }
    }

    public static boolean equipBuildingBlock(EntityAutomaton automaton) {
        ItemStack current = automaton.getHeldItemMainhand();
        if (!current.isEmpty() && current.getItem() instanceof ItemBlock) {
            return true;
        }
        IItemHandlerModifiable inv = automaton.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (!candidate.isEmpty() && candidate.getItem() instanceof ItemBlock) {
                swapMainHandWithSlot(automaton, inv, i);
                return true;
            }
        }
        return false;
    }

    public static boolean hasBuildingBlocks(EntityAutomaton automaton) {
        ItemStack current = automaton.getHeldItemMainhand();
        if (!current.isEmpty() && current.getItem() instanceof ItemBlock) {
            return true;
        }
        IItemHandlerModifiable inv = automaton.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (!candidate.isEmpty() && candidate.getItem() instanceof ItemBlock) {
                return true;
            }
        }
        return false;
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
