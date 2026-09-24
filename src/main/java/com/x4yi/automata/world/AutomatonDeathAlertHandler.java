package com.x4yi.automata.world;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Privately whispers bot coordinates to any player who dies within a bot's 3x3 or loaded chunks.
 */
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class AutomatonDeathAlertHandler {

    private AutomatonDeathAlertHandler() {}

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayerMP player)) {
            return;
        }
        World world = player.world;
        if (world.isRemote) {
            return;
        }
        ChunkPos deathChunk = new ChunkPos(player.getPosition());
        EntityAutomaton nearest = findNearestCoveringBot(world, player, deathChunk);
        if (nearest == null) {
            return;
        }
        BlockPos botPos = nearest.getPosition();
        player.sendMessage(new TextComponentString(
                TextFormatting.AQUA + "[Automata #" + nearest.getEntityId() + "] "
                        + TextFormatting.GRAY + "Coords: "
                        + TextFormatting.WHITE + botPos.getX() + ", " + botPos.getY() + ", " + botPos.getZ()));
    }

    private static EntityAutomaton findNearestCoveringBot(World world, EntityPlayerMP player, ChunkPos deathChunk) {
        EntityAutomaton nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityAutomaton bot : world.getEntities(EntityAutomaton.class, e -> e.isEntityAlive() && !e.isDowned())) {
            if (!bot.getChunkLoader().isChunkCovered(deathChunk.x, deathChunk.z)) {
                continue;
            }
            double distSq = bot.getDistanceSq(player);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = bot;
            }
        }
        return nearest;
    }
}

