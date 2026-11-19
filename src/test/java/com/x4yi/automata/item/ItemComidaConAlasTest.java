package com.x4yi.automata.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.EntityEquipmentSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ItemComidaConAlasTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void hasSwordStatsNineDamageOnePointSixSpeedAnd3200Durability() {
        ItemComidaConAlas sword = new ItemComidaConAlas();
        assertEquals(3200, sword.getMaxDamage());

        Collection<AttributeModifier> dmgMods =
                sword.getItemAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                        .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName());
        double dmgMod = dmgMods.stream().mapToDouble(AttributeModifier::getAmount).sum();
        assertEquals(8.0D, dmgMod, 1.0E-5D);

        Collection<AttributeModifier> spdMods =
                sword.getItemAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                        .get(SharedMonsterAttributes.ATTACK_SPEED.getName());
        double spdMod = spdMods.stream().mapToDouble(AttributeModifier::getAmount).sum();
        assertEquals(-2.4D, spdMod, 1.0E-5D);
    }
}

