
package com.x4yi.automata.ai;

import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.VanillaDoubleChestItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

import com.x4yi.automata.entity.EntityAutomaton;

public final class LogisticsService {

    private static final float LOW_HUNGER_THRESHOLD = 10.0F;
    private static final double FULL_INVENTORY_THRESHOLD = 0.85D;
    private static final double CHEST_INTERACT_EDGE_DIST = 1.25D;
    private static final double CHEST_INTERACT_VERTICAL_TOLERANCE = 1.6D;
    private static final int FOOD_RESTOCK_TARGET = 16;
    private static final int ARROW_RESTOCK_TARGET = 3 * 64;
    private static final int SCAFFOLD_RESTOCK_TARGET = 64;
    private static final long CHEST_BACKOFF_TICKS = 100L;

    private LogisticsService() {}

    public static boolean needsLogistics(AutomatonAIContext ctx) {
        AutomatonTaskConfig cfg = ctx.getTaskConfig();
        if (ctx.isLogisticsOnBackoff(ctx.getWorldTime())) {
            return false;
        }
        EntityAutomaton bot = ctx.getAutomaton();
        com.x4yi.automata.api.JobDescriptor descriptor = com.x4yi.automata.api.AutomataAPI.getJob(cfg.getJobId());
        boolean logisticsEligible = descriptor == null
                ? !com.x4yi.automata.api.AutomatonBuiltInJobs.COURIER.equals(cfg.getJobId())
                : descriptor.isLogisticsEligible();
        if (logisticsEligible
                && cfg.getSavingChest() != null
                && isMostlyFull(bot.getInventory().getMainInventory())
                && hasDepositableItems(bot.getInventory().getMainInventory(), cfg)) {
            return true;
        }
        if (cfg.getSourceChest() == null) {
            return false;
        }
        if (countMatching(bot.getInventory().getCombinedInventory(), s -> com.x4yi.automata.inventory.AutomatonInventory.scoreFood(s) > 0.0F) < 4) {
            return true;
        }
        if (needsToolForDescriptor(bot, descriptor, cfg.getJobId())) {
            return true;
        }
        if (hasBowOrCrossbow(bot) && countMatching(bot.getInventory().getCombinedInventory(), LogisticsService::isSafeAmmo) < 16) {
            return true;
        }
        if (!hasItemMatching(bot.getInventory().getCombinedInventory(), s -> s.getItem() == net.minecraft.init.Items.WATER_BUCKET)
                && bot.getHeldItemMainhand().getItem() != net.minecraft.init.Items.WATER_BUCKET
                && bot.getHeldItemOffhand().getItem() != net.minecraft.init.Items.WATER_BUCKET) {
            return true;
        }
        return cfg.isAllowScaffolding() && EquipmentSelector.countScaffoldBlocks(bot) < 16;
    }

    public static boolean handleLogistics(AutomatonAIContext ctx) {
        long now = ctx.getWorldTime();
        if (ctx.isLogisticsOnBackoff(now)) {
            return false;
        }
        EntityAutomaton bot = ctx.getAutomaton();
        AutomatonTaskConfig cfg = ctx.getTaskConfig();
        com.x4yi.automata.api.JobDescriptor descriptor = com.x4yi.automata.api.AutomataAPI.getJob(cfg.getJobId());
        boolean logisticsEligible = descriptor == null
                ? !com.x4yi.automata.api.AutomatonBuiltInJobs.COURIER.equals(cfg.getJobId())
                : descriptor.isLogisticsEligible();

        if (logisticsEligible
                && cfg.getSavingChest() != null
                && isMostlyFull(bot.getInventory().getMainInventory())
                && hasDepositableItems(bot.getInventory().getMainInventory(), cfg)) {
            boolean handled = visitAndDeposit(ctx, cfg.getSavingChest(), bot.getInventory().getMainInventory());
            if (!handled) {
                ctx.triggerLogisticsBackoff(now, CHEST_BACKOFF_TICKS);
            }
            return handled;
        }

        if (cfg.getSourceChest() != null) {
            boolean handled = visitAndRestockFromSource(ctx, cfg.getSourceChest(), descriptor, cfg);
            if (!handled) {
                ctx.triggerLogisticsBackoff(now, CHEST_BACKOFF_TICKS);
            }
            return handled;
        }

        return false;
    }

    public static double computeOccupancyRatio(int occupiedSlots, int totalSlots) {
        if (totalSlots <= 0) {
            return 0.0D;
        }
        return (double) occupiedSlots / (double) totalSlots;
    }

    public static boolean isMostlyFull(IItemHandler handler) {
        return getInventoryOccupancyRatio(handler) >= FULL_INVENTORY_THRESHOLD;
    }

    public static float getInventoryOccupancyRatio(IItemHandler handler) {
        int total = handler.getSlots();
        int occupied = 0;
        for (int i = 0; i < total; i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                occupied++;
            }
        }
        return (float) computeOccupancyRatio(occupied, total);
    }

    private static boolean hasItemMatching(IItemHandler handler, Predicate<ItemStack> predicate) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visitAndRestockFromSource(
            AutomatonAIContext ctx,
            BlockPos chestPos,
            @Nullable com.x4yi.automata.api.JobDescriptor descriptor,
            AutomatonTaskConfig cfg) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (ctx.isGoalOnCooldown(chestPos, ctx.getWorldTime())) {
            return false;
        }
        bot.getChunkLoader().setExtraWorkChunk(chestPos);
        if (!moveWithinChestReach(ctx, bot, chestPos)) {
            return true;
        }
        ctx.stopNavigation();
        ctx.getMovement().lookAtBlockFace(chestPos);
        IItemHandler chest = resolveItemHandler(ctx.getWorld(), chestPos);
        if (chest == null || chest.getSlots() <= 0) {
            return false;
        }
        boolean anyChanged = false;
        anyChanged |= upgradeArmorAndOffhandFromChest(bot, chest);
        anyChanged |= upgradeTaskEquipmentFromChest(bot, chest, descriptor, cfg.getJobId());
        anyChanged |= restockConsumablesFromChest(bot, chest, cfg);
        return anyChanged;
    }

    static boolean upgradeArmorAndOffhandFromChest(EntityAutomaton bot, IItemHandler chest) {
        boolean changed = false;
        net.minecraft.inventory.EntityEquipmentSlot[] armorSlots = {
                net.minecraft.inventory.EntityEquipmentSlot.FEET,
                net.minecraft.inventory.EntityEquipmentSlot.LEGS,
                net.minecraft.inventory.EntityEquipmentSlot.CHEST,
                net.minecraft.inventory.EntityEquipmentSlot.HEAD
        };
        for (net.minecraft.inventory.EntityEquipmentSlot slot : armorSlots) {
            ItemStack current = bot.getItemStackFromSlot(slot);
            float currentScore = getEffectiveArmorScore(current, slot);
            int bestIdx = -1;
            float bestScore = currentScore + 0.01F;
            for (int i = 0; i < chest.getSlots(); i++) {
                ItemStack candidate = chest.getStackInSlot(i);
                if (candidate.isEmpty() || !isArmorForSlot(candidate, slot)) {
                    continue;
                }
                float score = getEffectiveArmorScore(candidate, slot);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                ItemStack extracted = chest.extractItem(bestIdx, 1, false);
                if (!extracted.isEmpty()) {
                    bot.setItemStackToSlot(slot, extracted);
                    if (!current.isEmpty()) {
                        ItemStack rem = ItemHandlerHelper.insertItemStacked(chest, current.copy(), false);
                        if (!rem.isEmpty()) {
                            ItemStack invRem = ItemHandlerHelper.insertItemStacked(
                                    bot.getInventory().getCombinedInventory(), rem, false);
                            if (!invRem.isEmpty()) {
                                bot.entityDropItem(invRem, 0.0F);
                            }
                        }
                    }
                    changed = true;
                }
            }
        }
        if (bot.getHeldItemOffhand().isEmpty()
                && !com.x4yi.automata.compat.SpartanWeaponryCompat.isTwoHanded(bot.getHeldItemMainhand())) {
            for (int i = 0; i < chest.getSlots(); i++) {
                ItemStack candidate = chest.getStackInSlot(i);
                if (!candidate.isEmpty()
                        && (candidate.getItem() == net.minecraft.init.Items.TOTEM_OF_UNDYING
                                || EquipmentSelector.isShieldItem(candidate))) {
                    ItemStack extracted = chest.extractItem(i, 1, false);
                    if (!extracted.isEmpty()) {
                        bot.setHeldItem(net.minecraft.util.EnumHand.OFF_HAND, extracted);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return changed;
    }

    static boolean upgradeTaskEquipmentFromChest(
            EntityAutomaton bot,
            IItemHandler chest,
            @Nullable com.x4yi.automata.api.JobDescriptor descriptor,
            net.minecraft.util.ResourceLocation jobId) {
        boolean combatTask = com.x4yi.automata.api.AutomatonBuiltInJobs.GUARD.equals(jobId)
                || com.x4yi.automata.api.AutomatonBuiltInJobs.AGGRESSIVE_GUARD.equals(jobId)
                || com.x4yi.automata.api.AutomatonBuiltInJobs.ROAM.equals(jobId);
        if (combatTask) {
            double bestOwned = Math.max(0.0D, SquadSupplyService.scoreWeapon(bot.getHeldItemMainhand()));
            IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
            for (int i = 0; i < inv.getSlots(); i++) {
                bestOwned = Math.max(bestOwned, SquadSupplyService.scoreWeapon(inv.getStackInSlot(i)));
            }
            int bestChestIdx = -1;
            double bestChestScore = bestOwned <= 0.0D ? 0.0D : bestOwned + 0.5D;
            for (int i = 0; i < chest.getSlots(); i++) {
                double score = SquadSupplyService.scoreWeapon(chest.getStackInSlot(i));
                if (score > bestChestScore) {
                    bestChestScore = score;
                    bestChestIdx = i;
                }
            }
            if (bestChestIdx >= 0) {
                ItemStack extracted = chest.extractItem(bestChestIdx, 1, false);
                if (!extracted.isEmpty()) {
                    ItemStack prevMain = bot.getHeldItemMainhand().copy();
                    bot.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND, extracted);
                    if (!prevMain.isEmpty()) {
                        ItemStack rem = SquadSupplyService.scoreWeapon(prevMain) > 0.0D
                                ? ItemHandlerHelper.insertItemStacked(chest, prevMain, false)
                                : ItemHandlerHelper.insertItemStacked(inv, prevMain, false);
                        if (!rem.isEmpty()) {
                            bot.entityDropItem(rem, 0.0F);
                        }
                    }
                    EquipmentSelector.equipBestWeapon(bot);
                    return true;
                }
            }
            return false;
        }
        if (needsToolForDescriptor(bot, descriptor, jobId)) {
            int pulled = pullMatchingFromChest(
                    bot,
                    chest,
                    stack -> isToolForDescriptor(stack, descriptor, jobId) && EquipmentSelector.hasSafeDurability(stack),
                    1);
            return pulled > 0;
        }
        return false;
    }

    private static boolean restockConsumablesFromChest(
            EntityAutomaton bot,
            IItemHandler chest,
            AutomatonTaskConfig cfg) {
        boolean changed = false;
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        int foodCount = countMatching(inv, s -> com.x4yi.automata.inventory.AutomatonInventory.scoreFood(s) > 0.0F);
        if (foodCount < 4) {
            changed |= pullMatchingFromChest(
                    bot,
                    chest,
                    s -> com.x4yi.automata.inventory.AutomatonInventory.scoreFood(s) > 0.0F,
                    FOOD_RESTOCK_TARGET - foodCount) > 0;
        }
        if (hasBowOrCrossbow(bot)) {
            int ammoCount = countMatching(inv, LogisticsService::isSafeAmmo);
            if (ammoCount < 16) {
                changed |= pullMatchingFromChest(bot, chest, LogisticsService::isSafeAmmo, ARROW_RESTOCK_TARGET - ammoCount) > 0;
            }
        }
        if (!hasItemMatching(inv, s -> s.getItem() == net.minecraft.init.Items.WATER_BUCKET)
                && bot.getHeldItemMainhand().getItem() != net.minecraft.init.Items.WATER_BUCKET
                && bot.getHeldItemOffhand().getItem() != net.minecraft.init.Items.WATER_BUCKET) {
            changed |= pullMatchingFromChest(bot, chest, s -> s.getItem() == net.minecraft.init.Items.WATER_BUCKET, 1) > 0;
        }
        if (cfg.isAllowScaffolding()) {
            int blocks = EquipmentSelector.countScaffoldBlocks(bot);
            if (blocks < 16) {
                changed |= pullMatchingFromChest(
                        bot, chest, com.x4yi.automata.movement.BlockPlacementService::isValidScaffoldBlock, SCAFFOLD_RESTOCK_TARGET - blocks) > 0;
            }
        }
        if (com.x4yi.automata.api.AutomatonBuiltInJobs.FARMER.equals(cfg.getJobId())) {
            int seeds = countMatching(inv, s -> s.getItem() instanceof net.minecraftforge.common.IPlantable);
            if (seeds < 16) {
                changed |= pullMatchingFromChest(
                        bot, chest, s -> s.getItem() instanceof net.minecraftforge.common.IPlantable, 32 - seeds) > 0;
            }
        }
        return changed;
    }

    private static int pullMatchingFromChest(
            EntityAutomaton bot,
            IItemHandler chest,
            Predicate<ItemStack> matcher,
            int maxItemsToPull) {
        if (maxItemsToPull <= 0) {
            return 0;
        }
        IItemHandlerModifiable botInv = bot.getInventory().getCombinedInventory();
        int pulled = 0;
        for (int i = 0; i < chest.getSlots() && pulled < maxItemsToPull; i++) {
            ItemStack simulated = chest.extractItem(i, maxItemsToPull - pulled, true);
            if (simulated.isEmpty() || !matcher.test(simulated)) {
                continue;
            }
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(botInv, simulated, true);
            int fit = simulated.getCount() - remainder.getCount();
            if (fit > 0) {
                ItemStack extracted = chest.extractItem(i, fit, false);
                ItemHandlerHelper.insertItemStacked(botInv, extracted, false);
                pulled += fit;
            }
        }
        return pulled;
    }

    private static boolean isArmorForSlot(ItemStack stack, net.minecraft.inventory.EntityEquipmentSlot slot) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof net.minecraft.item.ItemArmor armor) {
            return armor.armorType == slot;
        }
        return stack.getItem().isValidArmor(stack, slot, null);
    }

    private static float getEffectiveArmorScore(ItemStack stack, net.minecraft.inventory.EntityEquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0F;
        }
        float score = EquipmentSelector.getArmorScore(stack);
        if (score > 0.0F) {
            return score;
        }
        double armor = 0.0D;
        double toughness = 0.0D;
        for (net.minecraft.entity.ai.attributes.AttributeModifier mod : stack.getAttributeModifiers(slot)
                .get(net.minecraft.entity.SharedMonsterAttributes.ARMOR.getName())) {
            armor += mod.getAmount();
        }
        for (net.minecraft.entity.ai.attributes.AttributeModifier mod : stack.getAttributeModifiers(slot)
                .get(net.minecraft.entity.SharedMonsterAttributes.ARMOR_TOUGHNESS.getName())) {
            toughness += mod.getAmount();
        }
        return (float) (armor * 10.0D + toughness * 4.0D);
    }

    private static boolean hasBowOrCrossbow(EntityAutomaton bot) {
        ItemStack main = bot.getHeldItemMainhand();
        if (EquipmentSelector.isBowItem(main) || com.x4yi.automata.compat.SpartanWeaponryCompat.isCrossbow(main)) {
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (EquipmentSelector.isBowItem(s) || com.x4yi.automata.compat.SpartanWeaponryCompat.isCrossbow(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeAmmo(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        boolean isAmmo = stack.getItem() instanceof net.minecraft.item.ItemArrow
                || com.x4yi.automata.compat.SpartanWeaponryCompat.isBoltItem(stack);
        if (!isAmmo) {
            return false;
        }
        return stack.getItem().getRegistryName() == null
                || !stack.getItem().getRegistryName().getPath().toLowerCase(java.util.Locale.ROOT).contains("explosive");
    }

    private static int countMatching(IItemHandler handler, Predicate<ItemStack> predicate) {
        int count = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean visitAndPull(
            AutomatonAIContext ctx,
            BlockPos chestPos,
            Predicate<ItemStack> matcher,
            int maxItemsToPull) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (ctx.isGoalOnCooldown(chestPos, ctx.getWorldTime())) {
            return false;
        }
        bot.getChunkLoader().setExtraWorkChunk(chestPos);
        if (!moveWithinChestReach(ctx, bot, chestPos)) {
            return true;
        }
        ctx.stopNavigation();
        ctx.getMovement().lookAtBlockFace(chestPos);
        IItemHandler chest = resolveItemHandler(ctx.getWorld(), chestPos);
        if (chest == null || chest.getSlots() <= 0) {
            return false;
        }
        IItemHandlerModifiable botInv = bot.getInventory().getCombinedInventory();
        int pulled = 0;
        for (int i = 0; i < chest.getSlots() && pulled < maxItemsToPull; i++) {
            ItemStack simulated = chest.extractItem(i, maxItemsToPull - pulled, true);
            if (simulated.isEmpty() || !matcher.test(simulated)) {
                continue;
            }
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(botInv, simulated, true);
            int fit = simulated.getCount() - remainder.getCount();
            if (fit > 0) {
                ItemStack extracted = chest.extractItem(i, fit, false);
                ItemHandlerHelper.insertItemStacked(botInv, extracted, false);
                pulled += fit;
            }
        }
        return pulled > 0;
    }

    public static boolean tryDepositSurplus(AutomatonAIContext ctx) {
        BlockPos depositPos = ctx.getTaskConfig().getDepositChest();
        if (depositPos == null) {
            return false;
        }
        return visitAndDeposit(ctx, depositPos, ctx.getAutomaton().getInventory().getMainInventory());
    }

    private static boolean visitAndDeposit(
            AutomatonAIContext ctx,
            BlockPos chestPos,
            IItemHandlerModifiable main) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (ctx.isGoalOnCooldown(chestPos, ctx.getWorldTime())) {
            return false;
        }
        bot.getChunkLoader().setExtraWorkChunk(chestPos);
        if (!moveWithinChestReach(ctx, bot, chestPos)) {
            return true;
        }
        ctx.stopNavigation();
        ctx.getMovement().lookAtBlockFace(chestPos);
        IItemHandler dest = resolveItemHandler(ctx.getWorld(), chestPos);
        if (dest == null || dest.getSlots() <= 0) {
            return false;
        }
        AutomatonTaskConfig cfg = ctx.getTaskConfig();
        boolean keptFood = false;
        boolean keptBlock = false;
        boolean movedAny = false;
        for (int i = 0; i < main.getSlots(); i++) {
            ItemStack stack = main.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            boolean isFood = stack.getItem() instanceof ItemFood;
            boolean isBlock = stack.getItem() instanceof ItemBlock;
            boolean keep = InventoryManagerService.shouldKeepItem(
                    stack,
                    cfg.getJobId(),
                    cfg.isAllowScaffolding(),
                    isFood && !keptFood,
                    isBlock && !keptBlock);
            if (isFood && !keptFood) {
                keptFood = true;
            }
            if (isBlock && !keptBlock) {
                keptBlock = true;
            }
            if (keep) {
                continue;
            }
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(dest, stack.copy(), false);
            int moved = stack.getCount() - remainder.getCount();
            if (moved > 0) {
                main.extractItem(i, moved, false);
                movedAny = true;
            }
        }
        return movedAny;
    }

    static boolean hasDepositableItems(IItemHandler main, AutomatonTaskConfig cfg) {
        boolean keptFood = false;
        boolean keptBlock = false;
        for (int i = 0; i < main.getSlots(); i++) {
            ItemStack stack = main.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            boolean isFood = stack.getItem() instanceof ItemFood;
            boolean isBlock = stack.getItem() instanceof ItemBlock;
            boolean keep = InventoryManagerService.shouldKeepItem(
                    stack,
                    cfg.getJobId(),
                    cfg.isAllowScaffolding(),
                    isFood && !keptFood,
                    isBlock && !keptBlock);
            if (isFood && !keptFood) {
                keptFood = true;
            }
            if (isBlock && !keptBlock) {
                keptBlock = true;
            }
            if (!keep) {
                return true;
            }
        }
        return false;
    }

    private static boolean moveWithinChestReach(AutomatonAIContext ctx, EntityAutomaton bot, BlockPos chestPos) {
        World world = ctx.getWorld();
        if (isWithinChestReach(bot.posX, bot.posY, bot.posZ, world, chestPos)) {
            return true;
        }
        BlockPos stand = findChestStandingSpot(world, bot, chestPos);
        if (stand != null && bot.getDistanceSqToCenter(stand) <= 6.25D) {
            ctx.getMovement().moveDirectlyToward(stand.getX() + 0.5D, stand.getZ() + 0.5D, 0.85F, false);
            return false;
        }
        ctx.requestPathTo(stand != null ? stand : chestPos, false);
        return false;
    }

    static boolean isWithinChestReach(
            double botX,
            double botY,
            double botZ,
            @Nullable World world,
            BlockPos chestPos) {
        if (isWithinOneBlockEdge(botX, botY, botZ, chestPos)) {
            return true;
        }
        if (world != null) {
            BlockPos partner = findConnectedChestPartner(world, chestPos);
            if (partner != null && isWithinOneBlockEdge(botX, botY, botZ, partner)) {
                return true;
            }
        }
        return false;
    }

    static boolean isWithinOneBlockEdge(double botX, double botY, double botZ, BlockPos blockPos) {
        double dx = Math.max(0.0D, Math.max(blockPos.getX() - botX, botX - (blockPos.getX() + 1.0D)));
        double dz = Math.max(0.0D, Math.max(blockPos.getZ() - botZ, botZ - (blockPos.getZ() + 1.0D)));
        double dy = Math.abs(botY - blockPos.getY());
        return (dx * dx + dz * dz) <= (CHEST_INTERACT_EDGE_DIST * CHEST_INTERACT_EDGE_DIST)
                && dy <= CHEST_INTERACT_VERTICAL_TOLERANCE;
    }

    @Nullable
    private static BlockPos findConnectedChestPartner(World world, BlockPos pos) {
        if (!world.isBlockLoaded(pos)) {
            return null;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityChest chest) {
            chest.checkForAdjacentChests();
            if (chest.adjacentChestXNeg != null) {
                return chest.adjacentChestXNeg.getPos();
            }
            if (chest.adjacentChestXPos != null) {
                return chest.adjacentChestXPos.getPos();
            }
            if (chest.adjacentChestZNeg != null) {
                return chest.adjacentChestZNeg.getPos();
            }
            if (chest.adjacentChestZPos != null) {
                return chest.adjacentChestZPos.getPos();
            }
        } else if (te != null) {
            net.minecraft.block.Block block = world.getBlockState(pos).getBlock();
            for (EnumFacing facing : EnumFacing.HORIZONTALS) {
                BlockPos adj = pos.offset(facing);
                if (world.isBlockLoaded(adj)
                        && world.getBlockState(adj).getBlock() == block
                        && world.getTileEntity(adj) != null) {
                    return adj;
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findChestStandingSpot(World world, EntityAutomaton bot, BlockPos chestPos) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos[] anchors = new BlockPos[] { chestPos, findConnectedChestPartner(world, chestPos) };
        for (BlockPos anchor : anchors) {
            if (anchor == null) {
                continue;
            }
            for (int dy : new int[] { 0, -1, 1 }) {
                for (EnumFacing facing : EnumFacing.HORIZONTALS) {
                    BlockPos candidate = anchor.offset(facing).add(0, dy, 0);
                    if (!world.isBlockLoaded(candidate)) {
                        continue;
                    }
                    if (!world.getBlockState(candidate.down()).getMaterial().isSolid()) {
                        continue;
                    }
                    if (!world.isAirBlock(candidate) && world.getBlockState(candidate).getCollisionBoundingBox(world, candidate) != null) {
                        continue;
                    }
                    if (!world.isAirBlock(candidate.up()) && world.getBlockState(candidate.up()).getCollisionBoundingBox(world, candidate.up()) != null) {
                        continue;
                    }
                    double d = bot.getDistanceSqToCenter(candidate);
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    @Nullable
    public static IItemHandler resolveItemHandler(World world, BlockPos pos) {
        if (world == null || pos == null || !world.isBlockLoaded(pos)) {
            return null;
        }
        TileEntity te = world.getTileEntity(pos);
        IItemHandler direct = resolveItemHandler(te);
        if (direct != null) {
            return direct;
        }
        return null;
    }

    @Nullable
    public static IItemHandler resolveItemHandler(TileEntity te) {
        if (te == null) {
            return null;
        }
        if (te instanceof TileEntityChest chest) {
            VanillaDoubleChestItemHandler doubleHandler = VanillaDoubleChestItemHandler.get(chest);
            if (doubleHandler != null && doubleHandler != VanillaDoubleChestItemHandler.NO_ADJACENT_CHESTS_INSTANCE) {
                return doubleHandler;
            }
        }
        if (te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (handler != null) {
                return handler;
            }
        }
        for (EnumFacing face : EnumFacing.VALUES) {
            if (te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, face)) {
                IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, face);
                if (handler != null) {
                    return handler;
                }
            }
        }
        if (te instanceof ISidedInventory sided) {
            return new SidedInvWrapper(sided, EnumFacing.UP);
        }
        if (te instanceof IInventory inv) {
            return new InvWrapper(inv);
        }
        return null;
    }

    private static boolean needsToolForDescriptor(
            EntityAutomaton bot,
            @Nullable com.x4yi.automata.api.JobDescriptor descriptor,
            net.minecraft.util.ResourceLocation fallbackJobId) {
        if (descriptor != null) {
            if (!descriptor.needsTool(bot) || !descriptor.hasToolMatcher()) {
                return false;
            }
            ItemStack main = bot.getHeldItemMainhand();
            if (!main.isEmpty() && descriptor.matchesTool(main)) {
                return false;
            }
            return !hasItemMatching(bot.getInventory().getCombinedInventory(), descriptor::matchesTool);
        }
        return needsToolForJob(bot, fallbackJobId);
    }

    private static boolean isToolForDescriptor(
            ItemStack stack,
            @Nullable com.x4yi.automata.api.JobDescriptor descriptor,
            net.minecraft.util.ResourceLocation fallbackJobId) {
        if (descriptor != null && descriptor.hasToolMatcher()) {
            return descriptor.matchesTool(stack);
        }
        return isToolForJob(stack, fallbackJobId);
    }

    private static boolean needsToolForJob(EntityAutomaton bot, net.minecraft.util.ResourceLocation jobId) {
        if (com.x4yi.automata.api.AutomatonBuiltInJobs.IDLE.equals(jobId)
                || com.x4yi.automata.api.AutomatonBuiltInJobs.COURIER.equals(jobId)) {
            return false;
        }
        ItemStack main = bot.getHeldItemMainhand();
        if (!main.isEmpty() && isToolForJob(main, jobId)) {
            return false;
        }
        return !hasItemMatching(bot.getInventory().getCombinedInventory(), s -> isToolForJob(s, jobId));
    }

    private static boolean isToolForJob(ItemStack stack, net.minecraft.util.ResourceLocation jobId) {
        if (com.x4yi.automata.api.AutomatonBuiltInJobs.GUARD.equals(jobId)
                || com.x4yi.automata.api.AutomatonBuiltInJobs.AGGRESSIVE_GUARD.equals(jobId)) {
            return EquipmentSelector.isCombatWeapon(stack);
        }
        if (com.x4yi.automata.api.AutomatonBuiltInJobs.FARMER.equals(jobId)) {
            return EquipmentSelector.isHoe(stack);
        }
        if (com.x4yi.automata.api.AutomatonBuiltInJobs.EXCAVATION.equals(jobId)
                || com.x4yi.automata.api.AutomatonBuiltInJobs.MINE.equals(jobId)
                || com.x4yi.automata.api.AutomatonBuiltInJobs.LUMBERJACK.equals(jobId)) {
            return stack.getItem() instanceof ItemTool || EquipmentSelector.isAxe(stack);
        }
        return false;
    }
}

