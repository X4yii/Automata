package com.x4yi.automata.coordination;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class SquadRegistry {

    private static final ConcurrentHashMap<String, SquadBlackboard> BOARDS = new ConcurrentHashMap<>();

    private SquadRegistry() {}

    public static SquadBlackboard getBoard(int dimensionId, @Nullable UUID ownerUuid, @Nullable String channel) {
        String ownerPart = ownerUuid == null ? "wild" : ownerUuid.toString();
        String channelPart = channel == null ? "" : channel.trim();
        String key = dimensionId + ":" + ownerPart + ":" + channelPart;
        return BOARDS.computeIfAbsent(key, k -> new SquadBlackboard());
    }

    public static void removeDimension(int dimensionId) {
        String prefix = dimensionId + ":";
        BOARDS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote && event.getWorld().provider != null) {
            removeDimension(event.getWorld().provider.getDimension());
        }
    }

    public static void clearAll() {
        BOARDS.clear();
    }
}
