package com.x4yi.automata.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SpartanWeaponryCompatTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void readsThrowingAmmoAndCrossbowLoadedNbtSafely() {
        ItemStack stack = new ItemStack(Items.STICK);
        assertEquals(1, SpartanWeaponryCompat.getRemainingThrowingAmmo(stack));
        assertFalse(SpartanWeaponryCompat.isCrossbowLoaded(stack));

        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("Ammo", 0);
        nbt.setBoolean("Loaded", true);
        stack.setTagCompound(nbt);

        assertEquals(0, SpartanWeaponryCompat.getRemainingThrowingAmmo(stack));
        assertTrue(SpartanWeaponryCompat.isCrossbowLoaded(stack));

        ItemStack ammoUsedStack = new ItemStack(Items.STICK);
        NBTTagCompound usedNbt = new NBTTagCompound();
        usedNbt.setInteger("AmmoUsed", 1);
        ammoUsedStack.setTagCompound(usedNbt);
        assertEquals(0, SpartanWeaponryCompat.getRemainingThrowingAmmo(ammoUsedStack));

        ItemStack ammoStackCrossbow = new ItemStack(Items.STICK);
        NBTTagCompound cbNbt = new NBTTagCompound();
        cbNbt.setTag("ammoStack", new ItemStack(Items.ARROW).writeToNBT(new NBTTagCompound()));
        ammoStackCrossbow.setTagCompound(cbNbt);
        assertTrue(SpartanWeaponryCompat.isCrossbowLoaded(ammoStackCrossbow));

        // Adversarial bounds: extreme AmmoUsed > maxAmmo must clamp to 0, empty ammoStack must be unloaded.
        ItemStack overUsed = new ItemStack(Items.STICK);
        NBTTagCompound overNbt = new NBTTagCompound();
        overNbt.setInteger("AmmoUsed", 999);
        overUsed.setTagCompound(overNbt);
        assertEquals(0, SpartanWeaponryCompat.getRemainingThrowingAmmo(overUsed));

        ItemStack emptyAmmoCrossbow = new ItemStack(Items.STICK);
        NBTTagCompound emptyCbNbt = new NBTTagCompound();
        emptyCbNbt.setTag("ammoStack", ItemStack.EMPTY.writeToNBT(new NBTTagCompound()));
        emptyAmmoCrossbow.setTagCompound(emptyCbNbt);
        assertFalse(SpartanWeaponryCompat.isCrossbowLoaded(emptyAmmoCrossbow));
    }
}

