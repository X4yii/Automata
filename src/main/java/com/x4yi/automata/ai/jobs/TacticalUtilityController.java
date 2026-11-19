package com.x4yi.automata.ai.jobs;

import java.util.List;

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

    public static boolean tickWaterDropClutch20Hz(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        long now = ctx.getWorldTime();
        BlockPos activeDrop = ctx.getActiveWaterDropPos();
        if (activeDrop != null) {
            if (now - ctx.getWaterDropPlacedTick() > 40L) {
                ctx.clearActiveWaterDrop();
            } else if (now - ctx.getWaterDropPlacedTick() >= 2L
                    && (bot.onGround || bot.isInWater())
                    && bot.getDistanceSqToCenter(activeDrop) <= 20.25D) {
                if (bot.world.getBlockState(activeDrop).getMaterial() == net.minecraft.block.material.Material.WATER
                        && swapBucketItem(bot, Items.BUCKET, Items.WATER_BUCKET)) {
                    bot.world.setBlockToAir(activeDrop);
                    bot.swingArm(EnumHand.MAIN_HAND);
                    bot.world.playSound(
                            null, bot.posX, bot.posY, bot.posZ,
                            SoundEvents.ITEM_BUCKET_FILL, SoundCategory.NEUTRAL, 0.7F, 1.0F);
                    ctx.clearActiveWaterDrop();
                    return true;
                }
            }
        }

        if (bot.onGround || bot.isInWater() || bot.isInLava() || bot.fallDistance <= 3.2F || bot.motionY >= -0.15D) {
            return false;
        }
        if (bot.world.provider.doesWaterVaporize()) {
            return false;
        }
        BlockPos feet = bot.getPosition();
        BlockPos waterPos = null;
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos check = feet.down(dy);
            if (!bot.world.isBlockLoaded(check)) {
                break;
            }
            if (bot.world.getBlockState(check).getMaterial().blocksMovement()) {
                BlockPos above = check.up();
                net.minecraft.block.state.IBlockState aboveState = bot.world.getBlockState(above);
                if (!aboveState.getMaterial().isLiquid()
                        && (bot.world.isAirBlock(above) || aboveState.getBlock().isReplaceable(bot.world, above))) {
                    waterPos = above;
                }
                break;
            }
        }
        if (waterPos == null || !swapBucketItem(bot, Items.WATER_BUCKET, Items.BUCKET)) {
            return false;
        }
        ctx.getMovement().snapLook(bot.rotationYaw, 90.0F);
        bot.world.setBlockState(waterPos, net.minecraft.init.Blocks.FLOWING_WATER.getDefaultState(), 11);
        bot.fallDistance = 0.0F;
        bot.motionY = Math.max(bot.motionY, -0.08D);
        bot.swingArm(EnumHand.MAIN_HAND);
        bot.world.playSound(
                null, bot.posX, bot.posY, bot.posZ,
                SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.NEUTRAL, 0.7F, 1.0F);
        ctx.setActiveWaterDrop(waterPos, now);
        return true;
    }

    static boolean swapBucketItem(EntityAutomaton bot, net.minecraft.item.Item expected, net.minecraft.item.Item replacement) {
        if (bot.isEating()) {
            bot.cancelEating();
        }
        if (bot.getHeldItemMainhand().getItem() == expected) {
            if (bot.getHeldItemMainhand().getCount() == 1) {
                bot.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(replacement));
            } else {
                bot.getHeldItemMainhand().shrink(1);
                insertBucketReplacement(bot, new ItemStack(replacement));
            }
            return true;
        }
        if (bot.getHeldItemOffhand().getItem() == expected) {
            if (bot.getHeldItemOffhand().getCount() == 1) {
                bot.setHeldItem(EnumHand.OFF_HAND, new ItemStack(replacement));
            } else {
                bot.getHeldItemOffhand().shrink(1);
                insertBucketReplacement(bot, new ItemStack(replacement));
            }
            return true;
        }
        if (swapBucketInHandler(bot, bot.getInventory().getMainInventory(), expected, replacement)) {
            return true;
        }
        IItemHandlerModifiable backpack = bot.getInventory().getBackpackHandler();
        return backpack != null && swapBucketInHandler(bot, backpack, expected, replacement);
    }

    private static boolean swapBucketInHandler(
            EntityAutomaton bot,
            IItemHandlerModifiable inv,
            net.minecraft.item.Item expected,
            net.minecraft.item.Item replacement) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty() || s.getItem() != expected) {
                continue;
            }
            if (s.getCount() == 1) {
                inv.setStackInSlot(i, new ItemStack(replacement));
                return true;
            }
            ItemStack extracted = inv.extractItem(i, 1, false);
            if (!extracted.isEmpty()) {
                insertBucketReplacement(bot, new ItemStack(replacement));
                return true;
            }
        }
        return false;
    }

    private static void insertBucketReplacement(EntityAutomaton bot, ItemStack stack) {
        ItemStack rem = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(
                bot.getInventory().getMainInventory(), stack, false);
        if (!rem.isEmpty()) {
            IItemHandlerModifiable backpack = bot.getInventory().getBackpackHandler();
            if (backpack != null) {
                rem = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(backpack, rem, false);
            }
        }
        if (!rem.isEmpty()) {
            bot.entityDropItem(rem, 0.0F);
        }
    }

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
        if (!lowHealth && shouldThrowChasePearl(
                distSq,
                AdaptiveThreatAnalyzer.isAerialThreat(bot, target),
                target.onGround,
                target.posY - bot.posY,
                bot.canEntityBeSeen(target))) {
            return throwPearlToward(ctx, bot, target.getPositionVector().add(0.0D, 0.5D, 0.0D), now);
        }
        return false;
    }

    static boolean shouldThrowChasePearl(
            double distSq,
            boolean aerialThreat,
            boolean targetGrounded,
            double dy,
            boolean canSee) {
        return distSq >= PEARL_CHASE_MIN_SQ
                && !aerialThreat
                && (targetGrounded || Math.abs(dy) <= 2.5D)
                && canSee;
    }

    public static void updateShieldDefense(EntityAutomaton bot, @Nullable EntityLivingBase threat, double distSq) {
        updateShieldDefense(null, bot, threat, distSq);
    }

    public static void updateShieldDefense(
            @Nullable AutomatonAIContext ctx,
            EntityAutomaton bot,
            @Nullable EntityLivingBase threat,
            double distSq) {
        ItemStack offhand = bot.getHeldItemOffhand();
        boolean hasShield = com.x4yi.automata.ai.EquipmentSelector.isShieldItem(offhand)
                && !com.x4yi.automata.compat.ModCompatBridge.isTwoHandedWeapon(bot.getHeldItemMainhand());
        if (!hasShield) {
            return;
        }
        boolean shouldBlock = shouldRaiseShield(bot, threat, distSq);
        if (!shouldBlock && threat != null && !bot.isEating()) {
            List<EntityLivingBase> living = ctx != null
                    ? ctx.getNearbyLivingEntities(16.0D)
                    : bot.world.getEntitiesWithinAABB(EntityLivingBase.class, bot.getEntityBoundingBox().grow(14.0D, 4.0D, 14.0D));
            for (int i = 0, n = living.size(); i < n; i++) {
                EntityLivingBase nearby = living.get(i);
                if (nearby == bot || nearby == threat || !nearby.isEntityAlive()) {
                    continue;
                }
                double dSq = bot.getDistanceSq(nearby);
                if (dSq > 256.0D) {
                    continue;
                }
                if (((nearby instanceof EntityCreeper creeper && creeper.getCreeperState() > 0)
                        || com.x4yi.automata.compat.ModCompatBridge.isImminentSwellingExplosive(nearby))
                        && dSq <= 36.0D) {
                    shouldBlock = true;
                    break;
                }
                if ((nearby instanceof IRangedAttackMob
                        || com.x4yi.automata.compat.ModCompatBridge.isModdedRangedOrPuller(nearby))
                        && (ctx != null ? ctx.canSeeEntityCached(nearby) : bot.canEntityBeSeen(nearby))) {
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
        if (((threat instanceof EntityCreeper creeper && creeper.getCreeperState() > 0)
                || com.x4yi.automata.compat.ModCompatBridge.isImminentSwellingExplosive(threat))
                && distSq <= 36.0D) {
            return true;
        }
        if ((threat instanceof IRangedAttackMob
                || com.x4yi.automata.compat.ModCompatBridge.isModdedRangedOrPuller(threat)
                || AdaptiveThreatAnalyzer.isRangedOrSpecialThreat(threat))
                && distSq <= 256.0D
                && bot.canEntityBeSeen(threat)) {
            return !bot.isAttackReady() || distSq > 16.0D;
        }
        return !bot.isAttackReady() && (distSq <= 20.25D || bot.hurtTime > 0);
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

