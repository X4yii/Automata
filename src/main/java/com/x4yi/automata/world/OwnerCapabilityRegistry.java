package com.x4yi.automata.world;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import com.x4yi.automata.AutomataMod;

/**
 * Server-side registry of client-reported visual/lighting capabilities per owner UUID.
 */
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class OwnerCapabilityRegistry {

    public record OwnerCaps(boolean dynamicLights) {}

    private static final Map<UUID, OwnerCaps> CAPS_BY_OWNER = new ConcurrentHashMap<>();

    private OwnerCapabilityRegistry() {}

    public static void setDynamicLights(@Nullable UUID ownerUuid, boolean enabled) {
        if (ownerUuid == null) {
            return;
        }
        if (!enabled) {
            CAPS_BY_OWNER.remove(ownerUuid);
        } else {
            CAPS_BY_OWNER.put(ownerUuid, new OwnerCaps(true));
        }
    }

    public static boolean hasDynamicLights(@Nullable UUID ownerUuid) {
        if (ownerUuid == null) {
            return false;
        }
        OwnerCaps caps = CAPS_BY_OWNER.get(ownerUuid);
        return caps != null && caps.dynamicLights();
    }

    public static void clearPlayer(@Nullable UUID ownerUuid) {
        if (ownerUuid != null) {
            CAPS_BY_OWNER.remove(ownerUuid);
        }
    }

    public static void clearAll() {
        CAPS_BY_OWNER.clear();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player != null) {
            clearPlayer(event.player.getUniqueID());
        }
    }
}
