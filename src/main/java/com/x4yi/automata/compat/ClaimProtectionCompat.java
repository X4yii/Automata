package com.x4yi.automata.compat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.x4yi.x4ui.common.network.UINetwork;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.world.FakePlayerService;

/**
 * Isolated compatibility layer for spawn protection and claim/anti-grief mods
 * (e.g. FTB Utilities, GriefPrevention, WorldGuard).
 */
public final class ClaimProtectionCompat {

    private static final long OWNER_ALERT_COOLDOWN_TICKS = 100L;
    private static final Map<UUID, Long> LAST_ALERT_TICK = new ConcurrentHashMap<>();

    private ClaimProtectionCompat() {}

    /**
     * Checks whether {@code bot} is permitted to modify {@code pos} without triggering claim/protection denials.
     * Fires a dry-run {@link BlockEvent.BreakEvent} and checks server spawn protection.
     */
    public static boolean canModifyBlock(WorldServer world, EntityAutomaton bot, BlockPos pos) {
        if (world == null || bot == null || pos == null || !world.isBlockLoaded(pos)) {
            return false;
        }
        FakePlayer fake = FakePlayerService.acquireSynced(world, bot);
        MinecraftServer server = world.getMinecraftServer();
        if (server != null && server.isBlockProtected(world, pos, fake)) {
            notifyOwnerRateLimited(world, bot.getOwnerUuid(), world.getTotalWorldTime());
            return false;
        }
        BlockEvent.BreakEvent dryRun = new BlockEvent.BreakEvent(world, pos, world.getBlockState(pos), fake);
        if (MinecraftForge.EVENT_BUS.post(dryRun)) {
            notifyOwnerRateLimited(world, bot.getOwnerUuid(), world.getTotalWorldTime());
            return false;
        }
        return true;
    }

    /**
     * Checks whether {@code bot} is permitted to place {@code placedState} at {@code pos}
     * without triggering claim/protection denials.
     */
    public static boolean canPlaceBlock(
            WorldServer world,
            EntityAutomaton bot,
            BlockPos pos,
            @Nullable IBlockState placedState) {
        if (world == null || bot == null || pos == null || !world.isBlockLoaded(pos)) {
            return false;
        }
        FakePlayer fake = FakePlayerService.acquireSynced(world, bot);
        MinecraftServer server = world.getMinecraftServer();
        if (server != null && server.isBlockProtected(world, pos, fake)) {
            notifyOwnerRateLimited(world, bot.getOwnerUuid(), world.getTotalWorldTime());
            return false;
        }
        IBlockState state = placedState != null ? placedState : world.getBlockState(pos);
        BlockSnapshot snapshot = new BlockSnapshot(world, pos, state);
        BlockEvent.EntityPlaceEvent dryRun = new BlockEvent.EntityPlaceEvent(
                snapshot,
                world.getBlockState(pos.down()),
                fake);
        if (MinecraftForge.EVENT_BUS.post(dryRun)) {
            notifyOwnerRateLimited(world, bot.getOwnerUuid(), world.getTotalWorldTime());
            return false;
        }
        return true;
    }

    public static void clearAlerts() {
        LAST_ALERT_TICK.clear();
    }

    private static void notifyOwnerRateLimited(WorldServer world, @Nullable UUID ownerUuid, long worldTime) {
        if (ownerUuid == null) {
            return;
        }
        Long last = LAST_ALERT_TICK.get(ownerUuid);
        if (last != null && (worldTime - last) < OWNER_ALERT_COOLDOWN_TICKS) {
            return;
        }
        if (LAST_ALERT_TICK.size() >= 256) {
            LAST_ALERT_TICK.clear();
        }
        LAST_ALERT_TICK.put(ownerUuid, worldTime);
        EntityPlayer owner = world.getPlayerEntityByUUID(ownerUuid);
        if (owner instanceof EntityPlayerMP mpOwner) {
            UINetwork.sendToast(
                    mpOwner,
                    "Automata",
                    net.minecraft.util.text.translation.I18n.translateToLocal("message.automata.claim_protected"),
                    2.5F);
        }
    }
}
