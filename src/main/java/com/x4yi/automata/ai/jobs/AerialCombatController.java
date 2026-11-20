package com.x4yi.automata.ai.jobs;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.BotLoadoutProfile;
import com.x4yi.automata.compat.ModCompatBridge;
import com.x4yi.automata.compat.WingsCompat;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.movement.WingFlightController;

/**
 * Contextual aerial combat controller for automata equipped with Wings.
 * Chooses tactics dynamically based on ceiling clearance, target altitude,
 * enemy archetype/AOE threats, loadout (ranged vs melee), and wing satiation.
 */
public final class AerialCombatController {

    public enum AerialTactic {
        GROUND_ONLY,
        EMERGENCY_LAND_LOW_SATIATION,
        AIR_VS_AIR_DOGFIGHT,
        AIR_VS_GROUND_RANGED_HARASS,
        AIR_VS_GROUND_DIVE_STRIKE,
        AIR_CLIMB_RECOVER
    }

    public static final int MIN_TAKEOFF_HEADROOM_BLOCKS = 4;

    private AerialCombatController() {}

    public static AerialTactic selectTactic(
            boolean hasUsableWings,
            boolean canFly,
            boolean hasOpenSkyHeadroom,
            boolean targetAirborne,
            boolean botAttackReady,
            boolean hasRangedCapability,
            boolean avoidMeleeRush,
            boolean currentlyAirborne,
            double heightAboveTarget) {
        if (!hasUsableWings) {
            return AerialTactic.GROUND_ONLY;
        }
        if (!canFly) {
            return currentlyAirborne ? AerialTactic.EMERGENCY_LAND_LOW_SATIATION : AerialTactic.GROUND_ONLY;
        }
        if (!hasOpenSkyHeadroom && !currentlyAirborne) {
            return AerialTactic.GROUND_ONLY;
        }
        if (targetAirborne) {
            return AerialTactic.AIR_VS_AIR_DOGFIGHT;
        }
        if (hasRangedCapability && (avoidMeleeRush || heightAboveTarget >= 3.0D)) {
            return AerialTactic.AIR_VS_GROUND_RANGED_HARASS;
        }
        if (avoidMeleeRush) {
            return AerialTactic.AIR_CLIMB_RECOVER;
        }
        if (botAttackReady) {
            return AerialTactic.AIR_VS_GROUND_DIVE_STRIKE;
        }
        return AerialTactic.AIR_CLIMB_RECOVER;
    }

    public static boolean hasOpenSkyHeadroom(WorldServer world, BlockPos pos) {
        for (int dy = 1; dy <= MIN_TAKEOFF_HEADROOM_BLOCKS; dy++) {
            BlockPos check = pos.up(dy);
            if (!world.isBlockLoaded(check) || !world.isAirBlock(check)) {
                return false;
            }
        }
        return true;
    }

    public static boolean tickAerialCombat(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityLivingBase target,
            CombatJobSupport.CombatTacticsState state) {
        boolean hasWings = WingsCompat.hasUsableWings(bot);
        if (!hasWings) {
            bot.getWingFlightController().setFlying(false);
            return false;
        }

        WorldServer world = ctx.getWorld();
        boolean canFly = WingsCompat.canFly(bot);
        boolean openSky = hasOpenSkyHeadroom(world, bot.getPosition());
        boolean targetAirborne = ModCompatBridge.isModdedFlying(target) || (!target.onGround && target.fallDistance > 1.0F);
        BotLoadoutProfile loadout = ctx.getLoadoutProfile();
        ModCompatBridge.TacticalDirective directive = ModCompatBridge.resolveTacticalDirective(target);

        AerialTactic tactic = selectTactic(
                true,
                canFly,
                openSky,
                targetAirborne,
                bot.isAttackReady(),
                loadout.hasReadyRangedWeapon(),
                directive.avoidMeleeRush() || directive.fleeWhenSwelling(),
                !bot.onGround,
                bot.posY - target.posY);

        WingFlightController flight = bot.getWingFlightController();
        switch (tactic) {
            case GROUND_ONLY:
                flight.setFlying(false);
                return false;
            case EMERGENCY_LAND_LOW_SATIATION:
                flight.setFlying(false);
                WingFlightController.steerToward(bot, bot.posX, bot.posY - 2.0D, bot.posZ, 16.0F);
                return true;
            case AIR_VS_AIR_DOGFIGHT:
                flight.setFlying(true);
                flight.setDesiredMoveForward(1.0F);
                WingFlightController.steerToward(
                        bot,
                        target.posX + state.strafeDir * 2.5D,
                        target.posY + target.height + 1.5D,
                        target.posZ + state.strafeDir * 2.5D,
                        18.0F);
                return true;
            case AIR_VS_GROUND_RANGED_HARASS:
                flight.setFlying(true);
                flight.setDesiredMoveForward(0.85F);
                WingFlightController.steerToward(
                        bot,
                        target.posX + state.strafeDir * 6.0D,
                        target.posY + 6.5D,
                        target.posZ - state.strafeDir * 6.0D,
                        15.0F);
                return true;
            case AIR_VS_GROUND_DIVE_STRIKE:
                flight.setFlying(true);
                flight.setDesiredMoveForward(1.0F);
                WingFlightController.steerToward(
                        bot,
                        target.posX,
                        target.posY + target.height * 0.5D,
                        target.posZ,
                        22.0F);
                return true;
            case AIR_CLIMB_RECOVER:
            default:
                flight.setFlying(true);
                flight.setDesiredMoveForward(0.9F);
                WingFlightController.steerToward(
                        bot,
                        target.posX + state.strafeDir * 3.5D,
                        target.posY + 5.0D,
                        target.posZ + state.strafeDir * 3.5D,
                        16.0F);
                return true;
        }
    }
}
