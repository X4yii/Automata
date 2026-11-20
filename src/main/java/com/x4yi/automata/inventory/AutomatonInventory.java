
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

import com.x4yi.automata.compat.UsefulBackpacksCompat;

public class AutomatonInventory {

    public static final int MAIN_SLOTS = 27;
    public static final int BAUBLE_SLOTS = com.x4yi.automata.compat.BaublesCompat.BAUBLE_SLOT_COUNT;

    private final ItemStackHandler mainInventory = new ItemStackHandler(MAIN_SLOTS);
    private final ItemStackHandler baublesInventory = new ItemStackHandler(BAUBLE_SLOTS) {
        @Override
        public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
            return com.x4yi.automata.compat.BaublesCompat.canEquipInSlot(stack, slot, null);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    };
    @Nullable
    private ItemStack cachedBackpackRef = ItemStack.EMPTY;
    @Nullable
    private UsefulBackpackItemHandler cachedBackpackHandler;
    private final ItemStackHandler backpackSlot = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
            return isBackpackItem(stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        protected void onContentsChanged(int slot) {
            invalidateBackpackCache();
        }
    };

    public static boolean isBackpackItem(@Nonnull ItemStack stack) {
        return UsefulBackpacksCompat.isUsefulBackpack(stack);
    }

    private void invalidateBackpackCache() {
        cachedBackpackRef = ItemStack.EMPTY;
        cachedBackpackHandler = null;
    }

    public ItemStackHandler getMainInventory() {
        return mainInventory;
    }

    public ItemStackHandler getBackpackSlot() {
        return backpackSlot;
    }

    public ItemStackHandler getBaublesInventory() {
        return baublesInventory;
    }

    @Nonnull
    public ItemStack getEquippedBackpack() {
        return backpackSlot.getStackInSlot(0);
    }

    @Nullable
    public IItemHandlerModifiable getBackpackHandler() {
        ItemStack backpack = getEquippedBackpack();
        if (backpack.isEmpty() || !UsefulBackpacksCompat.isUsefulBackpack(backpack)) {
            invalidateBackpackCache();
            return null;
        }
        if (cachedBackpackHandler != null && cachedBackpackRef == backpack) {
            return cachedBackpackHandler;
        }
        cachedBackpackRef = backpack;
        cachedBackpackHandler = new UsefulBackpackItemHandler(backpack);
        return cachedBackpackHandler;
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
        int bestSlot = -1;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < combined.getSlots(); i++) {
            ItemStack stack = combined.getStackInSlot(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof ItemFood)) {
                continue;
            }
            float score = scoreFood(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) {
            ItemStack extracted = combined.extractItem(bestSlot, 1, false);
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    public static float scoreFood(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemFood food)) {
            return Float.NEGATIVE_INFINITY;
        }
        int hunger = food.getHealAmount(stack);
        float saturationRestored = hunger * food.getSaturationModifier(stack) * 2.0F;
        float score = saturationRestored * 10.0F + hunger;
        net.minecraft.item.Item item = stack.getItem();
        if (item == net.minecraft.init.Items.ROTTEN_FLESH
                || item == net.minecraft.init.Items.SPIDER_EYE
                || item == net.minecraft.init.Items.POISONOUS_POTATO
                || (item == net.minecraft.init.Items.FISH && stack.getMetadata() == 3)) {
            score -= 1000.0F;
        }
        return score;
    }

    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Main", mainInventory.serializeNBT());
        tag.setTag("Backpack", backpackSlot.serializeNBT());
        tag.setTag("Baubles", baublesInventory.serializeNBT());
        return tag;
    }

    public void deserializeNBT(NBTTagCompound tag) {
        if (tag.hasKey("Main")) {
            mainInventory.deserializeNBT(tag.getCompoundTag("Main"));
        }
        if (tag.hasKey("Backpack")) {
            backpackSlot.deserializeNBT(tag.getCompoundTag("Backpack"));
        }
        if (tag.hasKey("Baubles")) {
            baublesInventory.deserializeNBT(tag.getCompoundTag("Baubles"));
        }
    }
}

