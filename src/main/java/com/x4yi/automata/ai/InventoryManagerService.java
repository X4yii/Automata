package com.x4yi.automata.ai;

import java.util.List;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemArrow;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemShield;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;

import com.x4yi.automata.ai.jobs.JobBlockSupport;
import com.x4yi.automata.entity.EntityAutomaton;

public final class InventoryManagerService {

    private static final float BACKPACK_OFFLOAD_THRESHOLD = 0.75F;
    private static final double DROP_SEEK_RADIUS = 10.0D;
    private static final EntityEquipmentSlot[] ARMOR_SLOTS = {
            EntityEquipmentSlot.FEET,
            EntityEquipmentSlot.LEGS,
            EntityEquipmentSlot.CHEST,
            EntityEquipmentSlot.HEAD
    };

    private InventoryManagerService() {}

    public static void tickInventoryManagement(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        autoEquipBestArmor(bot);
        autoEquipOffhandUtility(bot);
        offloadToBackpackIfNeeded(bot, ctx.getTaskConfig());
    }

    public static void autoEquipOffhandUtility(EntityAutomaton bot) {
        if (!bot.getHeldItemOffhand().isEmpty()) {
            return;
        }
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        int shieldIdx = -1;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty()) {
                continue;
            }
            if (s.getItem() == Items.TOTEM_OF_UNDYING) {
                ItemStack extracted = inv.extractItem(i, 1, false);
                if (!extracted.isEmpty()) {
                    bot.setHeldItem(EnumHand.OFF_HAND, extracted);
                    return;
                }
            }
            if (shieldIdx < 0 && EquipmentSelector.isShieldItem(s)) {
                shieldIdx = i;
            }
        }
        if (shieldIdx >= 0) {
            ItemStack extracted = inv.extractItem(shieldIdx, 1, false);
            if (!extracted.isEmpty()) {
                bot.setHeldItem(EnumHand.OFF_HAND, extracted);
            }
        }
    }

    public static boolean trySeekNearbyUsefulDrop(AutomatonAIContext ctx) {
        EntityAutomaton bot = ctx.getAutomaton();
        AutomatonTaskConfig cfg = ctx.getTaskConfig();
        int nearbyEnemies = com.x4yi.automata.ai.jobs.ThreatFieldEvaluator.assessLocalThreats(ctx, bot).enemyCount();
        if (!isSafeToSeekLoot(
                cfg.isCanPickUpLoot(),
                cfg.isSearchLoot(),
                bot.isEating(),
                bot.isBurning() || bot.isInLava(),
                bot.getHealthRatio(),
                nearbyEnemies,
                bot.getRevengeTarget() != null,
                LogisticsService.isMostlyFull(bot.getInventory().getMainInventory()))) {
            return false;
        }
        List<EntityItem> drops = bot.world.getEntitiesWithinAABB(
                EntityItem.class,
                bot.getEntityBoundingBox().grow(DROP_SEEK_RADIUS, 4.0D, DROP_SEEK_RADIUS),
                item -> item != null && item.isEntityAlive() && !item.cannotPickup() && !item.getItem().isEmpty());
        EntityItem best = null;
        double bestDistSq = Double.MAX_VALUE;
        long now = ctx.getWorldTime();
        for (EntityItem item : drops) {
            if (!isUsefulDrop(bot, ctx.getTaskConfig(), item.getItem())) {
                continue;
            }
            BlockPos pos = item.getPosition();
            if (ctx.isGoalOnCooldown(pos, now)) {
                continue;
            }
            double dSq = bot.getDistanceSq(item);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = item;
            }
        }
        if (best == null) {
            return false;
        }
        ctx.requestPathTo(best.getPosition(), false);
        return true;
    }

    private static boolean isUsefulDrop(EntityAutomaton bot, AutomatonTaskConfig cfg, ItemStack stack) {
        if (stack.getItem() instanceof ItemArmor armor) {
            ItemStack equipped = bot.getItemStackFromSlot(armor.armorType);
            return EquipmentSelector.getArmorScore(stack) > EquipmentSelector.getArmorScore(equipped) + 0.01F;
        }
        if (stack.getItem() instanceof ItemTool
                || stack.getItem() instanceof ItemSword
                || EquipmentSelector.isBowItem(stack)
                || EquipmentSelector.isShieldItem(stack)
                || stack.getItem() instanceof ItemArrow
                || stack.getItem() instanceof ItemEnderPearl
                || stack.getItem() == Items.TOTEM_OF_UNDYING) {
            return true;
        }
        if (com.x4yi.automata.api.AutomatonBuiltInJobs.FARMER.equals(cfg.getJobId())
                && (stack.getItem() instanceof IPlantable
                        || (stack.getItem() == Items.DYE && stack.getMetadata() == 15))) {
            return true;
        }
        if (com.x4yi.automata.api.AutomatonBuiltInJobs.MINE.equals(cfg.getJobId()) && isMineralLoot(stack)) {
            return true;
        }
        if (stack.getItem() instanceof ItemFood) {
            return bot.getHunger() < 16.0F;
        }
        return cfg.isAllowScaffolding() && stack.getItem() instanceof ItemBlock;
    }

    static boolean isSafeToSeekLoot(
            boolean canPickUpLoot,
            boolean searchLoot,
            boolean eating,
            boolean burningOrLava,
            float healthRatio,
            int nearbyEnemies,
            boolean hasRevengeTarget,
            boolean inventoryMostlyFull) {
        if (!canPickUpLoot || !searchLoot || eating || burningOrLava || inventoryMostlyFull) {
            return false;
        }
        return healthRatio >= 0.55F && nearbyEnemies == 0 && !hasRevengeTarget;
    }

    private static boolean isMineralLoot(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() == Items.COAL
                || stack.getItem() == Items.DIAMOND
                || stack.getItem() == Items.EMERALD
                || stack.getItem() == Items.REDSTONE
                || stack.getItem() == Items.QUARTZ
                || stack.getItem() == Items.GOLD_NUGGET
                || stack.getItem() == Items.IRON_NUGGET
                || (stack.getItem() == Items.DYE && stack.getMetadata() == 4)) {
            return true;
        }
        if (stack.getItem() instanceof ItemBlock itemBlock && itemBlock.getBlock().getRegistryName() != null) {
            if (com.x4yi.automata.ai.jobs.ModdedOreCatalog.isKnownModdedOreId(
                    itemBlock.getBlock().getRegistryName().toString())) {
                return true;
            }
        }
        for (int oreId : net.minecraftforge.oredict.OreDictionary.getOreIDs(stack)) {
            String name = net.minecraftforge.oredict.OreDictionary.getOreName(oreId);
            if (name != null && (name.startsWith("ore")
                    || name.startsWith("gem")
                    || name.startsWith("dust")
                    || name.startsWith("ingot")
                    || name.startsWith("nugget"))) {
                return true;
            }
        }
        return false;
    }

    public static void autoEquipBestArmor(EntityAutomaton bot) {
        IItemHandlerModifiable inv = bot.getInventory().getCombinedInventory();
        for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack current = bot.getItemStackFromSlot(slot);
            float bestScore = EquipmentSelector.getArmorScore(current);
            int bestIdx = -1;

            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack candidate = inv.getStackInSlot(i);
                if (candidate.isEmpty() || !(candidate.getItem() instanceof ItemArmor armor)) {
                    continue;
                }
                if (armor.armorType != slot) {
                    continue;
                }
                float score = EquipmentSelector.getArmorScore(candidate);
                if (score > bestScore + 0.01F) {
                    bestScore = score;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0) {
                ItemStack chosen = inv.extractItem(bestIdx, 1, false);
                if (!chosen.isEmpty()) {
                    bot.setItemStackToSlot(slot, chosen);
                    if (!current.isEmpty()) {
                        ItemStack leftover = inv.insertItem(bestIdx, current.copy(), false);
                        if (!leftover.isEmpty()) {
                            leftover = ItemHandlerHelper.insertItemStacked(inv, leftover, false);
                            if (!leftover.isEmpty()) {
                                bot.entityDropItem(leftover, 0.0F);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void offloadToBackpackIfNeeded(EntityAutomaton bot, AutomatonTaskConfig cfg) {
        IItemHandlerModifiable backpack = bot.getInventory().getBackpackHandler();
        if (backpack == null) {
            return;
        }
        IItemHandlerModifiable main = bot.getInventory().getMainInventory();
        if (LogisticsService.getInventoryOccupancyRatio(main) < BACKPACK_OFFLOAD_THRESHOLD) {
            return;
        }
        boolean keptFood = false;
        boolean keptBlock = false;
        for (int i = 0; i < main.getSlots(); i++) {
            ItemStack stack = main.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            boolean isFood = stack.getItem() instanceof ItemFood;
            boolean isBlock = stack.getItem() instanceof ItemBlock;
            boolean keep = shouldKeepItem(
                    stack,
                    cfg.getJobType(),
                    cfg.isAllowScaffolding(),
                    isFood && !keptFood,
                    isBlock && !keptBlock);
            if (isFood && !keptFood) {
                keptFood = true;
            }
            if (isBlock && !keptBlock) {
                keptBlock = true;
            }
            if (keep) {
                continue;
            }
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(backpack, stack.copy(), false);
            int moved = stack.getCount() - remainder.getCount();
            if (moved > 0) {
                main.extractItem(i, moved, false);
            }
        }
    }

    public static boolean shouldKeepItem(
            ItemStack stack,
            AutomatonJobType jobType,
            boolean allowScaffolding,
            boolean isFirstFoodStack,
            boolean isFirstScaffoldStack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (isFirstFoodStack && stack.getItem() instanceof ItemFood) {
            return true;
        }
        if (stack.getItem() instanceof ItemTool || stack.getItem() instanceof ItemSword || EquipmentSelector.isShieldItem(stack)) {
            return true;
        }
        if (jobType == AutomatonJobType.FARMER) {
            if (stack.getItem() instanceof ItemHoe || stack.getItem() instanceof IPlantable) {
                return true;
            }
        }
        if (jobType == AutomatonJobType.LUMBERJACK && JobBlockSupport.matchesOreDict(stack, "treeSapling")) {
            return true;
        }
        return (jobType == AutomatonJobType.EXCAVATION || jobType == AutomatonJobType.MINE)
                && allowScaffolding
                && isFirstScaffoldStack
                && stack.getItem() instanceof ItemBlock;
    }
}

