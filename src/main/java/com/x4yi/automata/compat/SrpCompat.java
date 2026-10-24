package com.x4yi.automata.compat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Native integration with Scape and Run: Parasites (SRP) COTH immunity systems.
 * Synchronizes {@code automata:automaton} with SRP's native {@code COTH Mob Immune Mob List}
 * and maintains the native {@code srpcothimmunity} entity data tag.
 */
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class SrpCompat {

    public static final String SRP_MOD_ID = "srparasites";
    public static final String AUTOMATON_ENTITY_ID = AutomataMod.MOD_ID + ":automaton";
    public static final String SRP_COTH_IMMUNITY_TAG = "srpcothimmunity";
    private static final String SRP_CONFIG_SYSTEMS_CLASS =
            "com.dhanantry.scapeandrunparasites.util.config.SRPConfigSystems";

    private SrpCompat() {}

    public static boolean isSrpLoaded() {
        return Loader.isModLoaded(SRP_MOD_ID);
    }

    public static void syncConfig() {
        if (!isSrpLoaded()) {
            return;
        }
        syncNativeCothImmuneList(AutomataConfig.cothImmune);
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server != null) {
                for (net.minecraft.world.WorldServer ws : server.worlds) {
                    if (ws == null) {
                        continue;
                    }
                    for (EntityAutomaton bot : ws.getEntities(EntityAutomaton.class, e -> e != null && e.isEntityAlive())) {
                        applyNativeImmunityTag(bot, AutomataConfig.cothImmune);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void applyNativeImmunityTag(EntityLivingBase entity, boolean immune) {
        if (entity == null) {
            return;
        }
        entity.getEntityData().setInteger(SRP_COTH_IMMUNITY_TAG, immune ? 1 : 0);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote || !isSrpLoaded()) {
            return;
        }
        if (event.getEntity() instanceof EntityAutomaton bot) {
            applyNativeImmunityTag(bot, AutomataConfig.cothImmune);
        }
    }

    public static void enforceTickImmunity(EntityAutomaton bot) {
        if (bot == null || bot.world.isRemote || !isSrpLoaded()) {
            return;
        }
        boolean immune = AutomataConfig.cothImmune;
        applyNativeImmunityTag(bot, immune);
        if (!immune) {
            return;
        }
        List<Potion> toRemove = null;
        for (PotionEffect effect : bot.getActivePotionEffects()) {
            ResourceLocation key = effect.getPotion().getRegistryName();
            if (key != null
                    && SRP_MOD_ID.equals(key.getNamespace())
                    && key.getPath().toLowerCase(java.util.Locale.ROOT).contains("coth")) {
                if (toRemove == null) {
                    toRemove = new ArrayList<>();
                }
                toRemove.add(effect.getPotion());
            }
        }
        if (toRemove != null) {
            for (Potion p : toRemove) {
                bot.removePotionEffect(p);
            }
        }
    }

    static boolean syncNativeCothImmuneList(boolean immune) {
        try {
            Class<?> cfgClass = Class.forName(SRP_CONFIG_SYSTEMS_CLASS);
            Field listField = findCothImmuneListField(cfgClass);
            if (listField == null) {
                return false;
            }
            Object raw = listField.get(null);
            boolean inverted = readInvertedFlag(cfgClass);
            if (raw instanceof String[] current) {
                String[] updated = updateCothImmuneList(current, inverted, immune);
                if (updated != current) {
                    listField.set(null, updated);
                }
                AutomataMod.LOGGER.info("Synchronized {} with SRPConfigSystems.{} (immune={})",
                        AUTOMATON_ENTITY_ID, listField.getName(), immune);
                return true;
            }
            if (raw instanceof Collection<?> col) {
                @SuppressWarnings("unchecked")
                Collection<String> mutable = (Collection<String>) col;
                String[] updated = updateCothImmuneList(mutable.toArray(new String[0]), inverted, immune);
                mutable.clear();
                mutable.addAll(Arrays.asList(updated));
                AutomataMod.LOGGER.info("Synchronized {} with SRPConfigSystems.{} collection (immune={})",
                        AUTOMATON_ENTITY_ID, listField.getName(), immune);
                return true;
            }
            return false;
        } catch (Throwable t) {
            AutomataMod.LOGGER.debug("SRPConfigSystems COTH list sync skipped: {}", t.getMessage());
            return false;
        }
    }

    static String[] updateCothImmuneList(String[] current, boolean inverted, boolean immune) {
        boolean shouldBeInList = inverted ? !immune : immune;
        List<String> list = new ArrayList<>(Arrays.asList(current));
        boolean present = false;
        for (String entry : list) {
            if (AUTOMATON_ENTITY_ID.equalsIgnoreCase(entry)) {
                present = true;
                break;
            }
        }
        if (shouldBeInList && !present) {
            list.add(AUTOMATON_ENTITY_ID);
            return list.toArray(new String[0]);
        }
        if (!shouldBeInList && present) {
            list.removeIf(entry -> AUTOMATON_ENTITY_ID.equalsIgnoreCase(entry));
            return list.toArray(new String[0]);
        }
        return current;
    }

    private static Field findCothImmuneListField(Class<?> cfgClass) {
        String[] knownNames = {"cothImmuneMobList", "COTHImmuneMobList", "cothMobImmuneList", "cothImmuneList"};
        for (String name : knownNames) {
            try {
                Field f = cfgClass.getField(name);
                if (f.getType() == String[].class) {
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        for (Field f : cfgClass.getFields()) {
            if (f.getType() != String[].class) {
                continue;
            }
            String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("coth") && lower.contains("immun")) {
                return f;
            }
        }
        return null;
    }

    private static boolean readInvertedFlag(Class<?> cfgClass) {
        for (Field f : cfgClass.getFields()) {
            if (f.getType() != boolean.class) {
                continue;
            }
            String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("coth") && lower.contains("immun") && lower.contains("invert")) {
                try {
                    return f.getBoolean(null);
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return false;
    }
}

