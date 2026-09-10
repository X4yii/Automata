
package com.x4yi.automata.inventory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;

public class AutomatonInventory {

    public static final int MAIN_SLOTS = 27;

    private final ItemStackHandler mainInventory = new ItemStackHandler(MAIN_SLOTS);
    private final ItemStackHandler backpackSlot = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
            return isBackpackItem(stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    };

    public static boolean isBackpackItem(@Nonnull ItemStack stack) {
        return !stack.isEmpty() && stack.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
    }

    public ItemStackHandler getMainInventory() {
        return mainInventory;
    }

    public ItemStackHandler getBackpackSlot() {
        return backpackSlot;
    }

    @Nonnull
    public ItemStack getEquippedBackpack() {
        return backpackSlot.getStackInSlot(0);
    }

    @Nullable
    public IItemHandlerModifiable getBackpackHandler() {
        ItemStack backpack = getEquippedBackpack();
        if (backpack.isEmpty()) {
            return null;
        }
        IItemHandler handler = backpack.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        return handler instanceof IItemHandlerModifiable modifiable ? modifiable : null;
    }

    public IItemHandlerModifiable getCombinedInventory() {
        IItemHandlerModifiable backpackHandler = getBackpackHandler();
        if (backpackHandler != null) {
            return new CombinedInvWrapper(mainInventory, backpackHandler);
        }
        return mainInventory;
    }

    @Nonnull
    public ItemStack consumeOneFood() {
        IItemHandlerModifiable combined = getCombinedInventory();
        for (int i = 0; i < combined.getSlots(); i++) {
            ItemStack stack = combined.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() instanceof ItemFood) {
                ItemStack extracted = combined.extractItem(i, 1, false);
                if (!extracted.isEmpty()) {
                    return extracted;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Main", mainInventory.serializeNBT());
        tag.setTag("Backpack", backpackSlot.serializeNBT());
        return tag;
    }

    public void deserializeNBT(NBTTagCompound tag) {
        if (tag.hasKey("Main")) {
            mainInventory.deserializeNBT(tag.getCompoundTag("Main"));
        }
        if (tag.hasKey("Backpack")) {
            backpackSlot.deserializeNBT(tag.getCompoundTag("Backpack"));
        }
    }
}

