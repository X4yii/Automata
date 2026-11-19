package com.x4yi.automata.ai.jobs;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityFlying;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IRangedAttackMob;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityGhast;
import net.minecraft.entity.monster.EntityShulker;
import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.compat.SrpCompat;
import com.x4yi.automata.entity.EntityAutomaton;

public final class AdaptiveThreatAnalyzer {

    public enum ThreatTrait {
        AERIAL,
        RANGED,
        PULLER_TRAPPER,
        SUMMONER_SPAWNER,
        AOE_BURST,
        EXPLOSIVE_IMMINENT,
        EVOLVED_ARMOR_CAP,
        BOSS_TIER,
        HEAVY_MELEE
    }

    @FunctionalInterface
    public interface ThreatTraitDetector {
        void detect(@Nullable EntityAutomaton bot, EntityLivingBase target, EnumSet<ThreatTrait> outTraits);
    }

    public record ThreatProfile(
            EnumSet<ThreatTrait> traits,
            double riskScore,
            double priorityMultiplier,
            int recommendedMeleeSlots) {

        public static final ThreatProfile NEUTRAL =
                new ThreatProfile(EnumSet.noneOf(ThreatTrait.class), 1.0D, 1.0D, 1);

        public boolean has(ThreatTrait trait) {
            return traits.contains(trait);
        }
    }

    private static final List<ThreatTraitDetector> DETECTORS = new CopyOnWriteArrayList<>();

    static {
        registerTraitDetector(AdaptiveThreatAnalyzer::detectGenericTraits);
        registerTraitDetector(AdaptiveThreatAnalyzer::detectSrpTraits);
    }

    private AdaptiveThreatAnalyzer() {}

    public static void registerTraitDetector(ThreatTraitDetector detector) {
        Objects.requireNonNull(detector, "detector");
        DETECTORS.add(detector);
    }

    public static ThreatProfile analyzeThreat(@Nullable EntityAutomaton bot, @Nullable EntityLivingBase target) {
        if (target == null) {
            return ThreatProfile.NEUTRAL;
        }
        EnumSet<ThreatTrait> traits = EnumSet.noneOf(ThreatTrait.class);
        for (ThreatTraitDetector detector : DETECTORS) {
            detector.detect(bot, target, traits);
        }
        IAttributeInstance dmgAttr = target.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        double dmg = dmgAttr != null ? dmgAttr.getAttributeValue() : 2.0D;
        return buildProfileFromTraits(traits, target.getMaxHealth(), target.getHealth(), dmg);
    }

    public static ThreatProfile buildProfileFromTraits(
            EnumSet<ThreatTrait> traits,
            float maxHealth,
            float currentHealth,
            double attackDamage) {
        double risk = 1.0D + Math.min(4.0D, maxHealth / 50.0D) + Math.min(2.0D, attackDamage / 12.0D);
        double mult = 1.0D;
        int slots = currentHealth > 25.0F || maxHealth >= 40.0F ? 2 : 1;

        if (traits.contains(ThreatTrait.EXPLOSIVE_IMMINENT)) {
            risk += 6.0D;
            mult *= 0.15D;
        }
        if (traits.contains(ThreatTrait.SUMMONER_SPAWNER)) {
            risk += 4.5D;
            mult *= 0.42D;
            slots = Math.max(slots, 3);
        }
        if (traits.contains(ThreatTrait.BOSS_TIER)) {
            risk += 6.0D;
            mult *= 0.55D;
            slots = Integer.MAX_VALUE;
        }
        if (traits.contains(ThreatTrait.EVOLVED_ARMOR_CAP)) {
            risk += 3.0D;
            mult *= 0.65D;
            slots = Math.max(slots, 3);
        }
        if (traits.contains(ThreatTrait.RANGED)) {
            risk += 2.5D;
            mult *= 0.62D;
            slots = Math.max(slots, 2);
        }
        if (traits.contains(ThreatTrait.PULLER_TRAPPER)) {
            risk += 2.5D;
            mult *= 0.65D;
            slots = Math.max(slots, 2);
        }
        if (traits.contains(ThreatTrait.AOE_BURST)) {
            risk += 2.0D;
            mult *= 0.72D;
        }
        if (traits.contains(ThreatTrait.AERIAL)) {
            risk += 2.0D;
            mult *= 0.78D;
            slots = Math.max(slots, 2);
        }
        if (traits.contains(ThreatTrait.HEAVY_MELEE)) {
            risk += 1.5D;
            mult *= 0.80D;
            if (maxHealth >= 80.0F) {
                slots = Math.max(slots, 3);
            }
        }
        return new ThreatProfile(traits, risk, Math.max(0.10D, mult), slots);
    }

    private static void detectGenericTraits(
            @Nullable EntityAutomaton bot,
            EntityLivingBase target,
            EnumSet<ThreatTrait> out) {
        if (isAerialThreat(bot, target)) {
            out.add(ThreatTrait.AERIAL);
        }
        if (target instanceof IRangedAttackMob
                || target instanceof EntityBlaze
                || target instanceof EntityGhast
                || target instanceof EntityShulker) {
            out.add(ThreatTrait.RANGED);
        }
        if (target instanceof EntityCreeper creeper && creeper.getCreeperState() > 0) {
            out.add(ThreatTrait.EXPLOSIVE_IMMINENT);
        }
        if (!target.isNonBoss() || target.getMaxHealth() >= 180.0F) {
            out.add(ThreatTrait.BOSS_TIER);
        } else if (target.getMaxHealth() >= 80.0F && target.getTotalArmorValue() >= 10) {
            out.add(ThreatTrait.EVOLVED_ARMOR_CAP);
        }
        IAttributeInstance dmg = target.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        if (dmg != null && dmg.getAttributeValue() >= 10.0D) {
            out.add(ThreatTrait.HEAVY_MELEE);
        }
    }

    private static void detectSrpTraits(
            @Nullable EntityAutomaton bot,
            EntityLivingBase target,
            EnumSet<ThreatTrait> out) {
        if (!SrpCompat.isSrpLoaded() && !isSrpParasite(target)) {
            return;
        }
        if (SrpCompat.isSrpFlying(target)) {
            out.add(ThreatTrait.AERIAL);
        }
        if (SrpCompat.isSrpShooter(target)) {
            out.add(ThreatTrait.RANGED);
        }
        if (SrpCompat.isSrpPuller(target)) {
            out.add(ThreatTrait.PULLER_TRAPPER);
        }
        if (SrpCompat.isSrpSummonerOrSpawner(target) || SrpCompat.isSrpNexusStructure(target)) {
            out.add(ThreatTrait.SUMMONER_SPAWNER);
        }
        if (SrpCompat.getSrpSwellState(target) > 0) {
            out.add(ThreatTrait.EXPLOSIVE_IMMINENT);
        }
        if (SrpCompat.isSrpCustomAoeAttacker(target) && SrpCompat.getSrpAttackTimer(target) > 0.0F) {
            out.add(ThreatTrait.AOE_BURST);
        }
        int tier = SrpCompat.getSrpEvolutionTier(target);
        if (tier >= 3) {
            out.add(ThreatTrait.BOSS_TIER);
        } else if (tier >= 1) {
            out.add(ThreatTrait.EVOLVED_ARMOR_CAP);
        }
    }

    public static boolean isAerialThreat(@Nullable EntityAutomaton bot, @Nullable EntityLivingBase target) {
        if (target == null) {
            return false;
        }
        if (target instanceof EntityFlying || target instanceof EntityGhast || SrpCompat.isSrpFlying(target)) {
            return true;
        }
        return bot != null && !target.onGround && !target.isInWater() && (target.posY - bot.posY) > 2.5D;
    }

    public static boolean isRangedOrSpecialThreat(@Nullable EntityLivingBase target) {
        if (target == null) {
            return false;
        }
        return target instanceof IRangedAttackMob
                || target instanceof EntityBlaze
                || target instanceof EntityGhast
                || target instanceof EntityShulker
                || SrpCompat.isSrpShooter(target)
                || SrpCompat.isSrpPuller(target)
                || isSrpParasite(target);
    }

    public static boolean isBossOrHighThreat(@Nullable EntityLivingBase target) {
        if (target == null) {
            return false;
        }
        if (!target.isNonBoss() || target.getMaxHealth() >= 60.0F) {
            return true;
        }
        IAttributeInstance dmg = target.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        return dmg != null && dmg.getAttributeValue() >= 10.0D;
    }

    public static boolean isSrpParasite(@Nullable EntityLivingBase target) {
        if (target == null) {
            return false;
        }
        ResourceLocation key = EntityList.getKey(target);
        return key != null && SrpCompat.SRP_MOD_ID.equals(key.getNamespace());
    }

    public static double computeThreatPriorityMultiplier(
            boolean primedCreeper,
            boolean aerial,
            boolean rangedOrSpecial,
            boolean bossOrHighThreat,
            boolean srpParasite) {
        double mult = 1.0D;
        if (primedCreeper) {
            mult *= 0.15D;
        }
        if (rangedOrSpecial) {
            mult *= 0.65D;
        }
        if (aerial) {
            mult *= 0.80D;
        }
        if (bossOrHighThreat) {
            mult *= 0.70D;
        }
        if (srpParasite) {
            mult *= 0.60D;
        }
        return mult;
    }
}
