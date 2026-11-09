package com.x4yi.automata.ai.jobs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

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

import com.x4yi.automata.AutomataMod;

public final class ModdedOreCatalog {

    private static final int MAX_ORE_CACHE_SIZE = 4096;

    private static final String[] DEFAULT_MODDED_ORE_IDS = {
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
            "modernmetals:zirconium_ore"
    };

    private static final Map<IBlockState, Boolean> ORE_STATE_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<IBlockState, Boolean>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<IBlockState, Boolean> eldest) {
                    return size() > MAX_ORE_CACHE_SIZE;
                }
            });

    private static volatile OreRules activeRules = OreRules.fromArrays(
            DEFAULT_MODDED_ORE_IDS, new String[0], new String[0]);

    @Nullable
    private static File configuredFile;

    private ModdedOreCatalog() {}

    public static synchronized void init(File configDir) {
        configuredFile = new File(configDir, "automata_ores.cfg");
        reload();
    }

    public static synchronized void reload() {
        if (configuredFile != null) {
            try {
                if (!configuredFile.exists()) {
                    writeDefaultConfig(configuredFile);
                }
                activeRules = readConfigFile(configuredFile);
            } catch (IOException e) {
                AutomataMod.LOGGER.warn("Failed to load ore catalog config {}, keeping current rules", configuredFile, e);
            }
        }
        clearCache();
    }

    private static void writeDefaultConfig(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Automata Modded Ore Catalog Configuration");
        lines.add("# Format: modid:block or modid:block@meta");
        lines.add("");
        appendBlockList(lines, "oreBlockIds", DEFAULT_MODDED_ORE_IDS);
        lines.add("");
        lines.add("# Optional glob patterns (e.g. *:*_ore)");
        appendBlockList(lines, "oreBlockIdPatterns", new String[0]);
        lines.add("");
        lines.add("# Block IDs never treated as ores");
        appendBlockList(lines, "oreBlockBlacklist", new String[0]);
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
    }

    private static void appendBlockList(List<String> lines, String key, String[] values) {
        lines.add(key + " <");
        for (String v : values) {
            lines.add("    " + v);
        }
        lines.add(">");
    }

    private static OreRules readConfigFile(File file) throws IOException {
        List<String> rawLines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        List<String> ids = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        List<String> blacklist = new ArrayList<>();
        List<String> currentTarget = null;
        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (currentTarget != null) {
                if (">".equals(line)) {
                    currentTarget = null;
                } else {
                    currentTarget.add(line);
                }
                continue;
            }
            if (line.startsWith("oreBlockIds") && line.endsWith("<")) {
                currentTarget = ids;
            } else if (line.startsWith("oreBlockIdPatterns") && line.endsWith("<")) {
                currentTarget = patterns;
            } else if (line.startsWith("oreBlockBlacklist") && line.endsWith("<")) {
                currentTarget = blacklist;
            }
        }
        return OreRules.fromArrays(
                ids.toArray(new String[0]),
                patterns.toArray(new String[0]),
                blacklist.toArray(new String[0]));
    }

    public static void clearCache() {
        ORE_STATE_CACHE.clear();
    }

    static void applyRulesForTest(String[] ids, String[] patterns, String[] blacklist) {
        activeRules = OreRules.fromArrays(ids, patterns, blacklist);
        clearCache();
    }

    static void resetDefaultsForTest() {
        activeRules = OreRules.fromArrays(DEFAULT_MODDED_ORE_IDS, new String[0], new String[0]);
        clearCache();
    }

    public static boolean isOreBlock(WorldServer world, BlockPos pos, IBlockState state) {
        Boolean cached = ORE_STATE_CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        boolean result = evaluateOreState(world, pos, state);
        ORE_STATE_CACHE.put(state, result);
        return result;
    }

    public static boolean isKnownModdedOreId(String registryId) {
        return isKnownModdedOreId(registryId, -1);
    }

    public static boolean isKnownModdedOreId(String registryId, int meta) {
        return activeRules.isIncluded(registryId, meta);
    }

    static boolean isBlacklistedOreId(String registryId, int meta) {
        return activeRules.isBlacklisted(registryId, meta);
    }

    private static boolean evaluateOreState(WorldServer world, BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.AIR || JobBlockSupport.isBlockBlacklisted(state)) {
            return false;
        }
        ResourceLocation reg = block.getRegistryName();
        int stateMeta = block.getMetaFromState(state);
        if (reg != null && activeRules.isBlacklisted(reg.toString(), stateMeta)) {
            return false;
        }
        if (block instanceof BlockOre || block instanceof BlockRedstoneOre || block == Blocks.QUARTZ_ORE) {
            return true;
        }
        if (reg != null && activeRules.isIncluded(reg.toString(), stateMeta)) {
            return true;
        }
        Item item = Item.getItemFromBlock(block);
        if (item != net.minecraft.init.Items.AIR) {
            int meta = block.damageDropped(state);
            ItemStack stack = new ItemStack(item, 1, meta);
            if (!stack.isEmpty() && hasOreDictPrefix(stack, "ore")) {
                return true;
            }
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

    private static final class OreRules {
        private final Set<String> exactIds;
        private final List<Pattern> patterns;
        private final Set<String> blacklist;

        private OreRules(Set<String> exactIds, List<Pattern> patterns, Set<String> blacklist) {
            this.exactIds = exactIds;
            this.patterns = patterns;
            this.blacklist = blacklist;
        }

        static OreRules fromArrays(String[] ids, String[] rawPatterns, String[] rawBlacklist) {
            Set<String> idSet = new HashSet<>();
            for (String id : ids) {
                if (id != null && !id.trim().isEmpty()) {
                    idSet.add(id.trim());
                }
            }
            List<Pattern> compiled = new ArrayList<>();
            for (String glob : rawPatterns) {
                if (glob != null && !glob.trim().isEmpty()) {
                    compiled.add(compileGlob(glob.trim()));
                }
            }
            Set<String> blackSet = new HashSet<>();
            for (String id : rawBlacklist) {
                if (id != null && !id.trim().isEmpty()) {
                    blackSet.add(id.trim());
                }
            }
            return new OreRules(Collections.unmodifiableSet(idSet), Collections.unmodifiableList(compiled), Collections.unmodifiableSet(blackSet));
        }

        boolean isBlacklisted(String registryId, int meta) {
            if (blacklist.contains(registryId)) {
                return true;
            }
            return meta >= 0 && blacklist.contains(registryId + "@" + meta);
        }

        boolean isIncluded(String registryId, int meta) {
            if (isBlacklisted(registryId, meta)) {
                return false;
            }
            if (exactIds.contains(registryId)) {
                return true;
            }
            if (meta >= 0 && exactIds.contains(registryId + "@" + meta)) {
                return true;
            }
            for (Pattern p : patterns) {
                if (p.matcher(registryId).matches()) {
                    return true;
                }
            }
            return false;
        }

        private static Pattern compileGlob(String glob) {
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                switch (c) {
                    case '*' -> regex.append(".*");
                    case '?' -> regex.append('.');
                    case '.', '(', ')', '[', ']', '{', '}', '\\', '^', '$', '|', '+' -> regex.append('\\').append(c);
                    default -> regex.append(c);
                }
            }
            regex.append('$');
            return Pattern.compile(regex.toString());
        }
    }
}

