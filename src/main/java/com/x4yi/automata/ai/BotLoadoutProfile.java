package com.x4yi.automata.ai;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.ai.jobs.RangedCombatController;
import com.x4yi.automata.ai.jobs.WeaponCombatProfile;
import com.x4yi.automata.compat.SrpCompat;
import com.x4yi.automata.entity.EntityAutomaton;

public record BotLoadoutProfile(
        int bestMeleeSlot,
        int secondaryMeleeSlot,
        double bestMeleeDamage,
        boolean hasReadyRangedWeapon,
        boolean hasBookOfVengeance,
        boolean hasUsableShield,
        boolean hasHealingConsumable,
        boolean hasEnderPearl,
        WeaponCombatProfile mainHandProfile) {

    public BotLoadoutProfile(
            int bestMeleeSlot,
            int secondaryMeleeSlot,
            double bestMeleeDamage,
            boolean hasReadyRangedWeapon,
            boolean hasBookOfVengeance,
            boolean hasUsableShield,
            boolean hasHealingConsumable,
            boolean hasEnderPearl) {
        this(
                bestMeleeSlot,
                secondaryMeleeSlot,
                bestMeleeDamage,
                hasReadyRangedWeapon,
                hasBookOfVengeance,
                hasUsableShield,
                hasHealingConsumable,
                hasEnderPearl,
                WeaponCombatProfile.resolve(ItemStack.EMPTY));
    }

    public boolean hasAnyCombatWeapon() {
        return bestMeleeSlot >= 0 || hasReadyRangedWeapon || hasBookOfVengeance;
    }

    public boolean isUnarmedOrSupportOnly() {
        return !hasAnyCombatWeapon();
    }

    public boolean hasAntiAdaptationFallback() {
        return secondaryMeleeSlot >= 0 || hasReadyRangedWeapon || hasBookOfVengeance;
    }

    public static BotLoadoutProfile scan(EntityAutomaton bot) {
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        int bestSlot = -1;
        double bestScore = -1.0D;
        double bestDmg = 1.0D;
        Item bestItem = null;

        int secondSlot = -1;
        double secondScore = -1.0D;

        boolean hasBook = SrpCompat.isBookOfVengeance(bot.getHeldItemMainhand());
        boolean hasShield = EquipmentSelector.isShieldItem(bot.getHeldItemOffhand())
                && EquipmentSelector.hasSafeDurability(bot.getHeldItemOffhand());
        boolean hasHeal = false;
        boolean hasPearl = false;

        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (SrpCompat.isBookOfVengeance(stack)) {
                hasBook = true;
            }
            if (!hasShield && EquipmentSelector.isShieldItem(stack) && EquipmentSelector.hasSafeDurability(stack)) {
                hasShield = true;
            }
            if (!hasPearl && stack.getItem() == Items.ENDER_PEARL) {
                hasPearl = true;
            }
            if (!hasHeal && EquipmentSelector.isHealingOrCureConsumable(stack, true, false)) {
                hasHeal = true;
            }
            if (!SrpCompat.isBookOfVengeance(stack)
                    && EquipmentSelector.isCombatWeapon(stack)
                    && EquipmentSelector.hasSafeDurability(stack)
                    && !EquipmentSelector.isBowItem(stack)) {
                double score = EquipmentSelector.scoreMeleeWeapon(stack);
                if (score > bestScore) {
                    if (bestSlot >= 0 && stack.getItem() != bestItem) {
                        secondScore = bestScore;
                        secondSlot = bestSlot;
                    }
                    bestScore = score;
                    bestSlot = i;
                    bestDmg = Math.max(1.0D, EquipmentSelector.getAttackDamage(stack));
                    bestItem = stack.getItem();
                } else if (score > secondScore && stack.getItem() != bestItem) {
                    secondScore = score;
                    secondSlot = i;
                }
            }
        }

        boolean readyRanged = RangedCombatController.hasReadyRangedWeapon(bot);
        WeaponCombatProfile mainProfile = WeaponCombatProfile.resolve(bot.getHeldItemMainhand());
        return new BotLoadoutProfile(
                bestSlot,
                secondSlot,
                bestDmg,
                readyRanged,
                hasBook,
                hasShield,
                hasHeal,
                hasPearl,
                mainProfile);
    }
}
