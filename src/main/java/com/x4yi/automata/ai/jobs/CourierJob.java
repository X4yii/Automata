
package com.x4yi.automata.ai.jobs;

import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.ai.TaskCompletionService;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.entity.EntityAutomaton;

public class CourierJob implements IAutomatonJob {

    @Override
    public ResourceLocation getJobId() {
        return AutomatonBuiltInJobs.COURIER;
    }

    @Override
    public void tick(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        ctx.stopNavigation();
    }

    private static boolean hasTransferableItems(IItemHandler inv) {
        boolean keptFoodReserve = false;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!keptFoodReserve && stack.getItem() instanceof ItemFood) {
                keptFoodReserve = true;
                continue;
            }
            return true;
        }
        return false;
    }

    private static void pullFromContainer(AutomatonAIContext ctx, BlockPos pos, IItemHandler botInv) {
        TileEntity te = ctx.getWorld().getTileEntity(pos);
        if (te == null || !te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            return;
        }
        IItemHandler source = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if (source == null) {
            return;
        }
        for (int i = 0; i < source.getSlots(); i++) {
            ItemStack simulated = source.extractItem(i, 64, true);
            if (simulated.isEmpty()) {
                continue;
            }
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(botInv, simulated, true);
            int fit = simulated.getCount() - remainder.getCount();
            if (fit > 0) {
                ItemStack extracted = source.extractItem(i, fit, false);
                ItemHandlerHelper.insertItemStacked(botInv, extracted, false);
            }
        }
    }

    private static void pushToContainer(AutomatonAIContext ctx, BlockPos pos, IItemHandlerModifiable botInv) {
        TileEntity te = ctx.getWorld().getTileEntity(pos);
        if (te == null || !te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            return;
        }
        IItemHandler dest = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if (dest == null) {
            return;
        }
        boolean keptFoodReserve = false;
        for (int i = 0; i < botInv.getSlots(); i++) {
            ItemStack stack = botInv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!keptFoodReserve && stack.getItem() instanceof ItemFood) {
                keptFoodReserve = true;
                continue;
            }
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(dest, stack.copy(), false);
            int moved = stack.getCount() - remainder.getCount();
            if (moved > 0) {
                botInv.extractItem(i, moved, false);
            }
        }
    }
}

