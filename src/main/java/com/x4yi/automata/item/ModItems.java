
package com.x4yi.automata.item;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.AutomataMod;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class ModItems {

    public static final ItemComidaConAlas COMIDA_CON_ALAS = new ItemComidaConAlas();
    public static final ItemAutomaStaff AUTOMA_STAFF = new ItemAutomaStaff();
    public static final ItemAutomaDevStaff AUTOMA_DEV_STAFF = new ItemAutomaDevStaff();

    private ModItems() {}

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().registerAll(COMIDA_CON_ALAS, AUTOMA_STAFF, AUTOMA_DEV_STAFF);
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(
                COMIDA_CON_ALAS,
                0,
                new ModelResourceLocation(COMIDA_CON_ALAS.getRegistryName(), "inventory"));
        ModelLoader.setCustomModelResourceLocation(
                AUTOMA_STAFF,
                0,
                new ModelResourceLocation(AUTOMA_STAFF.getRegistryName(), "inventory"));
        ModelLoader.setCustomModelResourceLocation(
                AUTOMA_DEV_STAFF,
                0,
                new ModelResourceLocation(AUTOMA_DEV_STAFF.getRegistryName(), "inventory"));
    }
}

