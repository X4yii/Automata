package com.x4yi.automata.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EquipmentSelectorTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void detectsVanillaAndModdedShieldAndBowItemsByClassOrUseAction() {
        assertFalse(EquipmentSelector.isShieldItem(ItemStack.EMPTY));
        assertFalse(EquipmentSelector.isBowItem(ItemStack.EMPTY));

        assertTrue(EquipmentSelector.isShieldItem(new ItemStack(Items.SHIELD)));
        assertTrue(EquipmentSelector.isBowItem(new ItemStack(Items.BOW)));

        Item customShield = new Item() {
            @Override
            public EnumAction getItemUseAction(ItemStack stack) {
                return EnumAction.BLOCK;
            }
        };
        Item customBow = new Item() {
            @Override
            public EnumAction getItemUseAction(ItemStack stack) {
                return EnumAction.BOW;
            }
        };

        assertTrue(EquipmentSelector.isShieldItem(new ItemStack(customShield)));
        assertFalse(EquipmentSelector.isBowItem(new ItemStack(customShield)));
        assertTrue(EquipmentSelector.isBowItem(new ItemStack(customBow)));
        assertFalse(EquipmentSelector.isShieldItem(new ItemStack(customBow)));
    }
}

