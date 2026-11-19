package com.x4yi.automata.inventory;

import javax.annotation.Nonnull;

import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;

import com.x4yi.automata.compat.UsefulBackpacksCompat;

public final class UsefulBackpackItemHandler implements IItemHandlerModifiable {

    private final ItemStack backpackStack;
    private final NonNullList<ItemStack> items;

    public UsefulBackpackItemHandler(@Nonnull ItemStack backpackStack) {
        this(backpackStack, UsefulBackpacksCompat.getBackpackDimensions(backpackStack)[2]);
    }

    UsefulBackpackItemHandler(@Nonnull ItemStack backpackStack, int slotCount) {
        this.backpackStack = backpackStack;
        this.items = NonNullList.withSize(Math.max(0, slotCount), ItemStack.EMPTY);
        readFromStack();
    }

    public ItemStack getBackpackStack() {
        return backpackStack;
    }

    public void readFromStack() {
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
        if (backpackStack.hasTagCompound() && backpackStack.getTagCompound() != null) {
            ItemStackHelper.loadAllItems(backpackStack.getTagCompound(), items);
        }
    }

    public void writeToStack() {
        if (backpackStack.isEmpty()) {
            return;
        }
        boolean empty = true;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                empty = false;
                break;
            }
        }
        if (empty) {
            if (backpackStack.hasTagCompound() && backpackStack.getTagCompound() != null) {
                NBTTagCompound nbt = backpackStack.getTagCompound();
                nbt.removeTag("Items");
                if (nbt.isEmpty()) {
                    backpackStack.setTagCompound(null);
                }
            }
            return;
        }
        NBTTagCompound nbt = backpackStack.getTagCompound();
        if (nbt == null) {
            nbt = new NBTTagCompound();
            backpackStack.setTagCompound(nbt);
        }
        ItemStackHelper.saveAllItems(nbt, items);
    }

    @Override
    public int getSlots() {
        return items.size();
    }

    @Nonnull
    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= items.size()) {
            return ItemStack.EMPTY;
        }
        return items.get(slot);
    }

    @Override
    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        if (slot < 0 || slot >= items.size()) {
            return;
        }
        items.set(slot, stack);
        writeToStack();
    }

    @Nonnull
    @Override
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || slot < 0 || slot >= items.size() || !isItemValid(slot, stack)) {
            return stack;
        }
        ItemStack existing = items.get(slot);
        int limit = getStackLimit(slot, stack);
        if (!existing.isEmpty()) {
            if (!ItemHandlerHelper.canItemStacksStack(stack, existing)) {
                return stack;
            }
            limit -= existing.getCount();
        }
        if (limit <= 0) {
            return stack;
        }
        boolean reachedLimit = stack.getCount() > limit;
        if (!simulate) {
            if (existing.isEmpty()) {
                items.set(slot, reachedLimit ? ItemHandlerHelper.copyStackWithSize(stack, limit) : stack);
            } else {
                existing.grow(reachedLimit ? limit : stack.getCount());
            }
            writeToStack();
        }
        return reachedLimit ? ItemHandlerHelper.copyStackWithSize(stack, stack.getCount() - limit) : ItemStack.EMPTY;
    }

    @Nonnull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0 || slot < 0 || slot >= items.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack existing = items.get(slot);
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int toExtract = Math.min(amount, existing.getMaxStackSize());
        if (existing.getCount() <= toExtract) {
            if (!simulate) {
                items.set(slot, ItemStack.EMPTY);
                writeToStack();
            }
            return existing.copy();
        }
        if (!simulate) {
            items.set(slot, ItemHandlerHelper.copyStackWithSize(existing, existing.getCount() - toExtract));
            writeToStack();
        }
        return ItemHandlerHelper.copyStackWithSize(existing, toExtract);
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    private int getStackLimit(int slot, @Nonnull ItemStack stack) {
        return Math.min(getSlotLimit(slot), stack.getMaxStackSize());
    }

    @Override
    public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
        if (stack.isEmpty() || slot < 0 || slot >= items.size()) {
            return false;
        }
        if (UsefulBackpacksCompat.isUsefulBackpack(stack) && !UsefulBackpacksCompat.isAllowStackingBackpacks()) {
            return false;
        }
        return true;
    }
}

