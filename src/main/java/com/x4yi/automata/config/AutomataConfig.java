
package com.x4yi.automata.config;

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

    @Config.Comment("Maximum active automata allowed per player (0 = unlimited).")
    @Config.RangeInt(min = 0, max = 256)
    public static int maxBotsPerPlayer = 8;

    @Config.Comment("Chunk loading radius around each active automaton (1 = 3x3 chunks, 0 = only current chunk).")
    @Config.RangeInt(min = 0, max = 4)
    public static int chunkLoadRadius = 1;

    @Config.Comment("Allows automata to pillar up, bridge, or break blocks outside work areas during pathfinding.")
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

    @Config.Comment("Default entity ID blacklist copied to newly spawned automata.")
    public static String[] defaultEntityBlacklist = {
            "minecraft:villager",
            "minecraft:iron_golem",
            "minecraft:squid",
            "minecraft:bat",
            "minecraft:armor_stand"
    };

    private AutomataConfig() {}

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (AutomataMod.MOD_ID.equals(event.getModID())) {
            ConfigManager.sync(AutomataMod.MOD_ID, Config.Type.INSTANCE);
        }
    }
}

