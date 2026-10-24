
package com.x4yi.automata.world;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;

public class AutomatonChunkLoader {

    private final EntityAutomaton automaton;
    private final Set<ChunkPos> forcedChunks = new HashSet<>();

    @Nullable
    private ForgeChunkManager.Ticket ticket;

    private int lastCenterChunkX = Integer.MIN_VALUE;
    private int lastCenterChunkZ = Integer.MIN_VALUE;
    private int lastRadius = -1;

    @Nullable
    private ChunkPos extraWorkChunk;

    public AutomatonChunkLoader(EntityAutomaton automaton) {
        this.automaton = automaton;
    }

    public void setExtraWorkChunk(@Nullable BlockPos pos) {
        ChunkPos next = pos == null ? null : new ChunkPos(pos);
        if ((next == null && extraWorkChunk != null) || (next != null && !next.equals(extraWorkChunk))) {
            extraWorkChunk = next;
            lastCenterChunkX = Integer.MIN_VALUE;
        }
    }

    public void update() {
        if (automaton.world.isRemote || automaton.isDead) {
            return;
        }
        if (automaton.isDowned() || !isOwnerPresentAndAlive()) {
            releaseAll();
            return;
        }
        int centerX = automaton.chunkCoordX;
        int centerZ = automaton.chunkCoordZ;
        int radius = Math.max(0, AutomataConfig.chunkLoadRadius);
        if (radius == 0 && extraWorkChunk == null) {
            releaseAll();
            return;
        }

        if (centerX == lastCenterChunkX && centerZ == lastCenterChunkZ && radius == lastRadius) {
            return;
        }

        if (ticket == null) {
            ticket = ForgeChunkManager.requestTicket(AutomataMod.instance, automaton.world, ForgeChunkManager.Type.ENTITY);
            if (ticket == null) {
                return;
            }
            ticket.bindEntity(automaton);
        }

        lastCenterChunkX = centerX;
        lastCenterChunkZ = centerZ;
        lastRadius = radius;

        Set<ChunkPos> desired = new LinkedHashSet<>();
        desired.add(new ChunkPos(centerX, centerZ));
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    desired.add(new ChunkPos(centerX + dx, centerZ + dz));
                }
            }
        }
        if (extraWorkChunk != null) {
            desired.add(extraWorkChunk);
        }

        int maxDepth = ticket.getMaxChunkListDepth();
        Iterator<ChunkPos> existingIt = forcedChunks.iterator();
        while (existingIt.hasNext()) {
            ChunkPos pos = existingIt.next();
            if (!desired.contains(pos)) {
                ForgeChunkManager.unforceChunk(ticket, pos);
                existingIt.remove();
            }
        }

        for (ChunkPos pos : desired) {
            if (forcedChunks.size() >= maxDepth) {
                break;
            }
            if (forcedChunks.add(pos)) {
                ForgeChunkManager.forceChunk(ticket, pos);
            }
        }
    }

    private boolean isOwnerPresentAndAlive() {
        java.util.UUID ownerUuid = automaton.getOwnerUuid();
        MinecraftServer server = automaton.world.getMinecraftServer();
        if (ownerUuid == null || server == null) {
            return false;
        }
        EntityPlayerMP owner = server.getPlayerList().getPlayerByUUID(ownerUuid);
        return owner != null && owner.isEntityAlive();
    }

    public boolean isChunkCovered(int chunkX, int chunkZ) {
        if (Math.abs(chunkX - automaton.chunkCoordX) <= 1 && Math.abs(chunkZ - automaton.chunkCoordZ) <= 1) {
            return true;
        }
        return forcedChunks.contains(new ChunkPos(chunkX, chunkZ));
    }

    public int getForcedChunkCount() {
        return forcedChunks.size();
    }

    public void releaseAll() {
        if (ticket != null) {
            for (ChunkPos pos : forcedChunks) {
                ForgeChunkManager.unforceChunk(ticket, pos);
            }
            forcedChunks.clear();
            ForgeChunkManager.releaseTicket(ticket);
            ticket = null;
        }
        lastCenterChunkX = Integer.MIN_VALUE;
        lastCenterChunkZ = Integer.MIN_VALUE;
    }

    public void bindLoadedTicket(ForgeChunkManager.Ticket loadedTicket) {
        releaseAll();
        this.ticket = loadedTicket;
        this.forcedChunks.addAll(loadedTicket.getChunkList());
        this.lastCenterChunkX = Integer.MIN_VALUE;
    }

    public static final class Callback implements ForgeChunkManager.LoadingCallback {
        @Override
        public void ticketsLoaded(List<ForgeChunkManager.Ticket> tickets, World world) {
            for (ForgeChunkManager.Ticket t : tickets) {
                Entity entity = t.getEntity();
                if (entity instanceof EntityAutomaton automaton) {
                    automaton.getChunkLoader().bindLoadedTicket(t);
                } else {
                    ForgeChunkManager.releaseTicket(t);
                }
            }
        }
    }
}

