package com.x4yi.automata.movement;

import net.minecraft.block.material.Material;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;

public final class SwimController {

    private SwimController() {}

    public static boolean needsAirSurface(EntityAutomaton bot, boolean miningActive) {
        if (bot.isPotionActive(MobEffects.WATER_BREATHING)) {
            return false;
        }
        int threshold = miningActive ? 60 : 90;
        return bot.getAir() < threshold;
    }

    public static void tickWaterNavigation(
            EntityAutomaton bot,
            HumanMovementController movement,
            BlockPlacementService placementService,
            int stepX,
            int stepY,
            int stepZ,
            double targetY,
            boolean miningActive,
            boolean canSprint,
            int stuckTicks) {
        if (!miningActive && canSprint) {
            bot.setSprinting(true);
        }
        CameraController.lookSmoothlyAt(bot, stepX + 0.5D, targetY + bot.getEyeHeight(), stepZ + 0.5D, MovementPace.FAST);
        boolean needsAirInWater = needsAirSurface(bot, miningActive);
        boolean stepIsDry = bot.world.getBlockState(new BlockPos(stepX, stepY, stepZ)).getMaterial() != Material.WATER;
        boolean climbingOut = !miningActive && ((stepIsDry && targetY > bot.posY + 0.10D)
                || (bot.collidedHorizontally && targetY >= bot.posY - 0.15D)
                || targetY > bot.posY + 0.85D);
        if (needsAirInWater || climbingOut) {
            bot.getJumpHelper().setJumping();
            if (climbingOut && bot.collidedHorizontally && bot.motionY < 0.34D) {
                bot.motionY = 0.34D;
            } else if (!bot.isSwimming() && bot.motionY < 0.08D) {
                bot.motionY += 0.04D;
            }
        } else if ((miningActive || targetY < bot.posY - 0.25D) && !needsAirInWater) {
            if (bot.isSwimming() && bot.motionY > -0.18D) {
                bot.motionY -= 0.03D;
            } else if (!bot.isSwimming() && bot.motionY > -0.06D) {
                bot.motionY -= 0.02D;
            }
        }
        if ((bot.collidedHorizontally || stuckTicks >= 10)
                && bot.world instanceof WorldServer ws
                && AutomataConfig.allowTerrainModificationInPathing
                && bot.getTaskConfig().isAllowScaffolding()) {
            BlockPos waterEscape = new BlockPos(stepX, stepY - 1, stepZ);
            if (!CellClassifier.isSolidSupport(ws, waterEscape)) {
                placementService.tryPlaceBlockLegit(ws, bot, movement, waterEscape, false, false);
            }
        }
    }
}
