package com.x4yi.automata.ai.jobs;

import java.util.Locale;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.compat.SpartanWeaponryCompat;
import com.x4yi.automata.compat.SrpCompat;

public final class WeaponCombatProfile {

    public enum CombatRole {
        MELEE,
        RANGED,
        SPECIAL_CASTER
    }

    public enum WeaponCombatStyle {
        STANDARD_MELEE,
        RAPID_BRAWLER,
        DUELIST_AGGRESSIVE,
        POLEARM_REACH,
        HEAVY_BREAKER,
        BOOMERANG_SKIRMISHER,
        THROWING_SKIRMISHER,
        CROSSBOW_MARKSMAN,
        BOW_MARKSMAN,
        VENGEANCE_CASTER
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

    public CombatRole getRole() {
        return switch (style) {
            case BOW_MARKSMAN, CROSSBOW_MARKSMAN, THROWING_SKIRMISHER, BOOMERANG_SKIRMISHER -> CombatRole.RANGED;
            case VENGEANCE_CASTER -> CombatRole.SPECIAL_CASTER;
            default -> CombatRole.MELEE;
        };
    }

    public double computeRoleAffinityMultiplier(AdaptiveThreatAnalyzer.ThreatProfile threat, float targetHealth) {
        return switch (getRole()) {
            case RANGED -> {
                double mult = 1.0D;
                if (threat.has(AdaptiveThreatAnalyzer.ThreatTrait.AERIAL)
                        || threat.has(AdaptiveThreatAnalyzer.ThreatTrait.SUMMONER_SPAWNER)
                        || threat.has(AdaptiveThreatAnalyzer.ThreatTrait.RANGED)) {
                    mult *= 0.50D;
                }
                if (threat.has(AdaptiveThreatAnalyzer.ThreatTrait.EXPLOSIVE_IMMINENT)) {
                    mult *= 0.45D;
                }
                yield mult;
            }
            case SPECIAL_CASTER -> {
                double mult = 1.0D;
                if (threat.has(AdaptiveThreatAnalyzer.ThreatTrait.AERIAL)
                        || threat.has(AdaptiveThreatAnalyzer.ThreatTrait.EVOLVED_ARMOR_CAP)
                        || threat.has(AdaptiveThreatAnalyzer.ThreatTrait.BOSS_TIER)
                        || targetHealth > 25.0F) {
                    mult *= 0.55D;
                } else if (targetHealth <= 20.0F) {
                    mult *= 1.35D;
                }
                yield mult;
            }
            case MELEE -> 1.0D;
        };
    }

    public static WeaponCombatProfile resolve(ItemStack stack) {
        if (stack.isEmpty()) {
            return new WeaponCombatProfile(WeaponCombatStyle.STANDARD_MELEE, BASE_MELEE_REACH, 0.0D, false, false, 20L);
        }
        if (SrpCompat.isBookOfVengeance(stack)) {
            return new WeaponCombatProfile(
                    WeaponCombatStyle.VENGEANCE_CASTER, SrpCompat.getVengeanceBookRange(), 4.0D, false, false, 0L);
        }
        SrpCompat.SrpWeaponKind srpKind = SrpCompat.resolveSrpWeaponKind(stack);
        if (srpKind == SrpCompat.SrpWeaponKind.BOW) {
            return new WeaponCombatProfile(WeaponCombatStyle.BOW_MARKSMAN, BASE_MELEE_REACH, 16.0D, false, false, 20L);
        }
        if (SpartanWeaponryCompat.isBoomerang(stack)) {
            return new WeaponCombatProfile(WeaponCombatStyle.BOOMERANG_SKIRMISHER, BASE_MELEE_REACH, 10.0D, false, false, 12L);
        }
        if (SpartanWeaponryCompat.isCrossbow(stack)) {
            return new WeaponCombatProfile(WeaponCombatStyle.CROSSBOW_MARKSMAN, BASE_MELEE_REACH, 16.0D, false, false, 25L);
        }
        if (com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesTickRangedWeapon(stack)
                && com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesRangedItem(stack)) {
            return new WeaponCombatProfile(WeaponCombatStyle.THROWING_SKIRMISHER, BASE_MELEE_REACH, 6.0D, false, false, 4L);
        }
        if (stack.getItem() instanceof ItemBow
                || (stack.getItemUseAction() == EnumAction.BOW
                        && !com.x4yi.automata.compat.ModdedWeaponCompat.isNoCubesZeroAmmoWeapon(stack))) {
            return new WeaponCombatProfile(WeaponCombatStyle.BOW_MARKSMAN, BASE_MELEE_REACH, 16.0D, false, false, 20L);
        }
        if (SpartanWeaponryCompat.isThrowableWeapon(stack)) {
            ResourceLocation key = stack.getItem().getRegistryName();
            String path = key != null ? key.getPath().toLowerCase(Locale.ROOT) : "";
            long charge = path.contains("knife") ? 8L : 14L;
            return new WeaponCombatProfile(WeaponCombatStyle.THROWING_SKIRMISHER, BASE_MELEE_REACH, 8.0D, false, false, charge);
        }

        double reachBonus = Math.max(SpartanWeaponryCompat.getReachBonus(stack), SrpCompat.getSrpWeaponReachBonus(stack));
        double speedMod = readAttackSpeedModifier(stack);
        boolean twoHanded = SpartanWeaponryCompat.isTwoHanded(stack);
        boolean rapidSpam = com.x4yi.automata.compat.ModdedWeaponCompat.isRapidSpamWeapon(stack);
        boolean extraDamage = SpartanWeaponryCompat.hasWeaponProperty(stack, "extra_damage")
                || SpartanWeaponryCompat.hasWeaponProperty(stack, "damage_bonus");

        if (srpKind != SrpCompat.SrpWeaponKind.NONE) {
            return fromSrpKind(srpKind, reachBonus, speedMod);
        }
        return fromStats(reachBonus, speedMod, twoHanded, extraDamage, rapidSpam);
    }

    static WeaponCombatProfile fromSrpKind(SrpCompat.SrpWeaponKind kind, double reachBonus, double speedMod) {
        double effectiveReach = BASE_MELEE_REACH + Math.max(0.0D, reachBonus);
        return switch (kind) {
            case SCYTHE -> new WeaponCombatProfile(
                    WeaponCombatStyle.POLEARM_REACH,
                    BASE_MELEE_REACH + Math.max(0.5D, reachBonus),
                    2.2D + Math.max(0.0D, reachBonus) * 0.5D,
                    false,
                    false,
                    0L);
            case LANCE -> new WeaponCombatProfile(
                    WeaponCombatStyle.POLEARM_REACH,
                    BASE_MELEE_REACH + Math.max(1.0D, reachBonus),
                    2.5D + Math.max(0.0D, reachBonus) * 0.5D,
                    true,
                    false,
                    0L);
            case CLEAVER -> new WeaponCombatProfile(
                    WeaponCombatStyle.HEAVY_BREAKER, effectiveReach, 1.6D, false, true, 0L);
            case MAUL -> new WeaponCombatProfile(
                    WeaponCombatStyle.HEAVY_BREAKER, effectiveReach, 2.0D, false, true, 0L);
            case BOW -> new WeaponCombatProfile(
                    WeaponCombatStyle.BOW_MARKSMAN, BASE_MELEE_REACH, 16.0D, false, false, 20L);
            case BOOK_OF_VENGEANCE -> new WeaponCombatProfile(
                    WeaponCombatStyle.VENGEANCE_CASTER, SrpCompat.getVengeanceBookRange(), 4.0D, false, false, 0L);
            case AXE, SWORD, NONE -> fromStats(reachBonus, speedMod, false, true, false);
        };
    }

    private static double readAttackSpeedModifier(ItemStack stack) {
        double spartan = SpartanWeaponryCompat.getAttackSpeedModifier(stack);
        if (spartan != 0.0D) {
            return spartan;
        }
        double total = 0.0D;
        for (AttributeModifier mod : stack.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                .get(SharedMonsterAttributes.ATTACK_SPEED.getName())) {
            total += mod.getAmount();
        }
        return total;
    }

    static WeaponCombatProfile fromStats(
            double reachBonus,
            double speedMod,
            boolean twoHanded,
            boolean extraDamage) {
        return fromStats(reachBonus, speedMod, twoHanded, extraDamage, false);
    }

    static WeaponCombatProfile fromStats(
            double reachBonus,
            double speedMod,
            boolean twoHanded,
            boolean extraDamage,
            boolean rapidSpam) {
        double effectiveReach = BASE_MELEE_REACH + Math.max(0.0D, reachBonus);
        if (reachBonus >= 0.75D) {
            double extraReachSpace = reachBonus >= 1.75D ? 2.0D : 1.0D;
            double keepAway = 2.25D + extraReachSpace;
            return new WeaponCombatProfile(WeaponCombatStyle.POLEARM_REACH, effectiveReach, keepAway, speedMod > -2.6D, false, 0L);
        }
        if (rapidSpam || speedMod >= -1.0D) {
            return new WeaponCombatProfile(WeaponCombatStyle.RAPID_BRAWLER, effectiveReach, 1.5D, true, false, 0L);
        }
        if (speedMod >= -2.1D || (extraDamage && speedMod >= -2.3D)) {
            return new WeaponCombatProfile(WeaponCombatStyle.DUELIST_AGGRESSIVE, effectiveReach, 1.8D, true, false, 0L);
        }
        if (speedMod <= -2.85D || twoHanded) {
            return new WeaponCombatProfile(WeaponCombatStyle.HEAVY_BREAKER, effectiveReach, 1.4D, false, true, 0L);
        }
        return new WeaponCombatProfile(WeaponCombatStyle.STANDARD_MELEE, effectiveReach, 0.0D, false, false, 0L);
    }
}

