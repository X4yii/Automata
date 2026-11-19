package com.x4yi.automata.ai.jobs;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.movement.CellClassifier;

public class RoamJob implements IAutomatonJob {

    private static final double ZONE_SCAN_RADIUS = 28.0D;
    private static final double ZONE_ENGAGE_MARGIN = 12.0D;

    @Nullable
    private BlockPos currentWaypoint;
    private long nextPatrolTick = -1L;

    @Override
    public ResourceLocation getJobId() {
        return AutomatonBuiltInJobs.ROAM;
    }

    @Override
    public boolean hasCommittedWork(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        return ctx.getCombatTacticsState().activeTargetId >= 0
                || (ctx.getWorldTime() - ctx.getCombatTacticsState().lastEngagedTick <= 40L)
                || ctx.getAutomaton().getRevengeTarget() != null;
    }

    @Override
    public void tick(IAutomatonContext publicCtx) {
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        EntityAutomaton bot = ctx.getAutomaton();
        AutomatonTaskConfig cfg = ctx.getTaskConfig();

        if (!canRoam(cfg)) {
            currentWaypoint = null;
            bot.getChunkLoader().setExtraWorkChunk(null);
            if (ctx.getMovement().hasActivePath()) {
                ctx.stopNavigation();
            }
            return;
        }

        BlockPos p1 = cfg.getPos1();
        BlockPos p2 = cfg.getPos2();
        int minX = Math.min(p1.getX(), p2.getX());
        int maxX = Math.max(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY());
        int maxY = Math.max(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxZ = Math.max(p1.getZ(), p2.getZ());

        EntityLivingBase target = CombatJobSupport.findNearestTarget(
                ctx,
                bot.getPosition(),
                ZONE_SCAN_RADIUS,
                candidate -> isWithinZoneMargin(candidate.posX, candidate.posZ, minX, maxX, minZ, maxZ, ZONE_ENGAGE_MARGIN));
        if (target != null) {
            bot.getChunkLoader().setExtraWorkChunk(target.getPosition());
            CombatJobSupport.engageTarget(ctx, target, false);
            return;
        }

        CombatJobSupport.cancelBowDraw(ctx);
        if (CombatJobSupport.tryRecoverOwnedThrownWeapons(ctx, bot)) {
            return;
        }
        long now = ctx.getWorldTime();
        if (ctx.getMovement().hasActivePath()) {
            if (currentWaypoint != null) {
                bot.getChunkLoader().setExtraWorkChunk(currentWaypoint);
            }
            return;
        }

        if (currentWaypoint != null && bot.getDistanceSqToCenter(currentWaypoint) <= 6.25D) {
            currentWaypoint = null;
            nextPatrolTick = now + 40L + bot.getRNG().nextInt(41);
            return;
        }

        if (now < nextPatrolTick) {
            return;
        }

        BlockPos next = pickWalkableWaypoint(ctx.getWorld(), bot, minX, maxX, minY, maxY, minZ, maxZ);
        if (next != null) {
            currentWaypoint = next;
            bot.getChunkLoader().setExtraWorkChunk(next);
            ctx.requestPathTo(next, false);
        } else {
            nextPatrolTick = now + 30L;
        }
    }

    @Override
    public void onStop(IAutomatonContext publicCtx) {
        currentWaypoint = null;
        nextPatrolTick = -1L;
        AutomatonAIContext ctx = AutomataAPI.requireInternalContext(publicCtx);
        ctx.getAutomaton().getChunkLoader().setExtraWorkChunk(null);
    }

    static boolean canRoam(@Nullable AutomatonTaskConfig cfg) {
        return cfg != null && cfg.getPos1() != null && cfg.getPos2() != null;
    }

    static boolean isWithinZoneMargin(
            double x,
            double z,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            double margin) {
        return x >= (minX - margin)
                && x <= (maxX + 1 + margin)
                && z >= (minZ - margin)
                && z <= (maxZ + 1 + margin);
    }

    @Nullable
    private static BlockPos pickWalkableWaypoint(
            WorldServer world,
            EntityAutomaton bot,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ) {
        int spanX = Math.max(1, maxX - minX + 1);
        int spanZ = Math.max(1, maxZ - minZ + 1);
        int topY = Math.min(254, maxY + 4);
        int bottomY = Math.max(1, minY - 4);
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = minX + bot.getRNG().nextInt(spanX);
            int z = minZ + bot.getRNG().nextInt(spanZ);
            for (int y = topY; y >= bottomY; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (world.isBlockLoaded(pos)
                        && CellClassifier.isSolidSupport(world, pos.down())
                        && CellClassifier.isPassable(world, pos)
                        && CellClassifier.isPassable(world, pos.up())) {
                    return pos;
                }
            }
        }
        return null;
    }
}

