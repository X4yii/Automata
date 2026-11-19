package com.x4yi.automata.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArrow;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.x4yi.automata.api.IAutomatonContext;
import com.x4yi.automata.compat.SpartanWeaponryCompat;
import com.x4yi.automata.coordination.SquadBlackboard;
import com.x4yi.automata.coordination.SquadBlackboard.ExchangeStage;
import com.x4yi.automata.coordination.SquadBlackboard.ItemExchangeOffer;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.inventory.AutomatonInventory;

public final class SquadSupplyService {

    public static final String GIFT_RECIPIENT_TAG = "AutomataGiftRecipientUuid";
    public static final String GIFT_GIVER_TAG = "AutomataGiftGiverUuid";

    public static final double FOOD_SHARE_RATIO = 0.40D;
    public static final double AMMO_SHARE_RATIO = 0.40D;

    private static final double ALLY_SCAN_RADIUS = 20.0D;
    private static final double EXCHANGE_DISTANCE_SQ = 1.25D;
    private static final double GIVER_RETREAT_MIN_DIST_SQ = 2.10D;
    private static final double GIVER_RETREAT_STEP = 1.35D;
    private static final long EXCHANGE_APPROACH_TTL_TICKS = 120L;
    private static final long EXCHANGE_DROPPED_TTL_TICKS = 60L;
    private static final float FULL_SHARE_OCCUPANCY = 0.80F;
    private static final float RECEIVER_MAX_OCCUPANCY = 0.50F;
    private static final int MIN_FOOD_TO_SHARE = 2;
    private static final int MIN_ARROWS_TO_KEEP = 8;
    private static final float ALLY_LOW_HUNGER_THRESHOLD = 14.0F;
    private static final int ALLY_LOW_FOOD_THRESHOLD = 4;

    private static final List<SupplyRule> SUPPLY_RULES = List.of(
            new WorstWeaponRule(),
            new FoodShareRule(),
            new AmmoShareRule(),
            new InventorySurplusRule());

    private SquadSupplyService() {}

    public static int computeShareAmount(int availableCount, double ratio, int minRemaining) {
        if (availableCount <= minRemaining || ratio <= 0.0D) {
            return 0;
        }
        int desired = Math.max(1, (int) Math.round(availableCount * ratio));
        return Math.min(desired, availableCount - minRemaining);
    }

    public static int computeFoodShareAmount(int giverFoodCount) {
        return computeShareAmount(giverFoodCount, FOOD_SHARE_RATIO, 1);
    }

    public static int computeAmmoShareAmount(int giverArrowCount) {
        return computeShareAmount(giverArrowCount, AMMO_SHARE_RATIO, MIN_ARROWS_TO_KEEP);
    }

    public static void tagGiftItemEntity(EntityItem itemEntity, EntityAutomaton giver, EntityAutomaton receiver) {
        NBTTagCompound data = itemEntity.getEntityData();
        data.setString(GIFT_RECIPIENT_TAG, receiver.getUniqueID().toString());
        data.setString(GIFT_GIVER_TAG, giver.getUniqueID().toString());
    }

    public static boolean canBotPickupItem(EntityAutomaton bot, EntityItem itemEntity) {
        NBTTagCompound data = itemEntity.getEntityData();
        if (data.hasKey(GIFT_RECIPIENT_TAG)) {
            String recipientUuid = data.getString(GIFT_RECIPIENT_TAG);
            if (!recipientUuid.isEmpty()) {
                return bot.getUniqueID().toString().equals(recipientUuid);
            }
        }
        return true;
    }

    public static boolean isGiftForBot(EntityAutomaton bot, EntityItem itemEntity) {
        NBTTagCompound data = itemEntity.getEntityData();
        if (!data.hasKey(GIFT_RECIPIENT_TAG)) {
            return false;
        }
        String recipientUuid = data.getString(GIFT_RECIPIENT_TAG);
        return !recipientUuid.isEmpty() && bot.getUniqueID().toString().equals(recipientUuid);
    }

    public static boolean tickActiveExchange(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        SquadBlackboard board = ctx.getBlackboard();
        long now = ctx.getWorldTime();
        ItemExchangeOffer offer = board.findExchangeForBot(bot.getEntityId(), now);
        if (offer == null) {
            return false;
        }
        if (!ctx.getTaskConfig().isSquadCooperation()
                || bot.isDowned()
                || bot.isEating()
                || bot.getRevengeTarget() != null) {
            board.completeExchange(offer.giverEntityId(), offer.receiverEntityId());
            return false;
        }

        boolean isGiver = offer.giverEntityId() == bot.getEntityId();
        int partnerId = isGiver ? offer.receiverEntityId() : offer.giverEntityId();
        Entity partnerEntity = bot.world.getEntityByID(partnerId);
        EntityAutomaton partner = partnerEntity instanceof EntityAutomaton alliedBot
                && alliedBot.isEntityAlive()
                && !alliedBot.isDowned()
                && bot.isAlliedWith(alliedBot)
                ? alliedBot
                : null;
        if (partner == null && offer.stage() == ExchangeStage.APPROACHING) {
            board.completeExchange(offer.giverEntityId(), offer.receiverEntityId());
            return false;
        }

        if (offer.stage() == ExchangeStage.APPROACHING) {
            return tickApproachingStage(ctx, bot, partner, isGiver, offer, board, now);
        }
        return tickDroppedStage(ctx, bot, partner, isGiver, offer, board, now);
    }

    private static boolean tickApproachingStage(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            EntityAutomaton partner,
            boolean isGiver,
            ItemExchangeOffer offer,
            SquadBlackboard board,
            long now) {
        ctx.setStatusMessage(isGiver ? "Sharing item" : "Receiving item");
        bot.getLookHelper().setLookPositionWithEntity(partner, 30.0F, 30.0F);
        double distSq = bot.getDistanceSq(partner);
        if (distSq > EXCHANGE_DISTANCE_SQ) {
            if (distSq <= 16.0D) {
                ctx.stopNavigation();
                ctx.getMovement().moveDirectlyToward(partner.posX, partner.posZ, 1.05F, false);
            } else {
                ctx.requestPathTo(partner.getPosition(), false);
            }
            if (isGiver) {
                board.refreshExchange(
                        offer.giverEntityId(),
                        offer.receiverEntityId(),
                        partner.getPosition(),
                        now,
                        EXCHANGE_APPROACH_TTL_TICKS);
            }
            return true;
        }

        ctx.stopNavigation();
        if (!isGiver) {
            return true;
        }

        List<ItemStack> itemsToDrop = extractFirstMatchingRule(ctx, bot, partner);
        if (itemsToDrop.isEmpty()) {
            board.completeExchange(offer.giverEntityId(), offer.receiverEntityId());
            return false;
        }
        for (ItemStack stack : itemsToDrop) {
            dropGiftTowardReceiver(bot, partner, stack);
        }
        bot.swingArm(EnumHand.MAIN_HAND);
        BlockPos dropAnchor = bot.getPosition();
        board.markExchangeDropped(
                offer.giverEntityId(),
                offer.receiverEntityId(),
                dropAnchor,
                now,
                EXCHANGE_DROPPED_TTL_TICKS);
        retreatOneBlockFrom(ctx, bot, partner, dropAnchor);
        return true;
    }

    private static boolean tickDroppedStage(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            @Nullable EntityAutomaton partner,
            boolean isGiver,
            ItemExchangeOffer offer,
            SquadBlackboard board,
            long now) {
        if (isGiver) {
            if (partner != null) {
                bot.getLookHelper().setLookPositionWithEntity(partner, 30.0F, 30.0F);
            }
            long elapsed = now - offer.droppedAtTick();
            double distFromDropSq = bot.getDistanceSqToCenter(offer.dropPos());
            if (distFromDropSq < GIVER_RETREAT_MIN_DIST_SQ && elapsed < 20L) {
                retreatOneBlockFrom(ctx, bot, partner, offer.dropPos());
                return true;
            }
            ctx.stopNavigation();
            if (elapsed >= 20L) {
                board.completeExchange(offer.giverEntityId(), offer.receiverEntityId());
            }
            return false;
        }

        EntityItem giftItem = findGiftItemForReceiver(bot, offer.dropPos());
        if (giftItem != null) {
            ctx.setStatusMessage("Receiving item");
            bot.getLookHelper().setLookPositionWithEntity(giftItem, 30.0F, 30.0F);
            if (bot.getDistanceSq(giftItem) > 0.64D) {
                ctx.stopNavigation();
                ctx.getMovement().moveDirectlyToward(giftItem.posX, giftItem.posZ, 1.05F, false);
            } else {
                ctx.stopNavigation();
                bot.tryPickupItem(giftItem);
            }
            return true;
        }

        if (now - offer.droppedAtTick() >= 4L) {
            ctx.stopNavigation();
            board.completeExchange(offer.giverEntityId(), offer.receiverEntityId());
            return false;
        }
        return true;
    }

    public static boolean tickSquadSupply(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        if (!ctx.getTaskConfig().isSquadCooperation()
                || bot.isDowned()
                || bot.isEating()
                || bot.getRevengeTarget() != null) {
            return false;
        }
        long now = ctx.getWorldTime();
        SquadBlackboard board = ctx.getBlackboard();
        if (board.findExchangeForBot(bot.getEntityId(), now) != null) {
            return false;
        }
        List<EntityAutomaton> allies = findNearbyAllies(ctx, bot);
        if (allies.isEmpty()) {
            return false;
        }

        EntityAutomaton target = findBestRecipient(ctx, bot, allies);
        if (target == null) {
            return false;
        }
        if (board.tryBeginExchange(
                bot.getEntityId(),
                target.getEntityId(),
                target.getPosition(),
                now,
                EXCHANGE_APPROACH_TTL_TICKS)) {
            return tickActiveExchange(ctx);
        }
        return false;
    }

    @Nullable
    private static EntityAutomaton findBestRecipient(
            IAutomatonContext ctx,
            EntityAutomaton giver,
            List<EntityAutomaton> allies) {
        for (SupplyRule rule : SUPPLY_RULES) {
            EntityAutomaton closest = null;
            double bestDistSq = Double.MAX_VALUE;
            for (EntityAutomaton ally : allies) {
                if (!rule.canSupply(ctx, giver, ally)) {
                    continue;
                }
                double dSq = giver.getDistanceSq(ally);
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    closest = ally;
                }
            }
            if (closest != null) {
                return closest;
            }
        }
        return null;
    }

    private static List<ItemStack> extractFirstMatchingRule(
            IAutomatonContext ctx,
            EntityAutomaton giver,
            EntityAutomaton receiver) {
        for (SupplyRule rule : SUPPLY_RULES) {
            if (rule.canSupply(ctx, giver, receiver)) {
                List<ItemStack> items = rule.extractSupply(ctx, giver, receiver);
                if (!items.isEmpty()) {
                    return items;
                }
            }
        }
        return Collections.emptyList();
    }

    private static void dropGiftTowardReceiver(
            EntityAutomaton giver,
            EntityAutomaton receiver,
            ItemStack stack) {
        double dx = receiver.posX - giver.posX;
        double dz = receiver.posZ - giver.posZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double nx = horiz > 1e-4D ? dx / horiz : 0.0D;
        double nz = horiz > 1e-4D ? dz / horiz : 1.0D;

        double spawnX = giver.posX + nx * 0.35D;
        double spawnY = giver.posY + giver.getEyeHeight() - 0.35D;
        double spawnZ = giver.posZ + nz * 0.35D;
        EntityItem drop = new EntityItem(giver.world, spawnX, spawnY, spawnZ, stack.copy());
        drop.motionX = nx * 0.16D;
        drop.motionY = 0.12D;
        drop.motionZ = nz * 0.16D;
        drop.setPickupDelay(5);
        tagGiftItemEntity(drop, giver, receiver);
        giver.world.spawnEntity(drop);
    }

    private static void retreatOneBlockFrom(
            AutomatonAIContext ctx,
            EntityAutomaton bot,
            @Nullable EntityAutomaton partner,
            BlockPos dropAnchor) {
        double refX = partner != null ? partner.posX : dropAnchor.getX() + 0.5D;
        double refZ = partner != null ? partner.posZ : dropAnchor.getZ() + 0.5D;
        double dx = bot.posX - refX;
        double dz = bot.posZ - refZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        double nx;
        double nz;
        if (len > 1e-4D) {
            nx = dx / len;
            nz = dz / len;
        } else {
            float yawRad = bot.rotationYaw * 0.017453292F;
            nx = Math.sin(yawRad);
            nz = -Math.cos(yawRad);
        }
        double targetX = bot.posX + nx * GIVER_RETREAT_STEP;
        double targetZ = bot.posZ + nz * GIVER_RETREAT_STEP;
        ctx.stopNavigation();
        ctx.getMovement().moveDirectlyToward(targetX, targetZ, 1.05F, false);
    }

    @Nullable
    private static EntityItem findGiftItemForReceiver(EntityAutomaton receiver, BlockPos anchor) {
        AxisAlignedBB box = new AxisAlignedBB(anchor).grow(6.0D, 3.0D, 6.0D);
        List<EntityItem> items = receiver.world.getEntitiesWithinAABB(EntityItem.class, box);
        EntityItem best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityItem item : items) {
            if (item == null || !item.isEntityAlive() || item.getItem().isEmpty() || !isGiftForBot(receiver, item)) {
                continue;
            }
            double dSq = receiver.getDistanceSq(item);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = item;
            }
        }
        return best;
    }

    public static double scoreWeapon(ItemStack stack) {
        if (stack.isEmpty() || !EquipmentSelector.hasSafeDurability(stack)) {
            return -1.0D;
        }
        if (com.x4yi.automata.compat.SrpCompat.isBookOfVengeance(stack)) {
            return 22.0D;
        }
        if (SpartanWeaponryCompat.isCrossbow(stack)) {
            return 7.0D;
        }
        if (EquipmentSelector.isBowItem(stack)) {
            return 6.0D;
        }
        if (SpartanWeaponryCompat.isBoomerang(stack) || SpartanWeaponryCompat.isThrowableWeapon(stack)) {
            return SpartanWeaponryCompat.getRemainingThrowingAmmo(stack) > 0 ? 5.0D : -1.0D;
        }
        double dmg = 1.0D;
        for (AttributeModifier mod : stack.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
            dmg += mod.getAmount();
        }
        if (EquipmentSelector.isCombatWeapon(stack)) {
            double attacksPerSec = Math.max(0.5D, 4.0D + SpartanWeaponryCompat.getAttackSpeedModifier(stack));
            double dpsFactor = 0.55D + Math.min(2.4D, attacksPerSec * 0.30D);
            double quickBonus = com.x4yi.automata.compat.ModdedWeaponCompat.isRapidSpamWeapon(stack) ? 2.5D : 0.0D;
            return dmg * dpsFactor + SpartanWeaponryCompat.getReachBonus(stack) * 1.25D + quickBonus;
        }
        return -1.0D;
    }

    public static boolean isSafeArrow(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemArrow)) {
            return false;
        }
        return stack.getItem().getRegistryName() == null
                || !stack.getItem().getRegistryName().getPath().toLowerCase(Locale.ROOT).contains("explosive");
    }

    private static List<WeaponEntry> collectWeapons(EntityAutomaton bot) {
        List<WeaponEntry> list = new ArrayList<>();
        ItemStack main = bot.getHeldItemMainhand();
        double mainScore = scoreWeapon(main);
        if (mainScore > 0.0D) {
            list.add(new WeaponEntry(-1, mainScore));
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            double score = scoreWeapon(s);
            if (score > 0.0D) {
                list.add(new WeaponEntry(i, score));
            }
        }
        return list;
    }

    private static ItemStack extractEntry(EntityAutomaton bot, int slotIndex) {
        if (slotIndex < 0) {
            ItemStack held = bot.getHeldItemMainhand().copy();
            bot.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            return held;
        }
        return bot.getInventory().getCombinedInventory().extractItem(slotIndex, 1, false);
    }

    private static List<ItemStack> extractMatchingItems(
            EntityAutomaton giver,
            Predicate<ItemStack> matcher,
            int maxCount) {
        List<ItemStack> out = new ArrayList<>();
        IItemHandlerModifiable gInv = giver.getInventory().getCombinedInventory();
        int remaining = maxCount;
        for (int i = 0; i < gInv.getSlots() && remaining > 0; i++) {
            ItemStack s = gInv.getStackInSlot(i);
            if (s.isEmpty() || !matcher.test(s)) {
                continue;
            }
            int take = Math.min(s.getCount(), remaining);
            ItemStack extracted = gInv.extractItem(i, take, false);
            if (!extracted.isEmpty()) {
                out.add(extracted);
                remaining -= extracted.getCount();
            }
        }
        return out;
    }

    private static int countMatching(EntityAutomaton bot, Predicate<ItemStack> matcher) {
        int count = 0;
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && matcher.test(s)) {
                count += s.getCount();
            }
        }
        return count;
    }

    private static List<EntityAutomaton> findNearbyAllies(AutomatonAIContext ctx, EntityAutomaton bot) {
        List<EntityAutomaton> allies = new ArrayList<>();
        for (EntityLivingBase e : ctx.getNearbyLivingEntities(ALLY_SCAN_RADIUS)) {
            if (e instanceof EntityAutomaton ally
                    && ally != bot
                    && ally.isEntityAlive()
                    && !ally.isDowned()
                    && bot.isAlliedWith(ally)) {
                allies.add(ally);
            }
        }
        return allies;
    }

    private interface SupplyRule {
        boolean canSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver);

        List<ItemStack> extractSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver);
    }

    private static final class WorstWeaponRule implements SupplyRule {
        @Override
        public boolean canSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver) {
            return collectWeapons(giver).size() >= 2 && collectWeapons(receiver).isEmpty();
        }

        @Override
        public List<ItemStack> extractSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver) {
            List<WeaponEntry> weapons = collectWeapons(giver);
            if (weapons.size() < 2) {
                return Collections.emptyList();
            }
            weapons.sort(Comparator.comparingDouble(w -> w.score));
            ItemStack extracted = extractEntry(giver, weapons.get(0).slotIndex);
            return extracted.isEmpty() ? Collections.emptyList() : List.of(extracted);
        }
    }

    private static final class FoodShareRule implements SupplyRule {
        private static final Predicate<ItemStack> HEALTHY_FOOD = s -> AutomatonInventory.scoreFood(s) > 0.0F;

        @Override
        public boolean canSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver) {
            int giverFood = countMatching(giver, HEALTHY_FOOD);
            if (giverFood < MIN_FOOD_TO_SHARE) {
                return false;
            }
            int receiverFood = countMatching(receiver, HEALTHY_FOOD);
            return receiverFood == 0
                    || (receiver.getHunger() <= ALLY_LOW_HUNGER_THRESHOLD && receiverFood < ALLY_LOW_FOOD_THRESHOLD);
        }

        @Override
        public List<ItemStack> extractSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver) {
            int giverFood = countMatching(giver, HEALTHY_FOOD);
            int shareCount = computeFoodShareAmount(giverFood);
            return shareCount > 0 ? extractMatchingItems(giver, HEALTHY_FOOD, shareCount) : Collections.emptyList();
        }
    }

    private static final class AmmoShareRule implements SupplyRule {
        @Override
        public boolean canSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver) {
            boolean receiverHasBow = EquipmentSelector.isBowItem(receiver.getHeldItemMainhand())
                    && !SpartanWeaponryCompat.isCrossbow(receiver.getHeldItemMainhand());
            return receiverHasBow
                    && countMatching(giver, SquadSupplyService::isSafeArrow) > 16
                    && countMatching(receiver, SquadSupplyService::isSafeArrow) < MIN_ARROWS_TO_KEEP;
        }

        @Override
        public List<ItemStack> extractSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver) {
            int giverArrows = countMatching(giver, SquadSupplyService::isSafeArrow);
            int shareCount = computeAmmoShareAmount(giverArrows);
            return shareCount > 0
                    ? extractMatchingItems(giver, SquadSupplyService::isSafeArrow, shareCount)
                    : Collections.emptyList();
        }
    }

    private static final class InventorySurplusRule implements SupplyRule {
        @Override
        public boolean canSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver) {
            if (ctx.getTaskConfig().getSavingChest() != null) {
                return false;
            }
            return LogisticsService.getInventoryOccupancyRatio(giver.getInventory().getMainInventory()) >= FULL_SHARE_OCCUPANCY
                    && LogisticsService.getInventoryOccupancyRatio(receiver.getInventory().getMainInventory()) <= RECEIVER_MAX_OCCUPANCY;
        }

        @Override
        public List<ItemStack> extractSupply(IAutomatonContext ctx, EntityAutomaton giver, EntityAutomaton receiver) {
            List<ItemStack> out = new ArrayList<>();
            IItemHandlerModifiable main = giver.getInventory().getMainInventory();
            AutomatonTaskConfig cfg = ctx.getTaskConfig();
            boolean keptFood = false;
            boolean keptBlock = false;
            for (int i = 0; i < main.getSlots(); i++) {
                ItemStack stack = main.getStackInSlot(i);
                if (stack.isEmpty()) {
                    continue;
                }
                boolean isFood = stack.getItem() instanceof ItemFood;
                boolean isBlock = stack.getItem() instanceof ItemBlock;
                boolean keep = InventoryManagerService.shouldKeepItem(
                        stack,
                        cfg.getJobId(),
                        cfg.isAllowScaffolding(),
                        isFood && !keptFood,
                        isBlock && !keptBlock)
                        || scoreWeapon(stack) > 0.0D
                        || stack.getItem() == Items.WATER_BUCKET
                        || stack.getItem() instanceof ItemArrow;
                if (isFood && !keptFood) {
                    keptFood = true;
                }
                if (isBlock && !keptBlock) {
                    keptBlock = true;
                }
                if (keep) {
                    continue;
                }
                ItemStack extracted = main.extractItem(i, stack.getCount(), false);
                if (!extracted.isEmpty()) {
                    out.add(extracted);
                }
                if (out.size() >= 3 || LogisticsService.getInventoryOccupancyRatio(main) < FULL_SHARE_OCCUPANCY) {
                    break;
                }
            }
            return out;
        }
    }

    private record WeaponEntry(int slotIndex, double score) {}
}
