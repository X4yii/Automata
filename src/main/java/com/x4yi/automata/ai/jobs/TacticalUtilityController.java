package com.x4yi.automata.ai.jobs;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IRangedAttackMob;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemShield;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.entity.EntityAutomaton;

public final class TacticalUtilityController {

    private static final float FALL_RESCUE_DISTANCE = 6.5F;
    private static final double PEARL_CHASE_MIN_SQ = 324.0D;
    private static final double PEARL_ESCAPE_MAX_SQ = 64.0D;

    private TacticalUtilityController() {}

    public static boolean tryEmergencyPearlRescue(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        long now = ctx.getWorldTime();
        if (!ctx.canUseEnderPearl(now) || !hasEnderPearl(bot)) {
            return false;
        }
        if (bot.fallDistance > FALL_RESCUE_DISTANCE && !bot.onGround && !bot.isInWater()) {
            BlockPos safe = findSafeGroundAround(bot, 10);
            if (safe != null) {
                return throwPearlToward(ctx, bot, new Vec3d(safe.getX() + 0.5D, safe.getY() + 0.5D, safe.getZ() + 0.5D), now);
            }
        }
        if (bot.isInLava()) {
            BlockPos safe = findSafeGroundAround(bot, 14);
            if (safe != null) {
                return throwPearlToward(ctx, bot, new Vec3d(safe.getX() + 0.5D, safe.getY() + 0.5D, safe.getZ() + 0.5D), now);
            }
        }
        return false;
    }

    public static boolean tryCombatPearl(
            AutomatonAIContext ctx,
            EntityLivingBase target,
            double distSq,
            boolean lowHealth) {
        EntityAutomaton bot = ctx.getAutomaton();
        long now = ctx.getWorldTime();
        if (!ctx.canUseEnderPearl(now) || !hasEnderPearl(bot)) {
            return false;
        }
        if (lowHealth && distSq <= PEARL_ESCAPE_MAX_SQ) {
            Vec3d away = bot.getPositionVector().subtract(target.getPositionVector());
            if (away.lengthSquared() < 0.01D) {
                away = new Vec3d(1.0D, 0.0D, 0.0D);
            }
            Vec3d escapeVec = bot.getPositionVector().add(away.normalize().scale(14.0D));
            return throwPearlToward(ctx, bot, escapeVec.add(0.0D, 2.0D, 0.0D), now);
        }
        if (!lowHealth && distSq >= PEARL_CHASE_MIN_SQ && bot.canEntityBeSeen(target)) {
            return throwPearlToward(ctx, bot, target.getPositionVector().add(0.0D, 0.5D, 0.0D), now);
        }
        return false;
    }

    public static void updateShieldDefense(EntityAutomaton bot, @Nullable EntityLivingBase threat, double distSq) {
        ItemStack offhand = bot.getHeldItemOffhand();
        boolean hasShield = com.x4yi.automata.ai.EquipmentSelector.isShieldItem(offhand);
        if (!hasShield) {
            return;
        }
        boolean shouldBlock = shouldRaiseShield(bot, threat, distSq);
        if (!shouldBlock && threat != null && !bot.isEating()) {
            for (EntityLivingBase nearby : bot.world.getEntitiesWithinAABB(
                    EntityLivingBase.class,
                    bot.getEntityBoundingBox().grow(14.0D, 4.0D, 14.0D))) {
                if (nearby == bot || nearby == threat || !nearby.isEntityAlive()) {
                    continue;
                }
                double dSq = bot.getDistanceSq(nearby);
                if (nearby instanceof EntityCreeper creeper && creeper.getCreeperState() > 0 && dSq <= 36.0D) {
                    shouldBlock = true;
                    break;
                }
                if (nearby instanceof IRangedAttackMob && dSq <= 256.0D && bot.canEntityBeSeen(nearby)) {
                    shouldBlock = true;
                    break;
                }
            }
        }
        if (shouldBlock) {
            if (!bot.isHandActive() || bot.getActiveHand() != EnumHand.OFF_HAND) {
                bot.setActiveHand(EnumHand.OFF_HAND);
            }
        } else if (bot.isHandActive() && bot.getActiveHand() == EnumHand.OFF_HAND) {
            bot.resetActiveHand();
        }
    }

    private static boolean shouldRaiseShield(EntityAutomaton bot, @Nullable EntityLivingBase threat, double distSq) {
        if (threat == null || bot.isEating()) {
            return false;
        }
        if (threat instanceof EntityCreeper creeper && creeper.getCreeperState() > 0 && distSq <= 36.0D) {
            return true;
        }
        if ((threat instanceof IRangedAttackMob || AdaptiveThreatAnalyzer.isRangedOrSpecialThreat(threat))
                && distSq <= 256.0D
                && bot.canEntityBeSeen(threat)) {
            return !bot.isAttackReady() || distSq > 16.0D;
        }
        return distSq <= 12.25D && !bot.isAttackReady();
    }

    public static boolean tryDrinkConsumable(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (bot.isEating() || bot.ticksExisted % 20 != 0) {
            return false;
        }
        boolean needsHeal = bot.getHealthRatio() < 0.55F;
        boolean hasBadEffect = hasNegativePotionEffect(bot);
        if (!needsHeal && !hasBadEffect) {
            return false;
        }
        int slot = EquipmentSelector.findHealingOrCureConsumableSlot(bot, needsHeal, hasBadEffect);
        if (slot < 0) {
            return false;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        ItemStack stack = inv.extractItem(slot, 1, false);
        if (stack.isEmpty()) {
            return false;
        }
        ItemStack result = stack.getItem().onItemUseFinish(stack, bot.world, bot);
        if (!result.isEmpty()) {
            ItemStack remainder = inv.insertItem(slot, result, false);
            for (int i = 0; i < inv.getSlots() && !remainder.isEmpty(); i++) {
                remainder = inv.insertItem(i, remainder, false);
            }
            if (!remainder.isEmpty()) {
                bot.entityDropItem(remainder, 0.0F);
            }
        }
        bot.playSound(SoundEvents.ENTITY_GENERIC_DRINK, 0.7F, 1.0F);
        return true;
    }

    private static boolean hasNegativePotionEffect(EntityAutomaton bot) {
        for (PotionEffect effect : bot.getActivePotionEffects()) {
            if (effect.getPotion().isBadEffect()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnderPearl(EntityAutomaton bot) {
        if (bot.getHeldItemMainhand().getItem() instanceof ItemEnderPearl
                || bot.getHeldItemOffhand().getItem() instanceof ItemEnderPearl) {
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).getItem() instanceof ItemEnderPearl) {
                return true;
            }
        }
        return false;
    }

    private static boolean consumeEnderPearl(EntityAutomaton bot) {
        if (bot.getHeldItemOffhand().getItem() instanceof ItemEnderPearl) {
            bot.getHeldItemOffhand().shrink(1);
            return true;
        }
        if (bot.getHeldItemMainhand().getItem() instanceof ItemEnderPearl) {
            bot.getHeldItemMainhand().shrink(1);
            return true;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() instanceof ItemEnderPearl) {
                return !inv.extractItem(i, 1, false).isEmpty();
            }
        }
        return false;
    }

    private static boolean throwPearlToward(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            Vec3d destination,
            long now) {
        if (!consumeEnderPearl(bot)) {
            return false;
        }
        Vec3d eye = new Vec3d(bot.posX, bot.posY + bot.getEyeHeight(), bot.posZ);
        float[] angles = BallisticsSolver.solveEnderPearlAngles(eye, destination);
        ctx.getMovement().snapLook(angles[0], angles[1]);

        EntityEnderPearl pearl = new EntityEnderPearl(bot.world, bot);
        float yawRad = angles[0] * 0.017453292F;
        float pitchRad = angles[1] * 0.017453292F;
        double mx = -MathHelper.sin(yawRad) * MathHelper.cos(pitchRad);
        double my = -MathHelper.sin(pitchRad);
        double mz = MathHelper.cos(yawRad) * MathHelper.cos(pitchRad);
        pearl.shoot(mx, my, mz, 1.5F, 1.0F);

        bot.world.spawnEntity(pearl);
        bot.world.playSound(
                null,
                bot.posX,
                bot.posY,
                bot.posZ,
                SoundEvents.ENTITY_ENDERPEARL_THROW,
                SoundCategory.NEUTRAL,
                0.5F,
                0.4F / (bot.getRNG().nextFloat() * 0.4F + 0.8F));
        bot.swingArm(EnumHand.MAIN_HAND);
        ctx.markEnderPearlUsed(now);
        return true;
    }

    @Nullable
    private static BlockPos findSafeGroundAround(EntityAutomaton bot, int radius) {
        BlockPos origin = bot.getPosition();
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = origin.getX() + bot.getRNG().nextInt(radius * 2 + 1) - radius;
            int z = origin.getZ() + bot.getRNG().nextInt(radius * 2 + 1) - radius;
            for (int y = origin.getY() + 6; y >= Math.max(2, origin.getY() - 12); y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (bot.world.isBlockLoaded(pos)
                        && bot.world.getBlockState(pos.down()).getMaterial().isSolid()
                        && !bot.world.getBlockState(pos).getMaterial().isLiquid()
                        && bot.world.isAirBlock(pos)
                        && bot.world.isAirBlock(pos.up())) {
                    return pos;
                }
            }
        }
        return null;
    }
}

