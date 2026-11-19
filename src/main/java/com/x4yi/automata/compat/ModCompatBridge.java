package com.x4yi.automata.compat;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAreaEffectCloud;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.jobs.SrpTacticalClassifier;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.world.FakePlayerService;

public final class ModCompatBridge {

    public record TacticalDirective(
            double preferredMinRangeSq,
            double preferredMaxRangeSq,
            boolean waitForLandingWhenMelee,
            boolean fleeWhenSwelling,
            boolean prioritizeCritOnLanding,
            boolean highPriorityFocus,
            boolean avoidMeleeRush,
            double threatWeightMultiplier) {

        public static final TacticalDirective DEFAULT = new TacticalDirective(
                0.0D, 9.0D, false, false, false, false, false, 1.0D);
    }

    private ModCompatBridge() {}

    public static boolean isImminentSwellingExplosive(@Nullable Entity entity) {
        if (entity instanceof EntityLivingBase) {
            return SrpCompat.getSrpSwellState(entity) > 0;
        }
        return false;
    }

    public static boolean isStaticAreaHazard(@Nullable Entity entity) {
        if (entity == null || !entity.isEntityAlive()) {
            return false;
        }
        if (entity instanceof EntityAreaEffectCloud) {
            return true;
        }
        if (!SrpCompat.isSrpLoaded()) {
            return false;
        }
        ResourceLocation key = EntityList.getKey(entity);
        return SrpTacticalClassifier.isSrpAreaHazardId(key);
    }

    public static boolean isDirectionalGroundWave(@Nullable Entity entity) {
        return entity != null && SrpCompat.isSrpGroundWave(entity);
    }

    public static boolean isCustomAoeAttacker(@Nullable Entity entity) {
        return entity != null && SrpCompat.isSrpCustomAoeAttacker(entity);
    }

    public static float getCustomAoeWindupProgress(@Nullable Entity entity) {
        return entity == null ? 0.0F : SrpCompat.getSrpAttackTimer(entity);
    }

    public static boolean isNonTargetableHazard(@Nullable Entity entity) {
        return entity != null && SrpCompat.isSrpNonTargetableHazard(entity);
    }

    public static boolean isModdedProjectile(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        if (SrpCompat.isSrpProjectile(entity)) {
            return true;
        }
        if (!SrpCompat.isSrpLoaded()) {
            return false;
        }
        ResourceLocation key = EntityList.getKey(entity);
        return SrpTacticalClassifier.isSrpProjectileId(key);
    }

    public static boolean isModdedExplosiveId(@Nullable ResourceLocation key) {
        return key != null && SrpTacticalClassifier.isSrpExplosiveId(key);
    }

    public static boolean isModdedRangedOrPuller(@Nullable Entity entity) {
        return entity != null && (SrpCompat.isSrpShooter(entity) || SrpCompat.isSrpPuller(entity));
    }

    public static boolean isModdedFlying(@Nullable Entity entity) {
        return entity != null && SrpCompat.isSrpFlying(entity);
    }

    public static boolean isTwoHandedWeapon(ItemStack stack) {
        return !stack.isEmpty() && SpartanWeaponryCompat.isTwoHanded(stack);
    }

    public static TacticalDirective resolveTacticalDirective(@Nullable Entity entity) {
        if (entity == null || !SrpCompat.isSrpLoaded()) {
            return TacticalDirective.DEFAULT;
        }
        SrpTacticalClassifier.SrpCombatProfile p = SrpTacticalClassifier.classify(entity);
        if (p == SrpTacticalClassifier.SrpCombatProfile.NONE) {
            return TacticalDirective.DEFAULT;
        }
        boolean avoidRush = p.archetype() == SrpTacticalClassifier.SrpParasiteArchetype.AOE_TOXIC_AURA
                || p.archetype() == SrpTacticalClassifier.SrpParasiteArchetype.SUICIDE_BOMBER
                || p.archetype() == SrpTacticalClassifier.SrpParasiteArchetype.HOST_SPAWNER_AOE;
        return new TacticalDirective(
                p.preferredMinRangeSq(),
                p.preferredMaxRangeSq(),
                p.waitForLandingWhenMelee(),
                p.fleeWhenSwelling(),
                p.prioritizeCritOnLanding(),
                p.isHighPriorityFocusTarget(),
                avoidRush,
                p.threatWeightMultiplier());
    }

    public static void tickEntityCompat(EntityAutomaton bot, WorldServer world) {
        SpartanWeaponryCompat.tickBoomerangReturn(bot);
        SpartanWeaponryCompat.tickThrownWeaponRecovery(bot);
        FakePlayerService.tickEquippedItemEffects(world, bot);
    }
}
