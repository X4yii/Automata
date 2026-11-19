package com.x4yi.automata.item;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.jobs.CombatJobSupport;
import com.x4yi.automata.entity.EntityAutomaton;

public class ItemAutomaDevStaff extends Item {

    public static final double DEV_RAYCAST_RANGE = 20.0D;
    private static final String TAG_TEMPLATE_NBT = "DevTemplateBot";
    private static final String TAG_CLONE_COUNTER = "DevCloneCounter";
    private static final String TAG_BOUND_BOT_UUID = "DevBoundBotUuid";
    private static final Pattern SUFFIX_PATTERN = Pattern.compile("^(.*)\\s+\\((\\d+)\\)$");

    public ItemAutomaDevStaff() {
        setRegistryName(AutomataMod.MOD_ID, "automa_dev_staff");
        setTranslationKey(AutomataMod.MOD_ID + ".automa_dev_staff");
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.TOOLS);
        setFull3D();
    }

    @Override
    public boolean hasEffect(ItemStack stack) {
        return true;
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, EntityPlayer player, Entity entity) {
        if (player.world.isRemote) {
            return true;
        }
        return true;
    }

    @Override
    public boolean itemInteractionForEntity(
            ItemStack stack,
            EntityPlayer playerIn,
            EntityLivingBase target,
            EnumHand hand) {
        if (playerIn.world.isRemote) {
            return true;
        }
        return true;
    }

    public static void handleDevActionOnEntity(
            EntityPlayer player,
            ItemStack stack,
            EntityLivingBase target,
            @Nullable DevStaffMode mode) {
        if (!(player.world instanceof WorldServer world)) {
            return;
        }
        DevStaffMode active = mode != null ? mode : DevStaffMode.DUPLICATE_BOT;
        switch (active) {
            case DUPLICATE_BOT -> {
                if (target instanceof EntityAutomaton bot) {
                    saveBotTemplate(stack, bot);
                    spawnDuplicateAtPlayer(player, stack, world);
                }
            }
            case REVIVE_BOTS -> reviveInAoe(player, world, target.getPosition());
            case FORCE_DOWNED -> {
                if (target instanceof EntityAutomaton bot) {
                    forceDowned(player, bot);
                }
            }
            case FORCE_TARGET -> handleForceTargetEntity(player, stack, world, target);
            case SQUAD_RALLY -> rallyOwnedBots(player, world, target.getPosition());
            case HEAL_AND_FEED -> {
                if (target instanceof EntityAutomaton bot) {
                    restoreBot(bot);
                    player.sendStatusMessage(new TextComponentString("Restored " + bot.getName()), true);
                } else {
                    healInAoe(player, world, target.getPosition());
                }
            }
            case RESET_AI -> {
                if (target instanceof EntityAutomaton bot) {
                    resetBotAi(player, bot);
                }
            }
        }
    }

    public static void handleDevActionOnPoint(
            EntityPlayer player,
            ItemStack stack,
            WorldServer world,
            BlockPos pos,
            @Nullable DevStaffMode mode) {
        DevStaffMode active = mode != null ? mode : DevStaffMode.REVIVE_BOTS;
        switch (active) {
            case DUPLICATE_BOT -> {
                if (getOrCreateTag(stack).hasKey(TAG_TEMPLATE_NBT)) {
                    spawnDuplicateAtPlayer(player, stack, world);
                } else {
                    player.sendStatusMessage(new TextComponentString("Click a bot first to copy template"), true);
                }
            }
            case REVIVE_BOTS -> reviveInAoe(player, world, pos);
            case SQUAD_RALLY -> rallyOwnedBots(player, world, pos);
            case HEAL_AND_FEED -> healInAoe(player, world, pos);
            case FORCE_DOWNED, FORCE_TARGET, RESET_AI ->
                    player.sendStatusMessage(new TextComponentString("Aim at an entity for " + active.getDisplayName()), true);
        }
    }

    private static void saveBotTemplate(ItemStack stack, EntityAutomaton bot) {
        NBTTagCompound tag = getOrCreateTag(stack);
        NBTTagCompound botNbt = new NBTTagCompound();
        bot.writeToNBT(botNbt);
        botNbt.removeTag("UUIDMost");
        botNbt.removeTag("UUIDLeast");
        tag.setTag(TAG_TEMPLATE_NBT, botNbt);
        tag.setInteger(TAG_CLONE_COUNTER, 0);
    }

    private static void spawnDuplicateAtPlayer(EntityPlayer player, ItemStack stack, WorldServer world) {
        NBTTagCompound tag = getOrCreateTag(stack);
        if (!tag.hasKey(TAG_TEMPLATE_NBT)) {
            return;
        }
        NBTTagCompound template = tag.getCompoundTag(TAG_TEMPLATE_NBT).copy();
        int nextIndex = tag.getInteger(TAG_CLONE_COUNTER) + 1;
        tag.setInteger(TAG_CLONE_COUNTER, nextIndex);

        EntityAutomaton clone = new EntityAutomaton(world);
        clone.readFromNBT(template);
        clone.setUniqueId(UUID.randomUUID());
        clone.setPosition(player.posX, player.posY, player.posZ);
        clone.motionX = 0.0D;
        clone.motionY = 0.0D;
        clone.motionZ = 0.0D;
        clone.fallDistance = 0.0F;
        clone.setHealth(clone.getMaxHealth());

        String baseName = clone.hasCustomName() ? clone.getCustomNameTag() : clone.getName();
        String newName = computeIncrementedBotName(baseName, nextIndex);
        clone.setCustomNameTag(newName);

        world.spawnEntity(clone);
        player.sendStatusMessage(new TextComponentString("Duplicated bot -> " + newName), true);
    }

    public static String computeIncrementedBotName(String originalName, int index) {
        String safe = (originalName == null || originalName.trim().isEmpty()) ? "Automaton" : originalName.trim();
        Matcher matcher = SUFFIX_PATTERN.matcher(safe);
        String base = matcher.matches() ? matcher.group(1).trim() : safe;
        return base + " (" + Math.max(1, index) + ")";
    }

    public static AxisAlignedBB computeFiveByFiveAoe(BlockPos center) {
        return new AxisAlignedBB(
                center.getX() - 2.0D,
                center.getY() - 2.0D,
                center.getZ() - 2.0D,
                center.getX() + 3.0D,
                center.getY() + 3.0D,
                center.getZ() + 3.0D);
    }

    @Nullable
    public static BlockPos computeAimPoint(EntityPlayer player, double maxRange) {
        Vec3d eyes = player.getPositionEyes(1.0F);
        Vec3d look = player.getLook(1.0F);
        Vec3d end = eyes.add(look.x * maxRange, look.y * maxRange, look.z * maxRange);
        RayTraceResult hit = player.world.rayTraceBlocks(eyes, end, false, true, false);
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return new BlockPos(end.x, end.y, end.z);
    }

    private static void reviveInAoe(EntityPlayer player, WorldServer world, BlockPos center) {
        AxisAlignedBB box = computeFiveByFiveAoe(center);
        List<EntityAutomaton> bots = world.getEntitiesWithinAABB(EntityAutomaton.class, box);
        int revived = 0;
        for (EntityAutomaton bot : bots) {
            if (!bot.isEntityAlive() || !bot.isDowned()) {
                continue;
            }
            bot.revive(1.0F);
            bot.setHunger(EntityAutomaton.MAX_HUNGER);
            bot.setSaturation(5.0F);
            AutomatonAIContext ctx = bot.getBrain().getContext();
            ctx.getBlackboard().clearDowned(bot.getEntityId());
            ctx.getBlackboard().clearReviveInProgress(bot.getEntityId());
            revived++;
        }
        player.sendStatusMessage(
                new TextComponentString(revived > 0
                        ? "Revived " + revived + " bot(s) in 5x5 AoE"
                        : "No downed bots in 5x5 AoE"),
                true);
    }

    private static void forceDowned(EntityPlayer player, EntityAutomaton bot) {
        if (bot.isDowned()) {
            player.sendStatusMessage(new TextComponentString(bot.getName() + " is already downed"), true);
            return;
        }
        bot.enterDownedState();
        if (bot.getOwnerUuid() != null) {
            bot.getBrain().getContext().getBlackboard()
                    .reportDowned(bot.getEntityId(), bot.getPosition(), bot.world.getTotalWorldTime());
        }
        player.sendStatusMessage(new TextComponentString("Forced downed: " + bot.getName()), true);
    }

    private static void handleForceTargetEntity(
            EntityPlayer player,
            ItemStack stack,
            WorldServer world,
            EntityLivingBase target) {
        NBTTagCompound tag = getOrCreateTag(stack);
        if (target instanceof EntityAutomaton bot) {
            tag.setUniqueId(TAG_BOUND_BOT_UUID, bot.getUniqueID());
            player.sendStatusMessage(new TextComponentString("Force Target bound: " + bot.getName()), true);
            return;
        }
        if (!tag.hasUniqueId(TAG_BOUND_BOT_UUID)) {
            player.sendStatusMessage(new TextComponentString("Click a bot first to bind Force Target"), true);
            return;
        }
        UUID botUuid = tag.getUniqueId(TAG_BOUND_BOT_UUID);
        EntityAutomaton bound = null;
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityAutomaton candidate && botUuid.equals(candidate.getUniqueID())) {
                bound = candidate;
                break;
            }
        }
        if (bound == null || !bound.isEntityAlive() || bound.isDowned()) {
            player.sendStatusMessage(new TextComponentString("Bound bot is unavailable"), true);
            return;
        }
        bound.setAttackTarget(target);
        CombatJobSupport.engageTarget(bound.getBrain().getContext(), target, true);
        player.sendStatusMessage(
                new TextComponentString(bound.getName() + " -> targeting " + target.getName()),
                true);
    }

    private static void rallyOwnedBots(EntityPlayer player, WorldServer world, BlockPos center) {
        AxisAlignedBB scan = new AxisAlignedBB(player.getPosition()).grow(96.0D);
        List<EntityAutomaton> owned = new ArrayList<>();
        for (EntityAutomaton bot : world.getEntitiesWithinAABB(EntityAutomaton.class, scan)) {
            if (bot.isEntityAlive() && !bot.isDowned() && bot.isOwnedBy(player)) {
                owned.add(bot);
            }
        }
        if (owned.isEmpty()) {
            player.sendStatusMessage(new TextComponentString("No owned bots within 96m"), true);
            return;
        }
        int count = owned.size();
        double radius = count <= 1 ? 0.0D : Math.min(3.5D, 1.5D + count * 0.25D);
        for (int i = 0; i < count; i++) {
            EntityAutomaton bot = owned.get(i);
            double angle = (Math.PI * 2.0D * i) / count;
            double tx = center.getX() + 0.5D + Math.cos(angle) * radius;
            double ty = center.getY() + 1.0D;
            double tz = center.getZ() + 0.5D + Math.sin(angle) * radius;
            bot.getBrain().nudgeUnstick();
            bot.setPositionAndUpdate(tx, ty, tz);
            bot.fallDistance = 0.0F;
        }
        player.sendStatusMessage(new TextComponentString("Rallied " + count + " bot(s)"), true);
    }

    private static void healInAoe(EntityPlayer player, WorldServer world, BlockPos center) {
        AxisAlignedBB box = computeFiveByFiveAoe(center);
        int count = 0;
        for (EntityAutomaton bot : world.getEntitiesWithinAABB(EntityAutomaton.class, box)) {
            if (bot.isEntityAlive() && !bot.isDowned()) {
                restoreBot(bot);
                count++;
            }
        }
        player.sendStatusMessage(new TextComponentString("Healed & fed " + count + " bot(s)"), true);
    }

    private static void restoreBot(EntityAutomaton bot) {
        bot.setHealth(bot.getMaxHealth());
        bot.setHunger(EntityAutomaton.MAX_HUNGER);
        bot.setSaturation(5.0F);
        List<net.minecraft.potion.Potion> badEffects = new ArrayList<>();
        for (PotionEffect effect : bot.getActivePotionEffects()) {
            if (effect.getPotion().isBadEffect()) {
                badEffects.add(effect.getPotion());
            }
        }
        for (net.minecraft.potion.Potion potion : badEffects) {
            bot.removePotionEffect(potion);
        }
    }

    private static void resetBotAi(EntityPlayer player, EntityAutomaton bot) {
        AutomatonAIContext ctx = bot.getBrain().getContext();
        int prevTarget = ctx.getCombatTacticsState().activeTargetId;
        if (prevTarget >= 0) {
            ctx.getBlackboard().releaseTarget(bot.getEntityId(), prevTarget);
            ctx.getBlackboard().releaseVengeanceSlam(bot.getEntityId(), prevTarget);
        }
        ctx.getCombatTacticsState().activeTargetId = -1;
        bot.setAttackTarget(null);
        bot.setRevengeTarget(null);
        bot.getBrain().nudgeUnstick();
        player.sendStatusMessage(new TextComponentString("Reset AI for " + bot.getName()), true);
    }

    private static NBTTagCompound getOrCreateTag(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        return tag;
    }
}
