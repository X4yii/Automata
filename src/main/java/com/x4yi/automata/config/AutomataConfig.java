
package com.x4yi.automata.config;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;

@Config(modid = AutomataMod.MOD_ID, name = AutomataMod.MOD_ID)
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class AutomataConfig {

    @Config.Comment("Shows technical AI state in the GUI and renders A* paths and SquadBlackboard reservations in-world.")
    public static boolean debugMode = false;

    @Config.Comment("Maximum concurrent worker threads for asynchronous A* pathfinding and area scans.")
    @Config.RangeInt(min = 1, max = 64)
    public static int maxPathfindingThreads = 4;

    @Config.Comment("Dynamically scales non-critical AI, inventory, and cache refresh intervals when server MSPT rises.")
    public static boolean adaptiveTpsThrottling = true;

    @Config.Comment("Minimum free gap in blocks kept between allied automata to avoid crowding and collision spam.")
    @Config.RangeDouble(min = 0.0D, max = 3.0D)
    public static double allySeparationGap = 0.5D;

    @Config.Comment("Maximum active automata allowed per player (0 = unlimited).")
    @Config.RangeInt(min = 0, max = 256)
    public static int maxBotsPerPlayer = 8;

    @Config.Comment("Chunk loading radius around each active automaton (1 = 3x3 chunks, 0 = only current chunk).")
    @Config.RangeInt(min = 0, max = 4)
    public static int chunkLoadRadius = 1;

    @Config.Comment("[WIP] Allows automata to pillar up, bridge, or break-through blocks outside work areas during pathfinding.")
    public static boolean allowTerrainModificationInPathing = true;

    @Config.Comment("Chunk rarity for natural automaton camps in the Overworld (higher = rarer, 0 = disabled).")
    @Config.RangeInt(min = 0, max = 2000)
    public static int campSpawnRarity = 240;

    @Config.Comment("Ignore passive animals by default when automata look for combat targets.")
    public static boolean ignoreAnimals = true;

    @Config.Comment("Ignore calm neutral mobs (such as non-angry Endermen, Zombie Pigmen, Wolves) unless provoked.")
    public static boolean ignoreCalmNeutrals = true;

    @Config.Comment("Prevents automata from trampling farmland/crops when jumping unless another mod already cancels the event.")
    public static boolean preventAutomatonCropTrampling = true;

    @Config.Comment("Horizontal and vertical scan radius in blocks when the Mine job searches for nearby exposed ores.")
    @Config.RangeInt(min = 4, max = 48)
    public static int mineSearchRadius = 16;

    @Config.Comment("When true, automata owned by a player with client-side dynamic lights (e.g. OptiFine) hold a torch in offhand outside combat.")
    public static boolean preferTorchOffhandForDynamicLightOwners = false;

    @Config.Comment("Client-side dynamic light providers checked on join/settings-close. Format: 'modid' or 'modid:ClassName#methodName'.")
    public static String[] dynamicLightProviders = {
            "optifine:Config#isDynamicLights",
            "celeritasdynamiclights",
            "nothiriumdynamiclights",
            "dynamiclights_celeritas"
    };

    @Config.Comment("Extra item registry IDs treated as offhand torches for dynamic lighting.")
    public static String[] offhandTorchItems = {};

    @Config.Comment("Default entity ID blacklist copied to newly spawned automata.")
    public static String[] defaultEntityBlacklist = {
            "minecraft:villager",
            "minecraft:iron_golem",
            "minecraft:squid",
            "minecraft:bat",
            "minecraft:armor_stand"
    };

    @Config.Comment("Block IDs (modid:block) that automata will never break or harvest.")
    public static String[] blockBlacklist = {
            "minecraft:mob_spawner",
            "minecraft:bedrock"
    };

    @Config.Comment("Use an owner-derived FakePlayer identity so claim protection mods evaluate the owner's permissions. Disabled by default to avoid inheriting owner OP permissions.")
    public static boolean useOwnerIdentity = false;

    @Config.Comment("Allow automata to continue working when their owner is offline (if false, automata pause when owner is offline or dead).")
    public static boolean workWhenOwnerOffline = false;

    @Config.Name("CothInmune")
    @Config.Comment("Makes automata natively immune to Scape and Run: Parasites COTH infection via SRP's immune list and srpcothimmunity tag.")
    public static boolean cothImmune = true;

    @Config.Comment("Utility AI hysteresis bonus added to the currently active behavior.")
    @Config.RangeDouble(min = 0.0D, max = 1.0D)
    public static double arbiterHysteresisBonus = 0.08D;

    @Config.Comment("Utility AI score for immediate hazards (lava, fire, explosives).")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterSurvivalHazardWeight = 1.00D;

    @Config.Comment("Utility AI score for low-health survival retreat.")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterSurvivalLowHealthWeight = 0.90D;

    @Config.Comment("Utility AI score for reviving a downed squadmate.")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterSurvivalReviveWeight = 0.84D;

    @Config.Comment("Utility AI score for reactive self-defense.")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterSelfDefenseWeight = 0.78D;

    @Config.Comment("Utility AI base score for chest logistics.")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterLogisticsBaseWeight = 0.45D;

    @Config.Comment("Utility AI extra score scaled by inventory occupancy for chest logistics.")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterLogisticsOccupancyScale = 0.35D;

    @Config.Comment("Utility AI score for seeking nearby loot when no committed work is active.")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterSeekLootIdleWeight = 0.65D;

    @Config.Comment("Utility AI score for seeking nearby loot while committed work is active.")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterSeekLootCommittedWeight = 0.46D;

    @Config.Comment("Utility AI score for the active job when committed work is running.")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterActiveJobCommittedWeight = 0.68D;

    @Config.Comment("Utility AI base score for the active job.")
    @Config.RangeDouble(min = 0.0D, max = 2.0D)
    public static double arbiterActiveJobBaseWeight = 0.55D;

    private static volatile String[] cachedBlacklistArray;
    private static volatile Set<ResourceLocation> cachedBlacklistIds = new HashSet<>();
    private static final ConcurrentHashMap<Block, Boolean> CACHED_BLOCK_BLACKLIST = new ConcurrentHashMap<>();

    private AutomataConfig() {}

    public static boolean isBlockBlacklisted(@Nullable Block block) {
        if (block == null) {
            return false;
        }
        String[] current = blockBlacklist;
        if (current == null || current.length == 0) {
            return false;
        }
        if (current != cachedBlacklistArray) {
            syncBlacklistCaches(current);
        }
        Boolean cached = CACHED_BLOCK_BLACKLIST.get(block);
        if (cached != null) {
            return cached;
        }
        boolean blacklisted = isBlockIdBlacklisted(block.getRegistryName());
        CACHED_BLOCK_BLACKLIST.put(block, blacklisted);
        return blacklisted;
    }

    public static boolean isBlockIdBlacklisted(@Nullable ResourceLocation id) {
        if (id == null) {
            return false;
        }
        String[] current = blockBlacklist;
        if (current == null || current.length == 0) {
            return false;
        }
        if (current != cachedBlacklistArray) {
            syncBlacklistCaches(current);
        }
        return cachedBlacklistIds.contains(id);
    }

    private static synchronized void syncBlacklistCaches(String[] current) {
        if (current == cachedBlacklistArray) {
            return;
        }
        Set<ResourceLocation> next = new HashSet<>(current.length * 2);
        for (String entry : current) {
            if (entry != null && !entry.isEmpty()) {
                next.add(new ResourceLocation(entry));
            }
        }
        cachedBlacklistIds = next;
        CACHED_BLOCK_BLACKLIST.clear();
        cachedBlacklistArray = current;
    }

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (AutomataMod.MOD_ID.equals(event.getModID())) {
            ConfigManager.sync(AutomataMod.MOD_ID, Config.Type.INSTANCE);
            cachedBlacklistArray = null;
            CACHED_BLOCK_BLACKLIST.clear();
            com.x4yi.automata.compat.SrpCompat.syncConfig();
            com.x4yi.automata.navigation.PathfindingExecutor.refreshPoolIfNeeded();
        }
    }
}

