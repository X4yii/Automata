package com.x4yi.automata.ai.jobs;

import java.util.Locale;

import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.compat.SpartanWeaponryCompat;

/**
 * Resolves a data-driven combat profile for vanilla, Spartan Weaponry, and
 * generic modded weapons based on reach, attack speed, and weapon properties.
 */
public final class WeaponCombatProfile {

    public enum WeaponCombatStyle {
        STANDARD_MELEE,
        DUELIST_AGGRESSIVE,
        POLEARM_REACH,
        HEAVY_BREAKER,
        BOOMERANG_SKIRMISHER,
        THROWING_SKIRMISHER,
        CROSSBOW_MARKSMAN,
        BOW_MARKSMAN
    }

    private static final double BASE_MELEE_REACH = 3.0D;

    private final WeaponCombatStyle style;
    private final double attackRange;
    private final double attackRangeSq;
    private final double idealMinRangeSq;
    private final boolean preferSprintHits;
    private final boolean preferCrits;
    private final long rangedChargeTicks;

    private WeaponCombatProfile(
            WeaponCombatStyle style,
            double attackRange,
            double idealMinRange,
            boolean preferSprintHits,
            boolean preferCrits,
            long rangedChargeTicks) {
        this.style = style;
        this.attackRange = attackRange;
        this.attackRangeSq = attackRange * attackRange;
        this.idealMinRangeSq = idealMinRange * idealMinRange;
        this.preferSprintHits = preferSprintHits;
        this.preferCrits = preferCrits;
        this.rangedChargeTicks = rangedChargeTicks;
    }

    public WeaponCombatStyle getStyle() {
        return style;
    }

    public double getAttackRange() {
        return attackRange;
    }

    public double getAttackRangeSq() {
        return attackRangeSq;
    }

    public double getIdealMinRangeSq() {
        return idealMinRangeSq;
    }

    public boolean prefersSprintHits() {
        return preferSprintHits;
    }

    public boolean prefersCrits() {
        return preferCrits;
    }

    public long getRangedChargeTicks() {
        return rangedChargeTicks;
    }

    public boolean isRangedSkirmishStyle() {
        return style == WeaponCombatStyle.BOOMERANG_SKIRMISHER
                || style == WeaponCombatStyle.THROWING_SKIRMISHER
                || style == WeaponCombatStyle.CROSSBOW_MARKSMAN;
    }

    public static WeaponCombatProfile resolve(ItemStack stack) {
        if (stack.isEmpty()) {
            return new WeaponCombatProfile(WeaponCombatStyle.STANDARD_MELEE, BASE_MELEE_REACH, 0.0D, false, false, 20L);
        }
        if (SpartanWeaponryCompat.isBoomerang(stack)) {
            return new WeaponCombatProfile(WeaponCombatStyle.BOOMERANG_SKIRMISHER, BASE_MELEE_REACH, 10.0D, false, false, 12L);
        }
        if (SpartanWeaponryCompat.isCrossbow(stack)) {
            return new WeaponCombatProfile(WeaponCombatStyle.CROSSBOW_MARKSMAN, BASE_MELEE_REACH, 16.0D, false, false, 25L);
        }
        if (stack.getItem() instanceof ItemBow || stack.getItemUseAction() == EnumAction.BOW) {
            return new WeaponCombatProfile(WeaponCombatStyle.BOW_MARKSMAN, BASE_MELEE_REACH, 16.0D, false, false, 20L);
        }
        if (SpartanWeaponryCompat.isThrowableWeapon(stack)) {
            ResourceLocation key = stack.getItem().getRegistryName();
            String path = key != null ? key.getPath().toLowerCase(Locale.ROOT) : "";
            long charge = path.contains("knife") ? 8L : 14L;
            return new WeaponCombatProfile(WeaponCombatStyle.THROWING_SKIRMISHER, BASE_MELEE_REACH, 8.0D, false, false, charge);
        }

        double reachBonus = SpartanWeaponryCompat.getReachBonus(stack);
        double speedMod = SpartanWeaponryCompat.getAttackSpeedModifier(stack);
        boolean twoHanded = SpartanWeaponryCompat.hasWeaponProperty(stack, "two_handed");
        boolean extraDamage = SpartanWeaponryCompat.hasWeaponProperty(stack, "extra_damage")
                || SpartanWeaponryCompat.hasWeaponProperty(stack, "damage_bonus");

        return fromStats(reachBonus, speedMod, twoHanded, extraDamage);
    }

    static WeaponCombatProfile fromStats(
            double reachBonus,
            double speedMod,
            boolean twoHanded,
            boolean extraDamage) {
        double effectiveReach = BASE_MELEE_REACH + Math.max(0.0D, reachBonus);
        if (reachBonus >= 0.75D) {
            double keepAway = Math.max(2.0D, effectiveReach - 1.65D);
            return new WeaponCombatProfile(WeaponCombatStyle.POLEARM_REACH, effectiveReach, keepAway, speedMod > -2.6D, false, 0L);
        }
        if (speedMod >= -2.1D || (extraDamage && speedMod >= -2.3D)) {
            return new WeaponCombatProfile(WeaponCombatStyle.DUELIST_AGGRESSIVE, effectiveReach, 1.8D, true, false, 0L);
        }
        if (speedMod <= -2.85D || twoHanded) {
            return new WeaponCombatProfile(WeaponCombatStyle.HEAVY_BREAKER, effectiveReach, 1.4D, false, true, 0L);
        }
        return new WeaponCombatProfile(WeaponCombatStyle.STANDARD_MELEE, effectiveReach, 0.0D, false, true, 0L);
    }
}

