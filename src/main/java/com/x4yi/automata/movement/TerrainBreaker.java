package com.x4yi.automata.movement;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.world.FakePlayerService;
import com.x4yi.automata.world.MiningProgressController;

public final class TerrainBreaker {

    private TerrainBreaker() {}

    public static boolean clearBreakThroughStep(
            WorldServer world,
            EntityAutomaton bot,
            HumanMovementController controller,
            int stepX,
            int stepY,
            int stepZ) {
        int botY = MathHelper.floor(bot.posY + 0.1D);
        int botX = MathHelper.floor(bot.posX);
        int botZ = MathHelper.floor(bot.posZ);

        if (stepY > botY) {
            BlockPos currentCeiling = new BlockPos(botX, botY + 2, botZ);
            if (!tryClearSingleBlock(world, bot, controller, currentCeiling)) {
                return false;
            }
        }

        BlockPos feet = new BlockPos(stepX, stepY, stepZ);
        BlockPos upperHead = feet.up(2);
        if (stepY < bot.posY - 0.5D && !world.isAirBlock(upperHead)
                && world.getBlockState(upperHead).getCollisionBoundingBox(world, upperHead) != null) {
            if (!tryClearSingleBlock(world, bot, controller, upperHead)) {
                return false;
            }
        }
        BlockPos head = feet.up();
        if (!tryClearSingleBlock(world, bot, controller, head)) {
            return false;
        }
        return tryClearSingleBlock(world, bot, controller, feet);
    }

    public static boolean tryClearSingleBlock(
            WorldServer world,
            EntityAutomaton bot,
            HumanMovementController controller,
            BlockPos pos) {
        if (!world.isBlockLoaded(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        if (world.isAirBlock(pos) || state.getCollisionBoundingBox(world, pos) == null) {
            return true;
        }
        if (tryOpenDoorOrGate(world, bot, controller, pos, state)) {
            return true;
        }
        if (state.getBlockHardness(world, pos) < 0.0F || AutomataConfig.isBlockBlacklisted(state.getBlock())) {
            controller.clearPath();
            return false;
        }
        if (hasUnsafeBreakNeighbor(world, pos)) {
            controller.clearPath();
            return false;
        }
        if (!HumanMovementController.isWithinTerrainReach(bot.posX, bot.posY, bot.posZ, pos.getX(), pos.getY(), pos.getZ())) {
            return true;
        }
        bot.motionX *= 0.4D;
        bot.motionZ *= 0.4D;
        bot.setMoveForward(0.0F);
        bot.setMoveStrafing(0.0F);
        bot.setSprinting(false);
        if (MiningProgressController.shouldDeferMiningInAir(bot.onGround, bot.isInWater(), bot.isOnLadder())) {
            return false;
        }
        controller.lookAtBlockFace(pos);
        return bot.getBrain().getContext().getMiningController().stepBreakBlock(world, bot, pos);
    }

    public static boolean hasUnsafeBreakNeighbor(WorldServer world, BlockPos pos) {
        for (EnumFacing facing : EnumFacing.VALUES) {
            BlockPos neighbor = pos.offset(facing);
            if (!world.isBlockLoaded(neighbor)) {
                continue;
            }
            IBlockState neighborState = world.getBlockState(neighbor);
            if (CellClassifier.isHazard(neighborState)) {
                return true;
            }
            if (facing == EnumFacing.UP && neighborState.getBlock() instanceof BlockFalling) {
                return true;
            }
        }
        return false;
    }

    public static boolean tryOpenDoorOrGate(
            WorldServer world,
            EntityAutomaton bot,
            HumanMovementController controller,
            BlockPos pos,
            IBlockState state) {
        if (!CellClassifier.isDoorOrGate(state) || state.getMaterial() == net.minecraft.block.material.Material.IRON) {
            return false;
        }
        PropertyBool openProp = state.getBlock() instanceof BlockDoor ? BlockDoor.OPEN : BlockFenceGate.OPEN;
        if (Boolean.TRUE.equals(state.getValue(openProp))) {
            return true;
        }
        if (!HumanMovementController.isWithinTerrainReach(bot.posX, bot.posY, bot.posZ, pos.getX(), pos.getY(), pos.getZ())) {
            return true;
        }
        controller.lookAtBlockFace(pos);
        boolean prevSneak = bot.isSneaking();
        bot.setSneaking(false);
        EnumActionResult res = FakePlayerService.tryUseItemOnBlock(
                world, bot, EnumHand.MAIN_HAND, pos, EnumFacing.NORTH, 0.5F, 0.5F, 0.5F);
        bot.setSneaking(prevSneak);
        if (res == EnumActionResult.SUCCESS) {
            com.x4yi.automata.navigation.WorldCacheManager.invalidateBlock(world, pos);
            com.x4yi.automata.navigation.WorldCacheManager.invalidateBlock(world, pos.up());
            com.x4yi.automata.navigation.WorldCacheManager.invalidateBlock(world, pos.down());
            return true;
        }
        return false;
    }
}

