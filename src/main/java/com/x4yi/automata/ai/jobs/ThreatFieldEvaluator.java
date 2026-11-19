package com.x4yi.automata.ai.jobs;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.IMob;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.compat.ModCompatBridge;
import com.x4yi.automata.entity.EntityAutomaton;

public final class ThreatFieldEvaluator {

    private static final double DEFAULT_SCAN_RADIUS = 14.0D;
    private static final int SECTOR_COUNT = 8;

    private ThreatFieldEvaluator() {}

    public static ThreatAssessment assessLocalThreats(AutomatonAIContext ctx, EntityAutomaton bot) {
        List<EntityLivingBase> nearby = ctx.getNearbyLivingEntities(DEFAULT_SCAN_RADIUS);
        double maxDistSq = DEFAULT_SCAN_RADIUS * DEFAULT_SCAN_RADIUS;
        double repulseX = 0.0D;
        double repulseZ = 0.0D;
        int occupiedMask = 0;
        int closeCount = 0;
        int enemyCount = 0;

        double allySumX = 0.0D;
        double allySumZ = 0.0D;
        int allyCount = 0;

        for (int i = 0, n = nearby.size(); i < n; i++) {
            EntityLivingBase entity = nearby.get(i);
            if (entity == bot || !entity.isEntityAlive() || bot.getDistanceSq(entity) > maxDistSq) {
                continue;
            }
            if (entity instanceof EntityAutomaton ally) {
                if (!ally.isDowned() && bot.isAlliedWith(ally)) {
                    allySumX += ally.posX;
                    allySumZ += ally.posZ;
                    allyCount++;
                }
                continue;
            }
            if ((entity instanceof IMob || entity == bot.getRevengeTarget())
                    && CombatTargetingService.isValidCombatCandidate(ctx, entity)) {
                boolean swelling = (entity instanceof EntityCreeper creeper && creeper.getCreeperState() > 0)
                        || ModCompatBridge.isImminentSwellingExplosive(entity);
                double weight = swelling ? 3.5D : 1.0D;
                double dx = bot.posX - entity.posX;
                double dz = bot.posZ - entity.posZ;
                double distSq = Math.max(0.35D, dx * dx + dz * dz);
                if (distSq <= 36.0D) {
                    closeCount++;
                }
                double inv = weight / (distSq * Math.sqrt(distSq));
                repulseX += dx * inv;
                repulseZ += dz * inv;
                double angle = Math.atan2(-dz, -dx);
                int sector = MathHelper.floor(((angle + Math.PI) / (2.0D * Math.PI)) * SECTOR_COUNT) & (SECTOR_COUNT - 1);
                occupiedMask |= (1 << sector);
                enemyCount++;
            }
        }

        if (enemyCount == 0) {
            return new ThreatAssessment(0.0D, 0.0D, 0, 0, false);
        }

        if (allyCount > 0) {
            double adx = (allySumX / allyCount) - bot.posX;
            double adz = (allySumZ / allyCount) - bot.posZ;
            double aDist = Math.sqrt(adx * adx + adz * adz);
            if (aDist > 3.5D) {
                repulseX += (adx / aDist) * 0.25D;
                repulseZ += (adz / aDist) * 0.25D;
            }
        }

        int occupiedCount = Integer.bitCount(occupiedMask);
        double len = Math.sqrt(repulseX * repulseX + repulseZ * repulseZ);
        double normX = len > 1.0E-5D ? repulseX / len : 0.0D;
        double normZ = len > 1.0E-5D ? repulseZ / len : 0.0D;
        boolean cornered = closeCount >= 3 || (closeCount >= 2 && occupiedCount >= 4);
        return new ThreatAssessment(normX, normZ, enemyCount, occupiedCount, cornered);
    }

    public static ThreatAssessment computeThreatAssessment(
            double selfX,
            double selfZ,
            List<double[]> enemies,
            List<double[]> allies) {
        if (enemies.isEmpty()) {
            return new ThreatAssessment(0.0D, 0.0D, 0, 0, false);
        }

        double repulseX = 0.0D;
        double repulseZ = 0.0D;
        boolean[] occupiedSectors = new boolean[SECTOR_COUNT];
        int closeCount = 0;

        for (double[] enemy : enemies) {
            double dx = selfX - enemy[0];
            double dz = selfZ - enemy[1];
            double weight = enemy.length > 2 ? enemy[2] : 1.0D;
            double distSq = Math.max(0.35D, dx * dx + dz * dz);
            if (distSq <= 36.0D) {
                closeCount++;
            }
            double inv = weight / (distSq * Math.sqrt(distSq));
            repulseX += dx * inv;
            repulseZ += dz * inv;

            double angle = Math.atan2(-dz, -dx);
            int sector = MathHelper.floor(((angle + Math.PI) / (2.0D * Math.PI)) * SECTOR_COUNT) & (SECTOR_COUNT - 1);
            occupiedSectors[sector] = true;
        }

        int occupiedCount = 0;
        for (boolean occupied : occupiedSectors) {
            if (occupied) {
                occupiedCount++;
            }
        }

        if (!allies.isEmpty()) {
            double allyX = 0.0D;
            double allyZ = 0.0D;
            for (double[] ally : allies) {
                allyX += ally[0];
                allyZ += ally[1];
            }
            allyX /= allies.size();
            allyZ /= allies.size();
            double adx = allyX - selfX;
            double adz = allyZ - selfZ;
            double aDist = Math.sqrt(adx * adx + adz * adz);
            if (aDist > 3.5D) {
                repulseX += (adx / aDist) * 0.25D;
                repulseZ += (adz / aDist) * 0.25D;
            }
        }

        double len = Math.sqrt(repulseX * repulseX + repulseZ * repulseZ);
        double normX = len > 1.0E-5D ? repulseX / len : 0.0D;
        double normZ = len > 1.0E-5D ? repulseZ / len : 0.0D;
        boolean cornered = closeCount >= 3 || (closeCount >= 2 && occupiedCount >= 4);
        return new ThreatAssessment(normX, normZ, enemies.size(), occupiedCount, cornered);
    }

    @Nullable
    public static BlockPos findEscapeAnchor(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            ThreatAssessment assessment,
            double distance) {
        if (!assessment.hasEscapeVector()) {
            return null;
        }
        WorldServer world = ctx.getWorld();
        double baseYaw = Math.atan2(assessment.escapeZ(), assessment.escapeX());
        double[] offsets = {0.0D, 0.55D, -0.55D, 1.1D, -1.1D};
        int baseY = MathHelper.floor(bot.posY + 0.2D);

        for (double offset : offsets) {
            double angle = baseYaw + offset;
            int tx = MathHelper.floor(bot.posX + Math.cos(angle) * distance);
            int tz = MathHelper.floor(bot.posZ + Math.sin(angle) * distance);
            BlockPos standable = findStandableNearY(world, tx, baseY, tz);
            if (standable != null) {
                return standable;
            }
        }
        return null;
    }

    public static boolean hasOtherEnemyNear(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            BlockPos pos,
            @Nullable EntityLivingBase currentTarget) {
        AxisAlignedBB box = new AxisAlignedBB(pos).grow(1.6D, 1.5D, 1.6D);
        for (EntityLivingBase entity : bot.world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (entity == bot || entity == currentTarget || !entity.isEntityAlive()) {
                continue;
            }
            if ((entity instanceof IMob || entity == bot.getRevengeTarget())
                    && CombatTargetingService.isValidCombatCandidate(ctx, entity)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos findStandableNearY(WorldServer world, int x, int baseY, int z) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos feet = new BlockPos(x, baseY + dy, z);
            if (!world.isBlockLoaded(feet)) {
                continue;
            }
            if (world.getBlockState(feet.down()).getMaterial().isSolid()
                    && !world.getBlockState(feet).getMaterial().blocksMovement()
                    && !world.getBlockState(feet.up()).getMaterial().blocksMovement()
                    && !world.getBlockState(feet).getMaterial().isLiquid()
                    && !world.getBlockState(feet.down()).getMaterial().isLiquid()) {
                return feet;
            }
        }
        return null;
    }

    public record ThreatAssessment(
            double escapeX,
            double escapeZ,
            int enemyCount,
            int occupiedSectors,
            boolean cornered) {

        public boolean hasEscapeVector() {
            return escapeX * escapeX + escapeZ * escapeZ > 0.01D;
        }
    }
}

