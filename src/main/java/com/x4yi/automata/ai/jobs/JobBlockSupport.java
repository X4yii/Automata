
package com.x4yi.automata.ai.jobs;

import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.oredict.OreDictionary;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.world.FakePlayerService;
import com.x4yi.automata.world.MiningProgressController;

public final class JobBlockSupport {

    static final double INTERACT_REACH_SQ = 20.25D;
    private static final int DEFAULT_SCAN_RADIUS = 12;

    private JobBlockSupport() {}

    static boolean isWithinReach(EntityAutomaton bot, BlockPos target) {
        if (bot.getDistanceSqToCenter(target) > INTERACT_REACH_SQ) {
            return false;
        }
        if (bot.world instanceof WorldServer worldServer) {
            return findBlockingBlockOnSight(bot, worldServer, target) == null;
        }
        return true;
    }

    @Nullable
    static BlockPos findBlockingBlockOnSight(EntityAutomaton bot, WorldServer world, BlockPos target) {
        Vec3d eyes = new Vec3d(bot.posX, bot.posY + bot.getEyeHeight(), bot.posZ);
        Vec3d center = new Vec3d(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
        RayTraceResult centerHit = world.rayTraceBlocks(eyes, center, false, true, false);
        if (centerHit == null || centerHit.typeOfHit != RayTraceResult.Type.BLOCK || target.equals(centerHit.getBlockPos())) {
            return null;
        }
        for (EnumFacing face : EnumFacing.VALUES) {
            BlockPos neighbor = target.offset(face);
            if (!world.isBlockLoaded(neighbor) || !isPassableCell(world, neighbor)) {
                continue;
            }
            Vec3d facePoint = new Vec3d(
                    target.getX() + 0.5D + face.getXOffset() * 0.45D,
                    target.getY() + 0.5D + face.getYOffset() * 0.45D,
                    target.getZ() + 0.5D + face.getZOffset() * 0.45D);
            RayTraceResult faceHit = world.rayTraceBlocks(eyes, facePoint, false, true, false);
            if (faceHit == null || faceHit.typeOfHit != RayTraceResult.Type.BLOCK || target.equals(faceHit.getBlockPos())) {
                return null;
            }
        }
        return centerHit.getBlockPos();
    }

    static boolean isLeafBlock(WorldServer world, BlockPos pos) {
        if (!world.isBlockLoaded(pos) || world.isAirBlock(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        return state.getMaterial() == Material.LEAVES || state.getBlock().isLeaves(state, world, pos);
    }

    static boolean tryReserve(AutomatonAIContext ctx, BlockPos pos) {
        if (!ctx.getTaskConfig().isSquadCooperation()) {
            return true;
        }
        return ctx.getBlackboard().tryReserveBlock(ctx.getAutomaton().getEntityId(), pos, ctx.getWorldTime());
    }

    static void release(AutomatonAIContext ctx, BlockPos pos) {
        if (ctx.getTaskConfig().isSquadCooperation()) {
            ctx.getBlackboard().releaseBlock(ctx.getAutomaton().getEntityId(), pos);
        }
    }

    static boolean breakBlockWithBestTool(AutomatonAIContext ctx, BlockPos pos) {
        EntityAutomaton bot = ctx.getAutomaton();
        WorldServer world = ctx.getWorld();
        updateNeededToolForBlock(bot, world, pos);
        bot.motionX *= 0.4D;
        bot.motionZ *= 0.4D;
        bot.setMoveForward(0.0F);
        bot.setMoveStrafing(0.0F);
        bot.setSprinting(false);
        ctx.getMovement().lookSmoothlyAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        if (MiningProgressController.shouldDeferMiningInAir(bot.onGround, bot.isInWater(), bot.isOnLadder())) {
            return false;
        }
        boolean broken = ctx.getMiningController().stepBreakBlock(world, bot, pos);
        if (broken) {
            release(ctx, pos);
        }
        return broken;
    }

    private static void updateNeededToolForBlock(EntityAutomaton bot, WorldServer world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        String toolClass = state.getBlock().getHarvestTool(state);
        if (toolClass == null) {
            bot.clearNeededTool();
            return;
        }
        byte code = switch (toolClass) {
            case "pickaxe" -> EntityAutomaton.NEEDED_TOOL_PICKAXE;
            case "axe" -> EntityAutomaton.NEEDED_TOOL_AXE;
            case "shovel" -> EntityAutomaton.NEEDED_TOOL_SHOVEL;
            default -> EntityAutomaton.NEEDED_TOOL_NONE;
        };
        if (code == EntityAutomaton.NEEDED_TOOL_NONE) {
            bot.clearNeededTool();
            return;
        }
        if (hasHarvestToolInInventory(bot, toolClass)) {
            bot.clearNeededTool();
        } else {
            bot.setNeededTool(code);
        }
    }

    private static boolean hasHarvestToolInInventory(EntityAutomaton bot, String toolClass) {
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem().getToolClasses(stack).contains(toolClass)) {
                return true;
            }
        }
        return false;
    }

    static boolean useMatchingInventoryItemOnFace(
            AutomatonAIContext ctx,
            BlockPos targetPos,
            EnumFacing face,
            Predicate<ItemStack> matcher) {
        EntityAutomaton bot = ctx.getAutomaton();
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (!candidate.isEmpty() && matcher.test(candidate)) {
                ItemStack prevMain = bot.getHeldItemMainhand().copy();
                ItemStack extracted = inv.extractItem(i, candidate.getCount(), false);
                bot.setHeldItem(EnumHand.MAIN_HAND, extracted);
                ctx.getMovement().lookAtBlockFace(targetPos, face);
                EnumActionResult res = FakePlayerService.tryUseItemOnBlock(
                        ctx.getWorld(), bot, EnumHand.MAIN_HAND, targetPos, face, 0.5F, 0.5F, 0.5F);
                if (res == EnumActionResult.SUCCESS) {
                    bot.swingArm(EnumHand.MAIN_HAND);
                }
                ItemStack remaining = bot.getHeldItemMainhand().copy();
                bot.setHeldItem(EnumHand.MAIN_HAND, prevMain);
                if (!remaining.isEmpty()) {
                    ItemStack leftover = inv.insertItem(i, remaining, false);
                    if (!leftover.isEmpty()) {
                        bot.entityDropItem(leftover, 0.0F);
                    }
                }
                return res == EnumActionResult.SUCCESS;
            }
        }
        return false;
    }

    public static boolean matchesOreDict(ItemStack stack, String oreName) {
        if (stack.isEmpty()) {
            return false;
        }
        int targetId = OreDictionary.getOreID(oreName);
        for (int id : OreDictionary.getOreIDs(stack)) {
            if (id == targetId) {
                return true;
            }
        }
        return false;
    }

    static AreaBounds resolveBounds(AutomatonTaskConfig config, BlockPos fallbackCenter, int verticalRadius) {
        BlockPos p1 = config.getPos1();
        BlockPos p2 = config.getPos2();
        if (p1 != null && p2 != null) {
            return new AreaBounds(
                    Math.min(p1.getX(), p2.getX()),
                    Math.max(0, Math.min(p1.getY(), p2.getY())),
                    Math.min(p1.getZ(), p2.getZ()),
                    Math.max(p1.getX(), p2.getX()),
                    Math.min(255, Math.max(p1.getY(), p2.getY())),
                    Math.max(p1.getZ(), p2.getZ()));
        }
        BlockPos center = p1 != null ? p1 : fallbackCenter;
        return new AreaBounds(
                center.getX() - DEFAULT_SCAN_RADIUS,
                Math.max(0, center.getY() - verticalRadius),
                center.getZ() - DEFAULT_SCAN_RADIUS,
                center.getX() + DEFAULT_SCAN_RADIUS,
                Math.min(255, center.getY() + verticalRadius),
                center.getZ() + DEFAULT_SCAN_RADIUS);
    }

    @Nullable
    static BlockPos findNearestStandingSpot(WorldServer world, BlockPos target) {
        BlockPos fallbackWaterSpot = null;
        for (int dy : new int[] {0, -1, 1}) {
            for (EnumFacing facing : EnumFacing.HORIZONTALS) {
                BlockPos side = target.offset(facing).add(0, dy, 0);
                if (isWalkableDrySpot(world, side)) {
                    return side;
                }
                if (fallbackWaterSpot == null && isWalkableSpot(world, side) && !isLiquidOrFire(world, side)) {
                    fallbackWaterSpot = side;
                }
            }
        }
        BlockPos above = target.up();
        if (world.isBlockLoaded(target)
                && world.getBlockState(target).getMaterial().isSolid()
                && isPassableCell(world, above)
                && isPassableCell(world, above.up())
                && !isLiquidOrFire(world, above)) {
            return above;
        }
        return fallbackWaterSpot;
    }

    @Nullable
    static BlockPos findScaffoldApproachSpot(WorldServer world, EntityAutomaton bot, BlockPos target) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = target.add(dx, dy, dz);
                    if (candidate.distanceSq(target) > 12.0D || !world.isBlockLoaded(candidate)) {
                        continue;
                    }
                    if (!isPassableCell(world, candidate) || !isPassableCell(world, candidate.up())) {
                        continue;
                    }
                    if (isLiquidOrFire(world, candidate) || isLiquidOrFire(world, candidate.up())) {
                        continue;
                    }
                    if (!hasScaffoldBaseBelow(world, candidate, 6)) {
                        continue;
                    }
                    double score = bot.getDistanceSqToCenter(candidate) + Math.max(0, candidate.getY() - (int) bot.posY) * 2.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static boolean hasScaffoldBaseBelow(WorldServer world, BlockPos feet, int maxDrop) {
        for (int i = 1; i <= maxDrop; i++) {
            BlockPos below = feet.down(i);
            if (!world.isBlockLoaded(below)) {
                return false;
            }
            if (world.getBlockState(below).getMaterial().isSolid()) {
                return true;
            }
            if (world.getBlockState(below).getMaterial() == Material.LAVA) {
                return false;
            }
        }
        return false;
    }

    private static boolean isWalkableDrySpot(WorldServer world, BlockPos feet) {
        return isWalkableSpot(world, feet)
                && world.getBlockState(feet).getMaterial() != Material.WATER
                && world.getBlockState(feet.up()).getMaterial() != Material.WATER
                && !isLiquidOrFire(world, feet)
                && !isLiquidOrFire(world, feet.up());
    }

    private static boolean isLiquidOrFire(WorldServer world, BlockPos pos) {
        Material mat = world.getBlockState(pos).getMaterial();
        return mat == Material.LAVA || mat == Material.FIRE;
    }

    private static boolean isWalkableSpot(WorldServer world, BlockPos feet) {
        if (!world.isBlockLoaded(feet)) {
            return false;
        }
        return world.getBlockState(feet.down()).getMaterial().isSolid()
                && isPassableCell(world, feet)
                && isPassableCell(world, feet.up());
    }

    private static boolean isPassableCell(WorldServer world, BlockPos pos) {
        return world.isAirBlock(pos) || world.getBlockState(pos).getCollisionBoundingBox(world, pos) == null;
    }

    record AreaBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
}

