package com.x4yi.automata.ai.jobs;

import net.minecraft.entity.EntityLivingBase;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.movement.SwimController;

public final class AquaticCombatController {

    private static final double AQUATIC_ENGAGE_MAX_DIST_SQ = 196.0D;

    private AquaticCombatController() {}

    public static boolean engageUnderwater(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state,
            double hitboxDistSq,
            long now,
            boolean coop) {
        if (!bot.isInWater()) {
            return false;
        }

        if (SwimController.needsAirSurface(bot, false)) {
            ctx.pauseNavigationForDirectControl();
            ctx.getMovement().lookSmoothlyAt(target.posX, bot.posY + 4.0D, target.posZ);
            bot.setSprinting(true);
            ctx.getMovement().applyDirectStrafe(0.65F, 0.0F, 1.15F);
            bot.getJumpHelper().setJumping();
            if (bot.motionY < 0.16D) {
                bot.motionY += 0.06D;
            }
            return true;
        }

        if (hitboxDistSq > AQUATIC_ENGAGE_MAX_DIST_SQ) {
            return false;
        }

        CombatJobSupport.cancelBowDraw(bot);
        EquipmentSelector.equipBestNonBookWeapon(bot);
        WeaponCombatProfile profile = WeaponCombatProfile.resolve(bot.getHeldItemMainhand());
        double effectiveRangeSq = Math.max(9.0D, profile.getAttackRangeSq());

        ctx.pauseNavigationForDirectControl();
        double targetCenterY = target.posY + target.height * 0.5D;
        ctx.getMovement().lookSmoothlyAt(target.posX, targetCenterY, target.posZ);

        boolean aqua = EntityAutomaton.isAquaAcrobaticsLoaded();
        double dy = targetCenterY - (bot.posY + bot.getEyeHeight());
        float[] input = computeAquaticApproachInputs(aqua, dy, bot.isAttackReady(), state.strafeDir);

        bot.setSprinting(bot.getHunger() > 6.0F);
        ctx.getMovement().applyDirectStrafe(input[0], input[1], 1.20F);

        if (!aqua || !bot.isSwimming()) {
            if (dy > 0.35D) {
                bot.getJumpHelper().setJumping();
                if (bot.motionY < 0.12D) {
                    bot.motionY += 0.04D;
                }
            } else if (dy < -0.45D && bot.motionY > -0.14D) {
                bot.motionY -= 0.035D;
            }
        }

        boolean rapid = profile.getStyle() == WeaponCombatProfile.WeaponCombatStyle.RAPID_BRAWLER;
        boolean targetInvulnerable = target.hurtResistantTime > target.maxHurtResistantTime / 2;
        if (hitboxDistSq <= effectiveRangeSq
                && bot.isAttackReady()
                && (rapid || !targetInvulnerable)
                && bot.canEntityBeSeen(target)) {
            MeleeCombatController.performMeleeStrike(ctx, bot, target, state, now, coop, false);
        }
        return true;
    }

    static float[] computeAquaticApproachInputs(
            boolean aquaAcrobatics,
            double dy,
            boolean attackReady,
            int flankSign) {
        int sign = flankSign >= 0 ? 1 : -1;
        if (aquaAcrobatics) {
            float forward = attackReady ? 0.95F : 0.70F;
            float strafe = attackReady ? sign * 0.25F : sign * 0.85F;
            return new float[] {forward, strafe};
        }
        float forward = attackReady ? 0.90F : 0.45F;
        float strafe = sign * 0.65F;
        return new float[] {forward, strafe};
    }
}
