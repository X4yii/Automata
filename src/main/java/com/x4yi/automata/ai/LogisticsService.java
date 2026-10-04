
package com.x4yi.automata.ai;

import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;

import com.x4yi.automata.entity.EntityAutomaton;

public final class LogisticsService {

    private static final float LOW_HUNGER_THRESHOLD = 10.0F;
    private static final double FULL_INVENTORY_THRESHOLD = 0.85D;
    private static final double CHEST_REACH_SQ = 20.25D;
    private static final int FOOD_RESTOCK_TARGET = 8;
    private static final long CHEST_BACKOFF_TICKS = 100L;

    private LogisticsService() {}

    public static boolean handleLogistics(AutomatonAIContext ctx) {
        long now = ctx.getWorldTime();
        if (ctx.isLogisticsOnBackoff(now)) {
            return false;
        }
        EntityAutomaton bot = ctx.getAutomaton();
        AutomatonTaskConfig cfg = ctx.getTaskConfig();

        if (bot.getHunger() <= LOW_HUNGER_THRESHOLD
                && cfg.getFoodChest() != null
                && !hasItemMatching(bot.getInventory().getCombinedInventory(), stack -> stack.getItem() instanceof ItemFood)) {
            boolean handled = visitAndPull(ctx, cfg.getFoodChest(), stack -> stack.getItem() instanceof ItemFood, FOOD_RESTOCK_TARGET);
            if (!handled) {
                ctx.triggerLogisticsBackoff(now, CHEST_BACKOFF_TICKS);
            }
            return handled;
        }

        com.x4yi.automata.api.JobDescriptor descriptor = com.x4yi.automata.api.AutomataAPI.getJob(cfg.getJobId());
        boolean logisticsEligible = descriptor == null
                ? cfg.getJobType() != AutomatonJobType.COURIER
                : descriptor.isLogisticsEligible();
        if (logisticsEligible && cfg.getDepositChest() != null) {
            IItemHandlerModifiable main = bot.getInventory().getMainInventory();
            if (isMostlyFull(main)) {
                boolean handled = visitAndDeposit(ctx, cfg.getDepositChest(), main);
                if (!handled) {
                    ctx.triggerLogisticsBackoff(now, CHEST_BACKOFF_TICKS);
                }
                return handled;
            }
        }

        if (logisticsEligible
                && cfg.getToolChest() != null
                && needsToolForDescriptor(bot, descriptor, cfg.getJobType())) {
            boolean handled = visitAndPull(
                    ctx,
                    cfg.getToolChest(),
                    stack -> isToolForDescriptor(stack, descriptor, cfg.getJobType()),
                    1);
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

    private static boolean visitAndPull(
            AutomatonAIContext ctx,
            BlockPos chestPos,
            Predicate<ItemStack> matcher,
            int maxItemsToPull) {
        EntityAutomaton bot = ctx.getAutomaton();
        bot.getChunkLoader().setExtraWorkChunk(chestPos);
        if (bot.getDistanceSqToCenter(chestPos) > CHEST_REACH_SQ) {
            ctx.requestPathTo(chestPos, false);
            return true;
        }
        ctx.stopNavigation();
        IItemHandler chest = resolveItemHandler(ctx.getWorld().getTileEntity(chestPos));
        if (chest == null) {
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
        bot.getChunkLoader().setExtraWorkChunk(chestPos);
        if (bot.getDistanceSqToCenter(chestPos) > CHEST_REACH_SQ) {
            ctx.requestPathTo(chestPos, false);
            return true;
        }
        ctx.stopNavigation();
        IItemHandler dest = resolveItemHandler(ctx.getWorld().getTileEntity(chestPos));
        if (dest == null) {
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
                    cfg.getJobType(),
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

    public static IItemHandler resolveItemHandler(TileEntity te) {
        if (te == null) {
            return null;
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
        if (te instanceof IInventory inv) {
            return new InvWrapper(inv);
        }
        return null;
    }

    private static boolean needsToolForDescriptor(
            EntityAutomaton bot,
            @Nullable com.x4yi.automata.api.JobDescriptor descriptor,
            AutomatonJobType fallbackType) {
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
        return needsToolForJob(bot, fallbackType);
    }

    private static boolean isToolForDescriptor(
            ItemStack stack,
            @Nullable com.x4yi.automata.api.JobDescriptor descriptor,
            AutomatonJobType fallbackType) {
        if (descriptor != null && descriptor.hasToolMatcher()) {
            return descriptor.matchesTool(stack);
        }
        return isToolForJob(stack, fallbackType);
    }

    private static boolean needsToolForJob(EntityAutomaton bot, AutomatonJobType jobType) {
        if (jobType == AutomatonJobType.IDLE || jobType == AutomatonJobType.COURIER) {
            return false;
        }
        ItemStack main = bot.getHeldItemMainhand();
        if (!main.isEmpty() && isToolForJob(main, jobType)) {
            return false;
        }
        return !hasItemMatching(bot.getInventory().getCombinedInventory(), s -> isToolForJob(s, jobType));
    }

    private static boolean isToolForJob(ItemStack stack, AutomatonJobType jobType) {
        return switch (jobType) {
            case DEFEND, CRAZY_DEFEND -> stack.getItem() instanceof ItemSword;
            case FARMER -> EquipmentSelector.isHoe(stack);
            case EXCAVATION, MINE, LUMBERJACK -> stack.getItem() instanceof ItemTool || EquipmentSelector.isAxe(stack);
            default -> false;
        };
    }
}

