package com.x4yi.automata.item;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.util.EnumHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.entity.EntityAutomaton;

public class ItemComidaConAlas extends ItemSword {

    public static final ToolMaterial MATERIAL = EnumHelper.addToolMaterial(
            "COMIDA_CON_ALAS",
            3,
            3200,
            8.0F,
            5.0F,
            14);

    public ItemComidaConAlas() {
        super(MATERIAL);
        setRegistryName(AutomataMod.MOD_ID, "comida_con_alas");
        setTranslationKey(AutomataMod.MOD_ID + ".comida_con_alas");
        setMaxDamage(3200);
        setCreativeTab(CreativeTabs.COMBAT);
    }

    @Override
    public boolean hitEntity(ItemStack stack, EntityLivingBase target, EntityLivingBase attacker) {
        if (!target.world.isRemote) {
            target.addPotionEffect(new PotionEffect(MobEffects.NAUSEA, 100, 1));
            target.addPotionEffect(new PotionEffect(MobEffects.SLOWNESS, 100, 0));
            if (target instanceof EntityAutomaton automaton
                    && attacker instanceof EntityPlayer player
                    && (automaton.isOwnedBy(player) || player.canUseCommand(2, ""))) {
                automaton.forceTerminate(DamageSource.causeMobDamage(attacker));
                return true;
            }
        }
        return super.hitEntity(stack, target, attacker);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(TextFormatting.GOLD + I18n.format("item.automata.comida_con_alas.tooltip"));
    }
}

