
package com.x4yi.automata.ai;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.IMob;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.jobs.CombatJobSupport;
import com.x4yi.automata.coordination.SquadBlackboard;
import com.x4yi.automata.entity.EntityAutomaton;

public final class SurvivalEvaluator {

    public enum HazardResponse {
        LAVA_PILLAR_AND_ESCAPE,
        SEEK_WATER,
        SEEK_SHADE_COVER,
        FLEE_HAZARD
    }

    private static final double DANGER_SCAN_RADIUS = 8.0D;
    private static final int WATER_EXTINGUISH_SCAN_RADIUS = 14;
    private static final int COVER_SCAN_RADIUS = 10;
    private static final int LAVA_ESCAPE_SCAN_RADIUS = 10;
    private static final double REVIVE_RANGE_SQ = 6.25D;
    private static final double REVIVE_SCAN_RADIUS = 24.0D;
    private static final long REVIVE_CHANNEL_TICKS = 40L;
    private static final double REVIVER_GUARD_SCAN_RADIUS = 16.0D;
    private static final double REVIVER_GUARD_RANGE_SQ = 12.25D;

    private SurvivalEvaluator() {}

    public static boolean handleSurvival(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (bot.isInLava() || bot.isBurning()) {
            ctx.resetReviveChannel();
            escapeHazard(ctx, bot.getPosition());
            return true;
        }

        Entity explosive = findImminentExplosive(bot);
        if (explosive != null) {
            ctx.resetReviveChannel();
            fleeFrom(ctx, explosive.getPositionVector());
            return true;
        }

        long now = ctx.getWorldTime();
        SquadBlackboard board = ctx.getBlackboard();
        boolean coop = ctx.getTaskConfig().isSquadCooperation();
        if (coop) {
            SquadBlackboard.ReviveAlert activeRevive = board.findActiveReviverForSquad(bot.getEntityId(), now);
            if (activeRevive != null) {
                Entity reviverEntity = ctx.getWorld().getEntityByID(activeRevive.reviverEntityId());
                if (reviverEntity instanceof EntityAutomaton reviver && reviver.isEntityAlive() && !reviver.isDowned()) {
                    defendReviverAtAllCosts(ctx, bot, reviver);
                    ctx.resetReviveChannel();
                    return true;
                }
                board.clearReviveInProgress(activeRevive.reviverEntityId());
            }
        }

        EntityAutomaton downedBot = findNearestRevivableAlly(ctx, bot, board, coop, now);
        if (downedBot != null) {
            if (coop && board.isDownedBeingRevivedByOther(bot.getEntityId(), downedBot.getEntityId(), now)) {
                ctx.resetReviveChannel();
                return false;
            }
            if (bot.getDistanceSq(downedBot) <= REVIVE_RANGE_SQ) {
                EntityLivingBase perimeterThreat = findImmediatePerimeterThreat(ctx, bot, downedBot);
                if (perimeterThreat != null) {
                    board.clearReviveInProgress(bot.getEntityId());
                    ctx.resetReviveChannel();
                    CombatJobSupport.engageTarget(ctx, perimeterThreat, true);
                    return true;
                }
                ctx.stopNavigation();
                ctx.getMovement().lookSmoothlyAt(downedBot.posX, downedBot.posY + 0.35D, downedBot.posZ);
                bot.swingArm(EnumHand.MAIN_HAND);
                board.reportReviveInProgress(bot.getEntityId(), downedBot.getEntityId(), bot.getPosition(), now);
                if (ctx.advanceReviveChannel(downedBot.getEntityId()) >= REVIVE_CHANNEL_TICKS) {
                    downedBot.revive(0.4F);
                    downedBot.world.setEntityState(downedBot, (byte) 7);
                    board.clearDowned(downedBot.getEntityId());
                    board.clearReviveInProgress(bot.getEntityId());
                    ctx.resetReviveChannel();
                }
            } else {
                board.clearReviveInProgress(bot.getEntityId());
                ctx.resetReviveChannel();
                ctx.requestPathTo(downedBot.getPosition(), true);
            }
            return true;
        }
        board.clearReviveInProgress(bot.getEntityId());

        ctx.resetReviveChannel();
        return false;
    }

    private static EntityAutomaton findNearestRevivableAlly(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            SquadBlackboard board,
            boolean coop,
            long now) {
        if (coop) {
            Map.Entry<Integer, BlockPos> downed = board.findNearestDowned(bot.getEntityId(), bot.getPosition());
            if (downed != null) {
                Entity ally = ctx.getWorld().getEntityByID(downed.getKey());
                if (ally instanceof EntityAutomaton downedBot && downedBot.isDowned()) {
                    return downedBot;
                }
                board.clearDowned(downed.getKey());
            }
        }
        List<EntityAutomaton> nearby = bot.world.getEntitiesWithinAABB(
                EntityAutomaton.class,
                bot.getEntityBoundingBox().grow(REVIVE_SCAN_RADIUS),
                candidate -> candidate != null
                        && candidate != bot
                        && candidate.isEntityAlive()
                        && candidate.isDowned()
                        && (bot.getOwnerUuid() == null || bot.getOwnerUuid().equals(candidate.getOwnerUuid())));
        EntityAutomaton best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityAutomaton candidate : nearby) {
            if (coop && board.isDownedBeingRevivedByOther(bot.getEntityId(), candidate.getEntityId(), now)) {
                continue;
            }
            double dSq = bot.getDistanceSq(candidate);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = candidate;
            }
        }
        return best;
    }

    private static void defendReviverAtAllCosts(AutomatonAIContext ctx, EntityAutomaton defender, EntityAutomaton reviver) {
        AxisAlignedBB box = reviver.getEntityBoundingBox().grow(REVIVER_GUARD_SCAN_RADIUS);
        List<EntityLivingBase> candidates = defender.world.getEntitiesWithinAABB(
                EntityLivingBase.class,
                box,
                e -> e != null && e != defender && e != reviver && CombatJobSupport.isValidCombatCandidate(ctx, e));
        EntityLivingBase nearestThreat = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityLivingBase candidate : candidates) {
            double distSq = reviver.getDistanceSq(candidate);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearestThreat = candidate;
            }
        }
        if (nearestThreat != null) {
            CombatJobSupport.engageTarget(ctx, nearestThreat, true);
        } else if (defender.getDistanceSq(reviver) > REVIVER_GUARD_RANGE_SQ) {
            ctx.requestPathTo(reviver.getPosition(), true);
        } else {
            ctx.stopNavigation();
        }
    }

    private static Entity findImminentExplosive(EntityAutomaton bot) {
        AxisAlignedBB box = bot.getEntityBoundingBox().grow(DANGER_SCAN_RADIUS);
        List<Entity> nearby = bot.world.getEntitiesWithinAABBExcludingEntity(bot, box);
        for (Entity entity : nearby) {
            if (entity instanceof EntityTNTPrimed) {
                return entity;
            }
            if (entity instanceof EntityCreeper creeper && creeper.getCreeperState() > 0) {
                return creeper;
            }
        }
        return null;
    }

    public static void tickLavaEscape20Hz(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (!bot.isInLava()) {
            return;
        }
        WorldServer world = ctx.getWorld();
        BlockPos feet = bot.getPosition();
        BlockPos below = feet.down();
        if (world.isBlockLoaded(below) && !world.getBlockState(below).getMaterial().isSolid()) {
            ctx.getMovement().placeSupportBlockAt(world, below, false, true);
        }
        if (world.isBlockLoaded(feet) && world.getBlockState(feet).getMaterial() == Material.LAVA) {
            ctx.getMovement().placeSupportBlockAt(world, feet, false, true);
        }
        if (bot.motionY < 0.25D) {
            bot.motionY = 0.25D;
        }
        bot.getJumpHelper().setJumping();
    }

    static HazardResponse classifyHazardResponse(boolean inLava, boolean hasWaterNearby, boolean hasShadeNearby) {
        if (inLava) {
            return HazardResponse.LAVA_PILLAR_AND_ESCAPE;
        }
        if (hasWaterNearby) {
            return HazardResponse.SEEK_WATER;
        }
        if (hasShadeNearby) {
            return HazardResponse.SEEK_SHADE_COVER;
        }
        return HazardResponse.FLEE_HAZARD;
    }

    private static void escapeHazard(AutomatonAIContext ctx, BlockPos pos) {
        EntityAutomaton bot = ctx.getAutomaton();
        WorldServer world = ctx.getWorld();
        boolean inLava = bot.isInLava();
        if (inLava) {
            tickLavaEscape20Hz(ctx);
            BlockPos drySpot = findNearestSafeDrySpot(world, pos, LAVA_ESCAPE_SCAN_RADIUS, 3, false);
            if (drySpot != null) {
                ctx.requestPathTo(drySpot, true);
                return;
            }
        } else {
            BlockPos waterSpot = findNearestWaterSpot(world, pos, WATER_EXTINGUISH_SCAN_RADIUS, 3);
            if (waterSpot != null) {
                ctx.requestPathTo(waterSpot, true);
                return;
            }
            BlockPos shadeSpot = findNearestSafeDrySpot(world, pos, COVER_SCAN_RADIUS, 3, true);
            if (shadeSpot != null) {
                ctx.requestPathTo(shadeSpot, true);
                return;
            }
            BlockPos safeSpot = findNearestSafeDrySpot(world, pos, 8, 2, false);
            if (safeSpot != null && safeSpot.distanceSq(pos) > 4.0D) {
                ctx.requestPathTo(safeSpot, true);
                return;
            }
        }
        if (bot.onGround || inLava) {
            bot.getJumpHelper().setJumping();
        }
        BlockPos escape = pos.add(bot.getRNG().nextInt(7) - 3, 1, bot.getRNG().nextInt(7) - 3);
        ctx.requestPathTo(escape, true);
    }

    @Nullable
    private static BlockPos findNearestWaterSpot(WorldServer world, BlockPos origin, int radiusXZ, int radiusY) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dy = -radiusY; dy <= radiusY; dy++) {
            for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
                for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (!world.isBlockLoaded(candidate)) {
                        continue;
                    }
                    if (world.getBlockState(candidate).getMaterial() != Material.WATER) {
                        continue;
                    }
                    BlockPos head = candidate.up();
                    if (!isPassableNonHazard(world, head)) {
                        continue;
                    }
                    double dSq = candidate.distanceSq(origin);
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    @Nullable
    private static BlockPos findNearestSafeDrySpot(
            WorldServer world,
            BlockPos origin,
            int radiusXZ,
            int radiusY,
            boolean requireShade) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dy = -radiusY; dy <= radiusY; dy++) {
            for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
                for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (!isSafeDrySpot(world, candidate)) {
                        continue;
                    }
                    if (requireShade && world.canSeeSky(candidate)) {
                        continue;
                    }
                    double dSq = candidate.distanceSq(origin);
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isSafeDrySpot(WorldServer world, BlockPos feet) {
        if (!world.isBlockLoaded(feet)) {
            return false;
        }
        if (!world.getBlockState(feet.down()).getMaterial().isSolid()) {
            return false;
        }
        if (!isPassableDryNonHazard(world, feet) || !isPassableDryNonHazard(world, feet.up())) {
            return false;
        }
        for (EnumFacing facing : EnumFacing.HORIZONTALS) {
            Material adj = world.getBlockState(feet.offset(facing)).getMaterial();
            if (adj == Material.LAVA || adj == Material.FIRE) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPassableNonHazard(WorldServer world, BlockPos pos) {
        Material mat = world.getBlockState(pos).getMaterial();
        if (mat == Material.LAVA || mat == Material.FIRE) {
            return false;
        }
        return world.isAirBlock(pos) || world.getBlockState(pos).getCollisionBoundingBox(world, pos) == null;
    }

    private static boolean isPassableDryNonHazard(WorldServer world, BlockPos pos) {
        Material mat = world.getBlockState(pos).getMaterial();
        if (mat == Material.WATER || mat == Material.LAVA || mat == Material.FIRE) {
            return false;
        }
        return world.isAirBlock(pos) || world.getBlockState(pos).getCollisionBoundingBox(world, pos) == null;
    }

    private static void fleeFrom(AutomatonAIContext ctx, Vec3d threatPos) {
        EntityAutomaton bot = ctx.getAutomaton();
        Vec3d away = bot.getPositionVector().subtract(threatPos);
        if (away.lengthSquared() < 0.01D) {
            away = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        Vec3d norm = away.normalize().scale(10.0D);
        BlockPos safeTarget = new BlockPos(bot.posX + norm.x, bot.posY, bot.posZ + norm.z);
        ctx.requestPathTo(safeTarget, true);
    }

    @Nullable
    private static EntityLivingBase findImmediatePerimeterThreat(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityAutomaton downedBot) {
        AxisAlignedBB box = downedBot.getEntityBoundingBox().grow(4.5D, 2.5D, 4.5D);
        EntityLivingBase nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityLivingBase candidate : bot.world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (!(candidate instanceof IMob) || !CombatJobSupport.isValidCombatCandidate(ctx, candidate)) {
                continue;
            }
            double dSq = bot.getDistanceSq(candidate);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                nearest = candidate;
            }
        }
        return nearest;
    }
}

