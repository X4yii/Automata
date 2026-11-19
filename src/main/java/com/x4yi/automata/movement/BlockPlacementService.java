package com.x4yi.automata.movement;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockPressurePlate;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.compat.ClaimProtectionCompat;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.WorldCacheManager;
import com.x4yi.automata.world.FakePlayerService;

public final class BlockPlacementService {

    public static final double MAX_PLACE_REACH = 4.5D;
    public static final double MAX_PLACE_REACH_SQ = MAX_PLACE_REACH * MAX_PLACE_REACH;
    public static final int BASE_PLACE_COOLDOWN_TICKS = 4;
    private static final float MAX_AIM_ERROR_DEG = 22.0F;

    private static final EnumFacing[] BRIDGE_FACE_ORDER = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST, EnumFacing.DOWN
    };
    private static final EnumFacing[] PILLAR_FACE_ORDER = {
            EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST
    };

    private int cooldownTicks;
    private long lastPlacedBlockPacked = Long.MIN_VALUE;

    public void tickCooldown() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
    }

    public boolean isOnCooldown() {
        return cooldownTicks > 0;
    }

    public long consumeLastPlacedBlock() {
        long val = lastPlacedBlockPacked;
        lastPlacedBlockPacked = Long.MIN_VALUE;
        return val;
    }

    public static boolean isValidScaffoldBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemBlock itemBlock)) {
            return false;
        }
        Block block = itemBlock.getBlock();
        if (AutomataConfig.isBlockBlacklisted(block)) {
            return false;
        }
        if (block instanceof BlockFalling
                || block instanceof BlockSlab
                || block instanceof BlockContainer
                || block instanceof BlockTorch
                || block instanceof BlockBush
                || block instanceof BlockDoor
                || block instanceof BlockFenceGate
                || block instanceof BlockPressurePlate) {
            return false;
        }
        IBlockState defaultState = block.getDefaultState();
        if (block.hasTileEntity(defaultState)) {
            return false;
        }
        return defaultState.isFullCube() && defaultState.isOpaqueCube() && defaultState.getMaterial().isSolid();
    }

    public boolean tryPlaceBlockLegit(
            WorldServer world,
            EntityAutomaton bot,
            HumanMovementController controller,
            BlockPos targetPos,
            boolean bridgeMode,
            boolean lavaRescue) {
        if (!world.isBlockLoaded(targetPos)) {
            return false;
        }
        IBlockState existing = world.getBlockState(targetPos);
        if (!world.isAirBlock(targetPos) && !existing.getBlock().isReplaceable(world, targetPos)) {
            return CellClassifier.isSolidSupport(world, targetPos);
        }
        if (!lavaRescue && cooldownTicks > 0) {
            return false;
        }
        if (!EquipmentSelector.equipBuildingBlock(bot)) {
            if (!lavaRescue) {
                controller.clearPath();
            }
            return false;
        }
        if (!ClaimProtectionCompat.canPlaceBlock(world, bot, targetPos, null)) {
            return false;
        }
        AxisAlignedBB blockBox = new AxisAlignedBB(targetPos);
        if (!world.getEntitiesWithinAABB(
                EntityLivingBase.class,
                blockBox,
                e -> e != null && e != bot && e.isEntityAlive()).isEmpty()) {
            return false;
        }
        boolean bridgeEdgeGraze = bridgeMode && bot.getEntityBoundingBox().minY >= targetPos.getY() + 0.85D;
        boolean selfIntersectAllowed = bot.isInWater() || bridgeEdgeGraze || lavaRescue;
        if (bot.getEntityBoundingBox().intersects(blockBox) && !selfIntersectAllowed) {
            return false;
        }

        Vec3d eyes = new Vec3d(bot.posX, bot.posY + bot.getEyeHeight(), bot.posZ);
        EnumFacing[] faceOrder = bridgeMode ? BRIDGE_FACE_ORDER : PILLAR_FACE_ORDER;
        for (EnumFacing facing : faceOrder) {
            BlockPos neighbor = targetPos.offset(facing);
            if (!CellClassifier.isSolidSupport(world, neighbor)) {
                continue;
            }
            EnumFacing clickFace = facing.getOpposite();
            Vec3d hitVec = new Vec3d(
                    neighbor.getX() + 0.5D + clickFace.getXOffset() * 0.5D,
                    neighbor.getY() + 0.5D + clickFace.getYOffset() * 0.5D,
                    neighbor.getZ() + 0.5D + clickFace.getZOffset() * 0.5D);
            if (eyes.squareDistanceTo(hitVec) > MAX_PLACE_REACH_SQ) {
                continue;
            }
            if (!lavaRescue && !hasLineOfSightToFace(world, eyes, hitVec, neighbor, clickFace)) {
                continue;
            }
            controller.lookAtBlockFace(neighbor, clickFace);
            if (!lavaRescue && !isAimedAt(bot, eyes, hitVec)) {
                return false;
            }

            boolean prevSneak = bot.isSneaking();
            bot.setSneaking(true);
            bot.swingArm(EnumHand.MAIN_HAND);
            EnumActionResult res = FakePlayerService.tryUseItemOnBlock(
                    world, bot, EnumHand.MAIN_HAND, neighbor, clickFace, 0.5F, 0.5F, 0.5F);
            if (!bridgeMode && !prevSneak) {
                bot.setSneaking(false);
            }
            if (res == EnumActionResult.SUCCESS && CellClassifier.isSolidSupport(world, targetPos)) {
                WorldCacheManager.invalidateBlock(world, targetPos);
                cooldownTicks = BASE_PLACE_COOLDOWN_TICKS + bot.getRNG().nextInt(2);
                lastPlacedBlockPacked = targetPos.toLong();
                return true;
            }
        }
        return false;
    }

    static boolean hasLineOfSightToFace(
            WorldServer world,
            Vec3d eyes,
            Vec3d hitVec,
            BlockPos expectedPos,
            EnumFacing expectedFace) {
        Vec3d rayEnd = hitVec.add(hitVec.subtract(eyes).normalize().scale(0.15D));
        RayTraceResult hit = world.rayTraceBlocks(eyes, rayEnd, false, true, false);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            return false;
        }
        return expectedPos.equals(hit.getBlockPos()) && hit.sideHit == expectedFace;
    }

    static boolean isAimedAt(EntityAutomaton bot, Vec3d eyes, Vec3d target) {
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float desiredPitch = MathHelper.clamp((float) (-(MathHelper.atan2(dy, horiz) * (180.0D / Math.PI))), -90.0F, 90.0F);
        float yawErr = Math.abs(MathHelper.wrapDegrees(desiredYaw - bot.rotationYaw));
        float pitchErr = Math.abs(desiredPitch - bot.rotationPitch);
        return yawErr <= MAX_AIM_ERROR_DEG && pitchErr <= MAX_AIM_ERROR_DEG;
    }

    @Nullable
    public static BlockPos findSupportNeighbor(WorldServer world, BlockPos targetPos, boolean bridgeMode) {
        EnumFacing[] order = bridgeMode ? BRIDGE_FACE_ORDER : PILLAR_FACE_ORDER;
        for (EnumFacing facing : order) {
            BlockPos neighbor = targetPos.offset(facing);
            if (CellClassifier.isSolidSupport(world, neighbor)) {
                return neighbor;
            }
        }
        return null;
    }
}

