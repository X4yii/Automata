
package com.x4yi.automata.item;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.entity.EntityAutomaton;

public class ItemAutomatonTerminator extends Item {

    public ItemAutomatonTerminator() {
        setRegistryName(AutomataMod.MOD_ID, "automaton_terminator");
        setTranslationKey(AutomataMod.MOD_ID + ".automaton_terminator");
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.COMBAT);
    }

    @Override
    public boolean hitEntity(ItemStack stack, EntityLivingBase target, EntityLivingBase attacker) {
        if (!target.world.isRemote && target instanceof EntityAutomaton automaton) {
            if (attacker instanceof EntityPlayer player && (automaton.isOwnedBy(player) || player.canUseCommand(2, ""))) {
                automaton.forceTerminate(DamageSource.causeMobDamage(attacker));
                return true;
            }
            return false;
        }
        return super.hitEntity(stack, target, attacker);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.format("item.automata.automaton_terminator.tooltip"));
    }
}

