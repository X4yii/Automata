package com.x4yi.automata.ai.jobs;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockOre;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.oredict.OreDictionary;

public final class ModdedOreCatalog {

    private static final Set<String> KNOWN_MODDED_ORE_IDS = Set.of(
            "thermalfoundation:ore",
            "thermalfoundation:ore_fluid",
            "tconstruct:ore",
            "mekanism:oreblock",
            "ic2:resource",
            "immersiveengineering:ore",
            "appliedenergistics2:quartz_ore",
            "appliedenergistics2:charged_quartz_ore",
            "forestry:resources",
            "railcraft:ore",
            "railcraft:ore_metal",
            "railcraft:ore_magic",
            "bigreactors:oreyellorite",
            "bigreactors:oreanglesite",
            "bigreactors:orebenitoite",
            "extremereactors:oreyellorite",
            "draconicevolution:draconium_ore",
            "thaumcraft:ore_amber",
            "thaumcraft:ore_cinnabar",
            "thaumcraft:ore_quartz",
            "thaumcraft:crystal_aer",
            "thaumcraft:crystal_ignis",
            "thaumcraft:crystal_aqua",
            "thaumcraft:crystal_terra",
            "thaumcraft:crystal_ordo",
            "thaumcraft:crystal_perditio",
            "astralsorcery:blockcustomore",
            "astralsorcery:blockcustomsandore",
            "actuallyadditions:block_misc",
            "mysticalagriculture:inferium_ore",
            "mysticalagriculture:prosperity_ore",
            "mysticalagriculture:nether_inferium_ore",
            "mysticalagriculture:nether_prosperity_ore",
            "mysticalagriculture:end_inferium_ore",
            "mysticalagriculture:end_prosperity_ore",
            "biomesoplenty:gem_ore",
            "projectred-exploration:ore",
            "nuclearcraft:ore",
            "embers:ore_copper",
            "embers:ore_lead",
            "embers:ore_silver",
            "embers:ore_tin",
            "embers:ore_aluminum",
            "embers:ore_nickel",
            "embers:ore_quartz",
            "netherendingores:ore_nether_modded_1",
            "netherendingores:ore_nether_modded_2",
            "netherendingores:ore_nether_vanilla",
            "netherendingores:ore_end_modded_1",
            "netherendingores:ore_end_modded_2",
            "netherendingores:ore_end_vanilla",
            "netherendingores:ore_other_1",
            "denseores:dense_ore_0",
            "denseores:dense_ore_1",
            "substratum:ore",
            "substratum:ore_metal",
            "techreborn:ore",
            "techreborn:ore2",
            "rftools:dimensional_shard_ore",
            "quark:biotite_ore",
            "iceandfire:silver_ore",
            "iceandfire:sapphire_ore",
            "iceandfire:amethyst_ore",
            "iceandfire:copper_ore",
            "aether_legacy:ambrosium_ore",
            "aether_legacy:zanite_ore",
            "aether_legacy:gravitite_ore",
            "erebus:ore_amber",
            "erebus:ore_diamond",
            "erebus:ore_emerald",
            "erebus:ore_coal",
            "erebus:ore_iron",
            "erebus:ore_gold",
            "erebus:ore_lapis",
            "erebus:ore_jade",
            "erebus:ore_fossil",
            "erebus:ore_quartz",
            "erebus:ore_silver",
            "erebus:ore_lead",
            "erebus:ore_copper",
            "erebus:ore_tin",
            "erebus:ore_aluminum",
            "galacticraftcore:basic_block_core",
            "galacticraftcore:basic_block_moon",
            "galacticraftplanets:mars",
            "galacticraftplanets:asteroids_block",
            "galacticraftplanets:venus",
            "metallurgy:adamantine_ore",
            "metallurgy:alduorite_ore",
            "metallurgy:astralsilver_ore",
            "metallurgy:atlarus_ore",
            "metallurgy:bitumen_ore",
            "metallurgy:carmot_ore",
            "metallurgy:ceruclase_ore",
            "metallurgy:copper_ore",
            "metallurgy:deep_iron_ore",
            "metallurgy:eximite_ore",
            "metallurgy:ignatius_ore",
            "metallurgy:infuscolium_ore",
            "metallurgy:kalendrite_ore",
            "metallurgy:lemurite_ore",
            "metallurgy:magnesium_ore",
            "metallurgy:manganese_ore",
            "metallurgy:meutoite_ore",
            "metallurgy:midasium_ore",
            "metallurgy:mithril_ore",
            "metallurgy:orichalcum_ore",
            "metallurgy:osmium_ore",
            "metallurgy:oureclase_ore",
            "metallurgy:phosphorite_ore",
            "metallurgy:platinum_ore",
            "metallurgy:potash_ore",
            "metallurgy:prometheum_ore",
            "metallurgy:rubracium_ore",
            "metallurgy:sanguinite_ore",
            "metallurgy:shadow_iron_ore",
            "metallurgy:silver_ore",
            "metallurgy:sulfur_ore",
            "metallurgy:tin_ore",
            "metallurgy:vulcanite_ore",
            "metallurgy:vyroxeres_ore",
            "metallurgy:zinc_ore",
            "basemetals:copper_ore",
            "basemetals:tin_ore",
            "basemetals:lead_ore",
            "basemetals:silver_ore",
            "basemetals:nickel_ore",
            "basemetals:platinum_ore",
            "basemetals:zinc_ore",
            "basemetals:mercury_ore",
            "basemetals:bismuth_ore",
            "basemetals:antimony_ore",
            "modernmetals:aluminum_ore",
            "modernmetals:chromium_ore",
            "modernmetals:iridium_ore",
            "modernmetals:magnesium_ore",
            "modernmetals:manganese_ore",
            "modernmetals:osmium_ore",
            "modernmetals:plutonium_ore",
            "modernmetals:rutile_ore",
            "modernmetals:tantalum_ore",
            "modernmetals:titanium_ore",
            "modernmetals:tungsten_ore",
            "modernmetals:uranium_ore",
            "modernmetals:zirconium_ore");

    private static final Map<IBlockState, Boolean> ORE_STATE_CACHE = new ConcurrentHashMap<>();

    private ModdedOreCatalog() {}

    public static boolean isOreBlock(WorldServer world, BlockPos pos, IBlockState state) {
        Boolean cached = ORE_STATE_CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        boolean result = evaluateOreState(world, pos, state);
        if (ORE_STATE_CACHE.size() < 4096) {
            ORE_STATE_CACHE.put(state, result);
        }
        return result;
    }

    public static boolean isKnownModdedOreId(String registryId) {
        return KNOWN_MODDED_ORE_IDS.contains(registryId);
    }

    private static boolean evaluateOreState(WorldServer world, BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.AIR) {
            return false;
        }
        if (block instanceof BlockOre || block instanceof BlockRedstoneOre || block == Blocks.QUARTZ_ORE) {
            return true;
        }
        ResourceLocation reg = block.getRegistryName();
        if (reg != null && isKnownModdedOreId(reg.toString())) {
            return true;
        }
        Item item = Item.getItemFromBlock(block);
        if (item != net.minecraft.init.Items.AIR) {
            int meta = block.damageDropped(state);
            ItemStack stack = new ItemStack(item, 1, meta);
            if (!stack.isEmpty() && hasOreDictPrefix(stack, "ore")) {
                return true;
            }
            int stateMeta = block.getMetaFromState(state);
            if (stateMeta != meta) {
                ItemStack altStack = new ItemStack(item, 1, stateMeta);
                if (!altStack.isEmpty() && hasOreDictPrefix(altStack, "ore")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasOreDictPrefix(ItemStack stack, String prefix) {
        for (int id : OreDictionary.getOreIDs(stack)) {
            String name = OreDictionary.getOreName(id);
            if (name != null && name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

