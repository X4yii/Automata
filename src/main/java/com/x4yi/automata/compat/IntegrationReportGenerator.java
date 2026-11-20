package com.x4yi.automata.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.config.IntegrationOverrides;

/**
 * Generates a modpack integration diagnostic report listing how Automata classifies
 * registered mod items (weapons/shields/bows/consumables) and entity tactics.
 */
public final class IntegrationReportGenerator {

    private IntegrationReportGenerator() {}

    public static Path writeReport(Path outputFile) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("=== Automata Integration Report ===");
        lines.add("");
        lines.add("[Items: Weapons, Shields, Bows & Consumables]");
        int knownItems = 0;
        int inferredItems = 0;

        for (Item item : ForgeRegistries.ITEMS.getValuesCollection()) {
            ResourceLocation id = item.getRegistryName();
            if (id == null || "minecraft".equals(id.getNamespace())) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            IntegrationOverrides.WeaponOverride ov = IntegrationOverrides.getWeaponOverride(id);
            if (ov != null) {
                lines.add("KNOWN(override) " + id + " -> mode=" + ov.useMode()
                        + ", twoHanded=" + ov.twoHanded()
                        + ", reachBonus=" + ov.reachBonus()
                        + ", ignore=" + ov.ignore());
                knownItems++;
                continue;
            }
            boolean knownAdapter = SpartanWeaponryCompat.isSpartanMeleeWeapon(stack)
                    || SpartanWeaponryCompat.isSpartanRangedItem(stack)
                    || ModdedWeaponCompat.isBetterSurvivalWeapon(stack)
                    || ModdedWeaponCompat.isNoCubesSrpWeapon(stack);
            boolean consumable = EquipmentSelector.isHealingOrCureConsumable(stack, true, true);
            if (knownAdapter) {
                lines.add("KNOWN(adapter)  " + id
                        + " -> twoHanded=" + ModCompatBridge.isTwoHandedWeapon(stack)
                        + ", bow=" + EquipmentSelector.isBowItem(stack));
                knownItems++;
            } else if (EquipmentSelector.isCombatWeapon(stack)
                    || EquipmentSelector.isShieldItem(stack)
                    || EquipmentSelector.isBowItem(stack)
                    || consumable) {
                lines.add("INFERRED        " + id
                        + " -> dmg=" + String.format(java.util.Locale.ROOT, "%.1f", EquipmentSelector.getAttackDamage(stack))
                        + ", shield=" + EquipmentSelector.isShieldItem(stack)
                        + ", bow=" + EquipmentSelector.isBowItem(stack)
                        + ", consumable=" + consumable);
                inferredItems++;
            }
        }

        lines.add("");
        lines.add("[Entities: Tactical Directives]");
        int knownEntities = 0;
        for (EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
            ResourceLocation id = entry.getRegistryName();
            if (id == null || "minecraft".equals(id.getNamespace())) {
                continue;
            }
            IntegrationOverrides.EntityTacticOverride ov = IntegrationOverrides.getEntityOverride(id);
            if (ov != null) {
                lines.add("KNOWN(override) " + id + " -> maxRange=" + ov.preferredMaxRange()
                        + ", avoidRush=" + ov.avoidMeleeRush()
                        + ", threatMult=" + ov.threatWeightMultiplier());
                knownEntities++;
            } else if ("srparasites".equals(id.getNamespace())) {
                lines.add("KNOWN(srp)      " + id);
                knownEntities++;
            }
        }

        lines.add("");
        lines.add("Summary: knownItems=" + knownItems + ", inferredItems=" + inferredItems + ", knownEntities=" + knownEntities);

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(outputFile, lines, StandardCharsets.UTF_8);
        return outputFile;
    }
}
