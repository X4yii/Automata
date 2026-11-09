package com.x4yi.automata.ai.jobs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockNetherWart;
import net.minecraft.block.BlockStem;
import net.minecraft.block.IGrowable;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.items.IItemHandler;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.entity.EntityAutomaton;

public class FarmerJob implements IAutomatonJob {

    private static final long UNREACHABLE_TTL_TICKS = 80L;
    private static final long AREA_PROMPT_INTERVAL_TICKS = 200L;

    private final AreaBlacklistTracker blacklistTracker = new AreaBlacklistTracker(UNREACHABLE_TTL_TICKS);

    @Nullable
    private BlockPos cachedTarget;
    private long nextAreaPromptTick;
    private long nextPatrolTick;

    @Override
    public ResourceLocation getJobId() {
        return AutomatonBuiltInJobs.FARMER;
    }

    @Override
    public void onStop(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        if (cachedTarget != null) {
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }
        blacklistTracker.clear();
        ctx.getAutomaton().clearNeededTool();
    }

    @Override
    public void tick(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        EntityAutomaton bot = ctx.getAutomaton();
        WorldServer world = ctx.getWorld();
        long now = ctx.getWorldTime();

        if (ctx.getTaskConfig().getPos1() == null || ctx.getTaskConfig().getPos2() == null) {
            bot.getChunkLoader().setExtraWorkChunk(null);
            bot.clearNeededTool();
            ctx.stopNavigation();
            promptOwnerForArea(bot, world, now);
            return;
        }

        if (cachedTarget != null && ctx.getMovement().getLastStuckGoal() != Long.MIN_VALUE) {
            ctx.getMovement().clearLastStuckGoal();
            blacklistTarget(cachedTarget, now);
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }

        JobBlockSupport.AreaBounds bounds = JobBlockSupport.resolveBounds(ctx.getTaskConfig(), bot.getPosition(), 2);

        boolean hasSeeds = hasPlantableSeed(bot.getInventory().getCombinedInventory());
        boolean hasBonemeal = hasBoneMeal(bot.getInventory().getCombinedInventory());

        BlockPos matureCrop = cachedTarget != null && isMatureCropValid(ctx, bounds, cachedTarget, now)
                ? cachedTarget
                : null;
        if (cachedTarget != null && matureCrop == null) {
            JobBlockSupport.release(ctx, cachedTarget);
            cachedTarget = null;
        }

        FarmScanResult scan = matureCrop == null ? scanFarmZone(ctx, bounds, now, hasSeeds, hasBonemeal) : null;
        if (matureCrop == null && scan != null && scan.matureCrop != null && JobBlockSupport.tryReserve(ctx, scan.matureCrop)) {
            matureCrop = scan.matureCrop;
            cachedTarget = matureCrop;
        }

        if (matureCrop != null) {
            bot.clearNeededTool();
            bot.getChunkLoader().setExtraWorkChunk(matureCrop);
            if (!JobBlockSupport.isWithinReach(bot, matureCrop)) {
                requestReachablePath(ctx, matureCrop, now);
                return;
            }
            ctx.stopNavigation();
            if (FarmerFieldPlanner.tryRightClickHarvestModdedCrop(ctx, world, bot, matureCrop)) {
                cachedTarget = null;
                return;
            }
            if (JobBlockSupport.breakBlockWithBestTool(ctx, matureCrop)) {
                cachedTarget = null;
                BlockPos soil = matureCrop.down();
                if (world.isAirBlock(matureCrop) && isPlantableSoil(world, soil)) {
                    plantInterleavedSeed(ctx, world, soil, bounds);
                }
            }
            return;
        }

        if (hasSeeds && scan != null && scan.emptyFarmland != null && JobBlockSupport.tryReserve(ctx, scan.emptyFarmland)) {
            BlockPos emptyFarmland = scan.emptyFarmland;
            bot.clearNeededTool();
            bot.getChunkLoader().setExtraWorkChunk(emptyFarmland);
            if (!JobBlockSupport.isWithinReach(bot, emptyFarmland)) {
                requestReachablePath(ctx, emptyFarmland.up(), now);
                return;
            }
            ctx.stopNavigation();
            if (plantInterleavedSeed(ctx, world, emptyFarmland, bounds)) {
                JobBlockSupport.release(ctx, emptyFarmland);
            }
            return;
        }

        BlockPos tillable = scan != null && scan.tillableSoil != null && JobBlockSupport.tryReserve(ctx, scan.tillableSoil)
                ? scan.tillableSoil
                : null;
        if (tillable != null) {
            if (!hasHoe(bot)) {
                bot.setNeededTool(EntityAutomaton.NEEDED_TOOL_HOE);
            } else {
                bot.clearNeededTool();
                bot.getChunkLoader().setExtraWorkChunk(tillable);
                if (!JobBlockSupport.isWithinReach(bot, tillable)) {
                    requestReachablePath(ctx, tillable.up(), now);
                    return;
                }
                ctx.stopNavigation();
                BlockPos above = tillable.up();
                if (!world.isAirBlock(above) && isClearableFoliage(world, above)) {
                    JobBlockSupport.breakBlockWithBestTool(ctx, above);
                    return;
                }
                if (JobBlockSupport.useMatchingInventoryItemOnFace(
                        ctx, tillable, EnumFacing.UP, EquipmentSelector::isHoe)) {
                    JobBlockSupport.release(ctx, tillable);
                }
                return;
            }
        } else {
            bot.clearNeededTool();
        }

        if (hasBonemeal && scan != null && scan.growableCrop != null && JobBlockSupport.tryReserve(ctx, scan.growableCrop)) {
            BlockPos growingCrop = scan.growableCrop;
            bot.getChunkLoader().setExtraWorkChunk(growingCrop);
            if (!JobBlockSupport.isWithinReach(bot, growingCrop)) {
                requestReachablePath(ctx, growingCrop, now);
                return;
            }
            ctx.stopNavigation();
            if (JobBlockSupport.useMatchingInventoryItemOnFace(
                    ctx, growingCrop, EnumFacing.UP, FarmerJob::isBoneMeal)) {
                JobBlockSupport.release(ctx, growingCrop);
            }
            return;
        }

        bot.getChunkLoader().setExtraWorkChunk(null);
        patrolFarmArea(ctx, bot, world, bounds, now);
    }

    private void promptOwnerForArea(EntityAutomaton bot, WorldServer world, long now) {
        if (now < nextAreaPromptTick || bot.getOwnerUuid() == null) {
            return;
        }
        nextAreaPromptTick = now + AREA_PROMPT_INTERVAL_TICKS;
        EntityPlayer owner = world.getPlayerEntityByUUID(bot.getOwnerUuid());
        if (owner != null) {
            owner.sendStatusMessage(
                    new TextComponentString(
                            "[Automata #" + bot.getEntityId()
                                    + "] Select a farm area (Pos1 & Pos2) - I'm not farming the whole planet!"),
                    true);
        }
    }

    private void patrolFarmArea(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            WorldServer world,
            JobBlockSupport.AreaBounds bounds,
            long now) {
        if (ctx.getMovement().hasActivePath() || now < nextPatrolTick) {
            return;
        }
        nextPatrolTick = now + 55L + bot.getRNG().nextInt(36);
        for (int attempt = 0; attempt < 8; attempt++) {
            int tx = bounds.minX() + bot.getRNG().nextInt(Math.max(1, bounds.maxX() - bounds.minX() + 1));
            int tz = bounds.minZ() + bot.getRNG().nextInt(Math.max(1, bounds.maxZ() - bounds.minZ() + 1));
            for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
                BlockPos feet = new BlockPos(tx, y, tz);
                if (world.isBlockLoaded(feet)
                        && !isBlacklisted(feet, now)
                        && !ctx.isGoalOnCooldown(feet, now)
                        && world.getBlockState(feet.down()).getMaterial().isSolid()
                        && world.getBlockState(feet.down()).getMaterial() != Material.WATER
                        && world.isAirBlock(feet.up())) {
                    ctx.requestPathTo(feet, false);
                    return;
                }
            }
        }
    }

    private void requestReachablePath(AutomatonAIContext ctx, BlockPos target, long now) {
        if (ctx.isGoalOnCooldown(target, now)) {
            blacklistTarget(target, now);
            JobBlockSupport.release(ctx, target);
            if (target.equals(cachedTarget)) {
                cachedTarget = null;
            }
            return;
        }
        ctx.requestPathTo(target, false);
    }

    private void blacklistTarget(BlockPos pos, long now) {
        blacklistTracker.blacklist(pos, now);
    }

    private boolean isBlacklisted(BlockPos pos, long now) {
        return blacklistTracker.isBlacklisted(pos, now);
    }

    private static final class FarmScanResult {
        @Nullable
        BlockPos matureCrop;
        double matureDistSq = Double.MAX_VALUE;
        @Nullable
        BlockPos emptyFarmland;
        double emptyFarmlandDistSq = Double.MAX_VALUE;
        @Nullable
        BlockPos tillableSoil;
        double tillableDistSq = Double.MAX_VALUE;
        @Nullable
        BlockPos growableCrop;
        double growableDistSq = Double.MAX_VALUE;
    }

    private FarmScanResult scanFarmZone(
            AutomatonAIContext ctx,
            JobBlockSupport.AreaBounds bounds,
            long now,
            boolean checkEmptyFarmland,
            boolean checkGrowableCrop) {
        EntityAutomaton bot = ctx.getAutomaton();
        WorldServer world = ctx.getWorld();
        int entityId = bot.getEntityId();
        FarmScanResult result = new FarmScanResult();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Set<Long> waterCache = null;

        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.setPos(x, y, z);
                    if (!world.isBlockLoaded(cursor) || isBlacklisted(cursor, now)) {
                        continue;
                    }
                    if (ctx.getBlackboard().isReservedByOther(entityId, cursor, now)) {
                        continue;
                    }
                    IBlockState state = world.getBlockState(cursor);
                    Block block = state.getBlock();
                    double distSq = bot.getDistanceSqToCenter(cursor);

                    if (isMatureCrop(world, cursor, state)) {
                        if (distSq < result.matureDistSq) {
                            result.matureDistSq = distSq;
                            result.matureCrop = cursor.toImmutable();
                        }
                        continue;
                    }

                    if (checkEmptyFarmland
                            && isPlantableSoil(world, cursor)
                            && world.isAirBlock(cursor.up())) {
                        if (block == Blocks.SOUL_SAND) {
                            if (distSq < result.emptyFarmlandDistSq) {
                                result.emptyFarmlandDistSq = distSq;
                                result.emptyFarmland = cursor.toImmutable();
                            }
                        } else {
                            if (waterCache == null) {
                                waterCache = collectNearbyWaterPositions(world, bounds);
                            }
                            if (hasHydrationInSet(waterCache, x, y, z) && distSq < result.emptyFarmlandDistSq) {
                                result.emptyFarmlandDistSq = distSq;
                                result.emptyFarmland = cursor.toImmutable();
                            }
                        }
                        continue;
                    }

                    if ((block == Blocks.DIRT || block == Blocks.GRASS)
                            && (world.isAirBlock(cursor.up()) || isClearableFoliage(world, cursor.up()))) {
                        if (waterCache == null) {
                            waterCache = collectNearbyWaterPositions(world, bounds);
                        }
                        if (hasHydrationInSet(waterCache, x, y, z) && distSq < result.tillableDistSq) {
                            result.tillableDistSq = distSq;
                            result.tillableSoil = cursor.toImmutable();
                        }
                        continue;
                    }

                    if (checkGrowableCrop
                            && block instanceof BlockCrops crops
                            && !crops.isMaxAge(state)
                            && distSq < result.growableDistSq) {
                        result.growableDistSq = distSq;
                        result.growableCrop = cursor.toImmutable();
                    }
                }
            }
        }
        return result;
    }

    private static Set<Long> collectNearbyWaterPositions(WorldServer world, JobBlockSupport.AreaBounds bounds) {
        Set<Long> waterPositions = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = bounds.minX() - 4;
        int maxX = bounds.maxX() + 4;
        int minY = bounds.minY();
        int maxY = bounds.maxY() + 1;
        int minZ = bounds.minZ() - 4;
        int maxZ = bounds.maxZ() + 4;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.setPos(x, y, z);
                    if (world.isBlockLoaded(cursor)
                            && world.getBlockState(cursor).getMaterial() == Material.WATER) {
                        waterPositions.add(cursor.toLong());
                    }
                }
            }
        }
        return waterPositions;
    }

    private static boolean hasHydrationInSet(Set<Long> waterPositions, int x, int y, int z) {
        if (waterPositions.isEmpty()) {
            return false;
        }
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (waterPositions.contains(probe.setPos(x + dx, y + dy, z + dz).toLong())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isMatureCropValid(AutomatonAIContext ctx, JobBlockSupport.AreaBounds bounds, BlockPos pos, long now) {
        if (pos.getX() < bounds.minX() || pos.getX() > bounds.maxX()
                || pos.getY() < bounds.minY() || pos.getY() > bounds.maxY()
                || pos.getZ() < bounds.minZ() || pos.getZ() > bounds.maxZ()) {
            return false;
        }
        if (isBlacklisted(pos, now)) {
            return false;
        }
        WorldServer world = ctx.getWorld();
        if (!world.isBlockLoaded(pos)) {
            return false;
        }
        if (!isMatureCrop(world, pos, world.getBlockState(pos))) {
            return false;
        }
        return !ctx.getBlackboard().isReservedByOther(ctx.getAutomaton().getEntityId(), pos, now)
                && JobBlockSupport.tryReserve(ctx, pos);
    }

    private static boolean plantInterleavedSeed(
            AutomatonAIContext ctx,
            WorldServer world,
            BlockPos soil,
            JobBlockSupport.AreaBounds bounds) {
        IItemHandler inv = ctx.getAutomaton().getInventory().getCombinedInventory();
        List<String> distinctKeys = collectDistinctPlantableKeys(world, soil, inv);
        if (distinctKeys.isEmpty()) {
            return false;
        }
        BlockPos plantPos = soil.up();
        int rowIndex = computeRowIndex(plantPos.getX(), plantPos.getZ(), bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ());
        Set<String> neighborCropKeys = collectNeighborCropKeys(world, plantPos);
        int chosenIdx = selectInterleavedSeedIndex(rowIndex, distinctKeys, neighborCropKeys);
        if (chosenIdx < 0) {
            return false;
        }
        String chosenKey = distinctKeys.get(chosenIdx);
        return JobBlockSupport.useMatchingInventoryItemOnFace(
                ctx, soil, EnumFacing.UP, stack -> isPlantableKey(stack, chosenKey));
    }

    private static List<String> collectDistinctPlantableKeys(WorldServer world, BlockPos soil, IItemHandler inv) {
        List<String> keys = new ArrayList<>();
        IBlockState soilState = world.getBlockState(soil);
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!isFarmSeed(stack)) {
                continue;
            }
            IPlantable plantable = (IPlantable) stack.getItem();
            if (!soilState.getBlock().canSustainPlant(soilState, world, soil, EnumFacing.UP, plantable)) {
                continue;
            }
            String key = getPlantableKey(stack);
            if (key != null && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static Set<String> collectNeighborCropKeys(WorldServer world, BlockPos plantPos) {
        Set<String> neighbors = new HashSet<>();
        for (EnumFacing face : EnumFacing.HORIZONTALS) {
            BlockPos adj = plantPos.offset(face);
            if (!world.isBlockLoaded(adj)) {
                continue;
            }
            Block block = world.getBlockState(adj).getBlock();
            if (block instanceof BlockCrops || block instanceof BlockNetherWart) {
                ResourceLocation reg = block.getRegistryName();
                if (reg != null) {
                    neighbors.add(reg.toString());
                }
            }
        }
        return neighbors;
    }

    public static int computeRowIndex(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        return (maxX - minX) >= (maxZ - minZ) ? z : x;
    }

    public static int selectInterleavedSeedIndex(int rowIndex, List<String> distinctKeys, Set<String> neighborCropKeys) {
        if (distinctKeys.isEmpty()) {
            return -1;
        }
        int preferred = Math.floorMod(rowIndex, distinctKeys.size());
        if (!neighborCropKeys.contains(distinctKeys.get(preferred))) {
            return preferred;
        }
        for (int i = 0; i < distinctKeys.size(); i++) {
            int candidate = (preferred + i) % distinctKeys.size();
            if (!neighborCropKeys.contains(distinctKeys.get(candidate))) {
                return candidate;
            }
        }
        return preferred;
    }

    @Nullable
    private static String getPlantableKey(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof IPlantable plantable)) {
            return null;
        }
        IBlockState plantState = plantable.getPlant(null, BlockPos.ORIGIN);
        if (plantState != null && plantState.getBlock().getRegistryName() != null) {
            return plantState.getBlock().getRegistryName().toString();
        }
        ResourceLocation itemReg = stack.getItem().getRegistryName();
        return itemReg == null ? null : itemReg.toString();
    }

    private static boolean isPlantableKey(ItemStack stack, String targetKey) {
        return isFarmSeed(stack) && targetKey.equals(getPlantableKey(stack));
    }

    private static boolean isFarmSeed(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof IPlantable
                && !(stack.getItem() instanceof ItemBlock);
    }

    private static boolean isPlantableSoil(WorldServer world, BlockPos soil) {
        return FarmerFieldPlanner.isPlantableSoil(world, soil);
    }

    private static boolean isClearableFoliage(WorldServer world, BlockPos pos) {
        return FarmerFieldPlanner.isClearableFoliage(world, pos);
    }

    private static boolean hasNearbyWater(WorldServer world, BlockPos center) {
        return FarmerFieldPlanner.hasNearbyWater(world, center);
    }

    private static boolean hasPlantableSeed(IItemHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isFarmSeed(inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBoneMeal(IItemHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isBoneMeal(inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBoneMeal(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == Items.DYE && stack.getMetadata() == 15;
    }

    private static boolean hasHoe(EntityAutomaton bot) {
        if (EquipmentSelector.isHoe(bot.getHeldItemMainhand())) {
            return true;
        }
        IItemHandler inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (EquipmentSelector.isHoe(inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMatureCrop(WorldServer world, BlockPos pos, IBlockState state) {
        return FarmerFieldPlanner.isMatureCrop(world, pos, state);
    }
}
