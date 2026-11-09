package com.x4yi.automata.ai.jobs;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityFlying;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IRangedAttackMob;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityGhast;
import net.minecraft.entity.monster.EntityShulker;
import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.compat.SrpCompat;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Dynamically classifies modded and vanilla combat threats (aerial, ranged, bosses, SRP parasites)
 * without hardcoding entity IDs.
 */
public final class AdaptiveThreatAnalyzer {

    private AdaptiveThreatAnalyzer() {}

    public static boolean isAerialThreat(EntityAutomaton bot, @Nullable EntityLivingBase target) {
        if (target == null) {
            return false;
        }
        if (target instanceof EntityFlying || target instanceof EntityGhast) {
            return true;
        }
        return !target.onGround && !target.isInWater() && (target.posY - bot.posY) > 2.5D;
    }

    public static boolean isRangedOrSpecialThreat(@Nullable EntityLivingBase target) {
        if (target == null) {
            return false;
        }
        return target instanceof IRangedAttackMob
                || target instanceof EntityBlaze
                || target instanceof EntityGhast
                || target instanceof EntityShulker
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

