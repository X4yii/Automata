package com.x4yi.automata.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.AutomataMod;

/**
 * Data-driven JSON overrides for modded weapon profiles and entity tactical directives.
 * Files live under config/automata_weapon_profiles.json and config/automata_entity_tactics.json.
 */
public final class IntegrationOverrides {

    public record WeaponOverride(
            String useMode,
            boolean twoHanded,
            double reachBonus,
            String fuelItemId,
            boolean ignore) {}

    public record EntityTacticOverride(
            double preferredMinRange,
            double preferredMaxRange,
            boolean waitForLandingWhenMelee,
            boolean fleeWhenSwelling,
            boolean prioritizeCritOnLanding,
            boolean highPriorityFocus,
            boolean avoidMeleeRush,
            double threatWeightMultiplier,
            boolean ignore) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String WEAPON_FILE_NAME = "automata_weapon_profiles.json";
    private static final String ENTITY_FILE_NAME = "automata_entity_tactics.json";

    private static volatile File configDirectory;
    private static volatile Map<String, WeaponOverride> weaponOverrides = Collections.emptyMap();
    private static volatile Map<String, EntityTacticOverride> entityOverrides = Collections.emptyMap();

    private IntegrationOverrides() {}

    public static void init(@Nullable File configDir) {
        configDirectory = configDir;
        reload();
    }

    public static void reload() {
        if (configDirectory == null) {
            return;
        }
        File weaponFile = new File(configDirectory, WEAPON_FILE_NAME);
        File entityFile = new File(configDirectory, ENTITY_FILE_NAME);
        ensureDefaultTemplate(weaponFile, "{\n  \"_comment\": \"Override weapon profiles by registry ID (useMode: auto|melee|ranged|throw|beam|ignore)\",\n  \"weapons\": {}\n}\n");
        ensureDefaultTemplate(entityFile, "{\n  \"_comment\": \"Override entity tactical directives by registry ID or modid:* prefix\",\n  \"entities\": {}\n}\n");

        try {
            String weaponJson = new String(Files.readAllBytes(weaponFile.toPath()), StandardCharsets.UTF_8);
            String entityJson = new String(Files.readAllBytes(entityFile.toPath()), StandardCharsets.UTF_8);
            loadFromJson(weaponJson, entityJson);
        } catch (IOException e) {
            AutomataMod.LOGGER.warn("Failed to load integration overrides", e);
        }
    }

    public static void loadFromJson(@Nullable String weaponJson, @Nullable String entityJson) {
        if (weaponJson != null) {
            weaponOverrides = parseWeapons(weaponJson);
        }
        if (entityJson != null) {
            entityOverrides = parseEntities(entityJson);
        }
    }

    @Nullable
    public static WeaponOverride getWeaponOverride(@Nullable ResourceLocation id) {
        if (id == null || weaponOverrides.isEmpty()) {
            return null;
        }
        WeaponOverride exact = weaponOverrides.get(id.toString());
        if (exact != null) {
            return exact;
        }
        return weaponOverrides.get(id.getNamespace() + ":*");
    }

    @Nullable
    public static EntityTacticOverride getEntityOverride(@Nullable ResourceLocation id) {
        if (id == null || entityOverrides.isEmpty()) {
            return null;
        }
        EntityTacticOverride exact = entityOverrides.get(id.toString());
        if (exact != null) {
            return exact;
        }
        return entityOverrides.get(id.getNamespace() + ":*");
    }

    private static Map<String, WeaponOverride> parseWeapons(String raw) {
        Map<String, WeaponOverride> result = new HashMap<>();
        try {
            JsonObject root = new JsonParser().parse(raw).getAsJsonObject();
            JsonObject map = root.has("weapons") && root.get("weapons").isJsonObject()
                    ? root.getAsJsonObject("weapons")
                    : root;
            for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
                if ("_comment".equals(entry.getKey()) || !entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject obj = entry.getValue().getAsJsonObject();
                String useMode = getString(obj, "useMode", "auto");
                boolean twoHanded = getBoolean(obj, "twoHanded", false);
                double reachBonus = getDouble(obj, "reachBonus", 0.0D);
                String fuelItemId = getString(obj, "fuelItemId", "");
                boolean ignore = getBoolean(obj, "ignore", "ignore".equalsIgnoreCase(useMode));
                result.put(entry.getKey(), new WeaponOverride(useMode, twoHanded, reachBonus, fuelItemId, ignore));
            }
        } catch (Exception e) {
            AutomataMod.LOGGER.warn("Invalid {}: {}", WEAPON_FILE_NAME, e.getMessage());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, EntityTacticOverride> parseEntities(String raw) {
        Map<String, EntityTacticOverride> result = new HashMap<>();
        try {
            JsonObject root = new JsonParser().parse(raw).getAsJsonObject();
            JsonObject map = root.has("entities") && root.get("entities").isJsonObject()
                    ? root.getAsJsonObject("entities")
                    : root;
            for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
                if ("_comment".equals(entry.getKey()) || !entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject obj = entry.getValue().getAsJsonObject();
                result.put(entry.getKey(), new EntityTacticOverride(
                        getDouble(obj, "preferredMinRange", 0.0D),
                        getDouble(obj, "preferredMaxRange", 3.0D),
                        getBoolean(obj, "waitForLandingWhenMelee", false),
                        getBoolean(obj, "fleeWhenSwelling", false),
                        getBoolean(obj, "prioritizeCritOnLanding", false),
                        getBoolean(obj, "highPriorityFocus", false),
                        getBoolean(obj, "avoidMeleeRush", false),
                        getDouble(obj, "threatWeightMultiplier", 1.0D),
                        getBoolean(obj, "ignore", false)));
            }
        } catch (Exception e) {
            AutomataMod.LOGGER.warn("Invalid {}: {}", ENTITY_FILE_NAME, e.getMessage());
        }
        return Collections.unmodifiableMap(result);
    }

    private static void ensureDefaultTemplate(File file, String content) {
        if (file.exists()) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static String getString(JsonObject obj, String key, String def) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : def;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean def) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsBoolean() : def;
    }

    private static double getDouble(JsonObject obj, String key, double def) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsDouble() : def;
    }
}
