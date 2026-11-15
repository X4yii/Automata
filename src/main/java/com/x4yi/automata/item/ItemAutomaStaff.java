package com.x4yi.automata.item;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.ai.LogisticsService;
import com.x4yi.automata.ai.jobs.MineJob;
import com.x4yi.automata.ai.jobs.ModdedOreCatalog;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.entity.EntityAutomaton;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public class ItemAutomaStaff extends Item {

    private static final String TAG_POS1 = "Pos1";
    private static final String TAG_POS2 = "Pos2";
    private static final String TAG_CHEST_POS = "ChestPos";
    private static final String TAG_BOUND_UUID = "BoundAutomatonUuid";
    private static final String TAG_MINER_TARGET_MODE = "MinerTargetMode";

    public ItemAutomaStaff() {
        setRegistryName(AutomataMod.MOD_ID, "automa_staff");
        setTranslationKey(AutomataMod.MOD_ID + ".automa_staff");
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.TOOLS);
        setFull3D();
    }

    @Override
    public EnumActionResult onItemUse(
            EntityPlayer player,
            World worldIn,
            BlockPos pos,
            EnumHand hand,
            EnumFacing facing,
            float hitX,
            float hitY,
            float hitZ) {
        if (worldIn.isRemote) {
            return EnumActionResult.SUCCESS;
        }
        ItemStack stack = player.getHeldItem(hand);
        NBTTagCompound tag = getOrCreateTag(stack);
        EntityAutomaton bound = resolveBoundAutomaton(worldIn, stack);
        if (player.isSneaking()) {
            tag.setLong(TAG_POS2, pos.toLong());
            if (bound != null && canControl(player, bound)) {
                if (MineJob.rejectAreaIfMiner(player, bound)) {
                    return EnumActionResult.SUCCESS;
                }
                bound.getTaskConfig().setPos2(pos);
            }
            player.sendStatusMessage(
                    new TextComponentTranslation("message.automata.pos2_set", pos.getX(), pos.getY(), pos.getZ()),
                    true);
        } else {
            tag.setLong(TAG_POS1, pos.toLong());
            if (bound != null && canControl(player, bound)) {
                if (MineJob.rejectAreaIfMiner(player, bound)) {
                    return EnumActionResult.SUCCESS;
                }
                bound.getTaskConfig().setPos1(pos);
            }
            player.sendStatusMessage(
                    new TextComponentTranslation("message.automata.pos1_set", pos.getX(), pos.getY(), pos.getZ()),
                    true);
        }
        return EnumActionResult.SUCCESS;
    }

    @Override
    public boolean itemInteractionForEntity(
            ItemStack stack,
            EntityPlayer playerIn,
            EntityLivingBase target,
            EnumHand hand) {
        if (!(target instanceof EntityAutomaton bot)) {
            return false;
        }
        if (playerIn.world.isRemote) {
            return true;
        }
        if (!canControl(playerIn, bot)) {
            return false;
        }
        if (playerIn.isSneaking()) {
            bindAutomaton(stack, bot);
            BlockPos p1 = getPos1(stack);
            BlockPos p2 = getPos2(stack);
            if (p1 != null || p2 != null) {
                if (MineJob.rejectAreaIfMiner(playerIn, bot)) {
                    return true;
                }
                if (p1 != null) {
                    bot.getTaskConfig().setPos1(p1);
                }
                if (p2 != null) {
                    bot.getTaskConfig().setPos2(p2);
                }
                playerIn.sendStatusMessage(new TextComponentTranslation("message.automata.region_applied"), true);
            } else {
                playerIn.sendStatusMessage(
                        new TextComponentTranslation("message.automata.staff_bound", bot.getDisplayName()),
                        true);
            }
            return true;
        }
        BlockPos p1 = getPos1(stack);
        BlockPos p2 = getPos2(stack);
        if (p1 == null && p2 == null) {
            playerIn.sendStatusMessage(new TextComponentTranslation("message.automata.no_region_marked"), true);
            return true;
        }
        if (MineJob.rejectAreaIfMiner(playerIn, bot)) {
            return true;
        }
        if (p1 != null) {
            bot.getTaskConfig().setPos1(p1);
        }
        if (p2 != null) {
            bot.getTaskConfig().setPos2(p2);
        }
        playerIn.sendStatusMessage(new TextComponentTranslation("message.automata.region_applied"), true);
        return true;
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, EntityPlayer player, Entity entity) {
        if (!(entity instanceof EntityAutomaton bot)) {
            return false;
        }
        if (player.world.isRemote) {
            return true;
        }
        return handleRemoteLeftClickEntity(player, stack, bot, false, player.isSneaking());
    }

    public static boolean handleRemoteLeftClickEntity(
            EntityPlayer player,
            ItemStack stack,
            EntityAutomaton bot,
            boolean ctrlPressed,
            boolean sneakPressed) {
        return handleRemoteLeftClickEntity(player, stack, bot, ctrlPressed, sneakPressed, null);
    }

    public static boolean handleRemoteLeftClickEntity(
            EntityPlayer player,
            ItemStack stack,
            EntityAutomaton bot,
            boolean ctrlPressed,
            boolean sneakPressed,
            @Nullable AutomaStaffMode mode) {
        if (!canControl(player, bot)) {
            return true;
        }
        if (mode == AutomaStaffMode.TOGGLE_FOLLOW) {
            if (ctrlPressed && AutomatonBuiltInJobs.MINE.equals(bot.getTaskConfig().getJobId())) {
                // Allow MineJob vein bind if player specifically wants Follow/Stay, fall through to toggle below.
            }
            com.x4yi.automata.ai.TaskCompletionBehavior current = bot.getTaskConfig().getCompletionBehavior();
            com.x4yi.automata.ai.TaskCompletionBehavior next =
                    current == com.x4yi.automata.ai.TaskCompletionBehavior.FOLLOW_OWNER
                            ? com.x4yi.automata.ai.TaskCompletionBehavior.STAY_PUT
                            : com.x4yi.automata.ai.TaskCompletionBehavior.FOLLOW_OWNER;
            bot.getTaskConfig().setCompletionBehavior(next);
            String key = next == com.x4yi.automata.ai.TaskCompletionBehavior.FOLLOW_OWNER
                    ? "message.automata.follow_enabled"
                    : "message.automata.stay_enabled";
            player.sendStatusMessage(new TextComponentTranslation(key, bot.getDisplayName()), true);
            return true;
        }
        if (mode == AutomaStaffMode.SELECT_ZONE) {
            bindAutomaton(stack, bot);
            NBTTagCompound tag = getOrCreateTag(stack);
            if (tag.hasKey(TAG_POS1) && tag.hasKey(TAG_POS2) && !MineJob.rejectAreaIfMiner(player, bot)) {
                bot.getTaskConfig().setPos1(BlockPos.fromLong(tag.getLong(TAG_POS1)));
                bot.getTaskConfig().setPos2(BlockPos.fromLong(tag.getLong(TAG_POS2)));
                player.sendStatusMessage(new TextComponentTranslation("message.automata.region_applied"), true);
            } else {
                player.sendStatusMessage(new TextComponentTranslation("message.automata.staff_bound", bot.getDisplayName()), true);
            }
            return true;
        }
        if (mode == AutomaStaffMode.ASSIGN_CHEST || (mode == null && sneakPressed)) {
            bindAutomaton(stack, bot);
            getOrCreateTag(stack).setBoolean(TAG_MINER_TARGET_MODE, false);
            BlockPos chest = getChestPos(stack);
            if (chest != null) {
                bot.getTaskConfig().setDepositChest(chest);
                player.sendStatusMessage(
                        new TextComponentTranslation(
                                "message.automata.chest_assigned",
                                chest.getX(),
                                chest.getY(),
                                chest.getZ()),
                        true);
            } else {
                player.sendStatusMessage(
                        new TextComponentTranslation("message.automata.staff_chest_select_bot", bot.getDisplayName()),
                        true);
            }
            return true;
        }
        if (mode == null && ctrlPressed && AutomatonBuiltInJobs.MINE.equals(bot.getTaskConfig().getJobId())) {
            bindAutomaton(stack, bot);
            getOrCreateTag(stack).setBoolean(TAG_MINER_TARGET_MODE, true);
            player.sendStatusMessage(
                    new TextComponentString("Minero vinculado: haz click izquierdo sobre una veta de minerales"),
                    true);
            return true;
        }
        // WHIP (or fallback)
        bot.getBrain().nudgeUnstick();
        bot.world.playSound(
                null,
                bot.posX,
                bot.posY + 0.8D,
                bot.posZ,
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
                SoundCategory.PLAYERS,
                0.55F,
                1.35F);
        if (bot.world instanceof WorldServer worldServer) {
            worldServer.spawnParticle(
                    EnumParticleTypes.CRIT,
                    bot.posX,
                    bot.posY + bot.height * 0.6D,
                    bot.posZ,
                    6,
                    0.25D,
                    0.25D,
                    0.25D,
                    0.05D);
        }
        player.sendStatusMessage(new TextComponentTranslation("message.automata.staff_nudge"), true);
        return true;
    }

    public static void handleRemoteLeftClickBlock(
            EntityPlayer player,
            ItemStack stack,
            WorldServer world,
            BlockPos pos,
            boolean sneakPressed) {
        handleRemoteLeftClickBlock(player, stack, world, pos, sneakPressed, null);
    }

    public static void handleRemoteLeftClickBlock(
            EntityPlayer player,
            ItemStack stack,
            WorldServer world,
            BlockPos pos,
            boolean sneakPressed,
            @Nullable AutomaStaffMode mode) {
        if (isMinerTargetMode(stack) && ModdedOreCatalog.isOreBlock(world, pos, world.getBlockState(pos))) {
            EntityAutomaton bound = resolveBoundAutomaton(world, stack);
            if (bound != null && canControl(player, bound)
                    && AutomatonBuiltInJobs.MINE.equals(bound.getTaskConfig().getJobId())) {
                IAutomatonJob job = bound.getBrain().getActiveJob();
                if (job instanceof MineJob mineJob) {
                    int veinSize = mineJob.commandVein(world, pos);
                    getOrCreateTag(stack).setBoolean(TAG_MINER_TARGET_MODE, false);
                    player.sendStatusMessage(
                            new TextComponentString("Minero enviado a picar la veta (" + veinSize + " bloques)"),
                            true);
                    return;
                }
            }
        }
        if (mode == AutomaStaffMode.SELECT_ZONE) {
            NBTTagCompound tag = getOrCreateTag(stack);
            EntityAutomaton bound = resolveBoundAutomaton(world, stack);
            if (sneakPressed) {
                tag.setLong(TAG_POS2, pos.toLong());
                if (bound != null && canControl(player, bound) && !MineJob.rejectAreaIfMiner(player, bound)) {
                    bound.getTaskConfig().setPos2(pos);
                }
                player.sendStatusMessage(
                        new TextComponentTranslation("message.automata.pos2_set", pos.getX(), pos.getY(), pos.getZ()),
                        true);
            } else {
                tag.setLong(TAG_POS1, pos.toLong());
                if (bound != null && canControl(player, bound) && !MineJob.rejectAreaIfMiner(player, bound)) {
                    bound.getTaskConfig().setPos1(pos);
                }
                player.sendStatusMessage(
                        new TextComponentTranslation("message.automata.pos1_set", pos.getX(), pos.getY(), pos.getZ()),
                        true);
            }
            return;
        }
        if ((mode == AutomaStaffMode.ASSIGN_CHEST || (mode == null && sneakPressed))
                && LogisticsService.resolveItemHandler(world.getTileEntity(pos)) != null) {
            getOrCreateTag(stack).setLong(TAG_CHEST_POS, pos.toLong());
            EntityAutomaton bound = resolveBoundAutomaton(world, stack);
            if (bound != null && canControl(player, bound)) {
                bound.getTaskConfig().setDepositChest(pos);
                player.sendStatusMessage(
                        new TextComponentTranslation(
                                "message.automata.chest_assigned",
                                pos.getX(),
                                pos.getY(),
                                pos.getZ()),
                        true);
            } else {
                player.sendStatusMessage(
                        new TextComponentTranslation(
                                "message.automata.chest_selected",
                                pos.getX(),
                                pos.getY(),
                                pos.getZ()),
                        true);
            }
        }
    }

    public static boolean isMinerTargetMode(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.getBoolean(TAG_MINER_TARGET_MODE);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        if (player == null || !player.isSneaking()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemAutomaStaff)) {
            return;
        }
        World world = event.getWorld();
        BlockPos pos = event.getPos();
        if (LogisticsService.resolveItemHandler(world.getTileEntity(pos)) == null) {
            return;
        }
        event.setCanceled(true);
        if (world.isRemote) {
            return;
        }
        getOrCreateTag(stack).setLong(TAG_CHEST_POS, pos.toLong());
        EntityAutomaton bound = resolveBoundAutomaton(world, stack);
        if (bound != null && canControl(player, bound)) {
            bound.getTaskConfig().setDepositChest(pos);
            player.sendStatusMessage(
                    new TextComponentTranslation(
                            "message.automata.chest_assigned",
                            pos.getX(),
                            pos.getY(),
                            pos.getZ()),
                    true);
        } else {
            player.sendStatusMessage(
                    new TextComponentTranslation(
                            "message.automata.chest_selected",
                            pos.getX(),
                            pos.getY(),
                            pos.getZ()),
                    true);
        }
    }

    private static boolean canControl(EntityPlayer player, EntityAutomaton bot) {
        return bot.isOwnedBy(player) || player.canUseCommand(2, "");
    }

    private static void bindAutomaton(ItemStack stack, EntityAutomaton bot) {
        getOrCreateTag(stack).setString(TAG_BOUND_UUID, bot.getUniqueID().toString());
    }

    @Nullable
    private static EntityAutomaton resolveBoundAutomaton(World world, ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(TAG_BOUND_UUID, Constants.NBT.TAG_STRING)) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(tag.getString(TAG_BOUND_UUID));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityAutomaton bot && uuid.equals(bot.getUniqueID())) {
                return bot;
            }
        }
        return null;
    }

    private static NBTTagCompound getOrCreateTag(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        return tag;
    }

    @Nullable
    public static BlockPos getPos1(ItemStack stack) {
        return readTagPos(stack, TAG_POS1);
    }

    @Nullable
    public static BlockPos getPos2(ItemStack stack) {
        return readTagPos(stack, TAG_POS2);
    }

    @Nullable
    public static BlockPos getChestPos(ItemStack stack) {
        return readTagPos(stack, TAG_CHEST_POS);
    }

    @Nullable
    private static BlockPos readTagPos(ItemStack stack, String key) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null && tag.hasKey(key, Constants.NBT.TAG_LONG)) {
            return BlockPos.fromLong(tag.getLong(key));
        }
        return null;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.format("item.automata.automa_staff.tooltip"));
    }
}

