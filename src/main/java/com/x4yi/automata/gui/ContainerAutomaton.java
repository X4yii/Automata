/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.gui;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.x4yi.x4ui.common.network.IServerActionHandler;
import com.x4yi.x4ui.common.network.UINetwork;
import com.x4yi.x4ui.common.sync.NetworkSyncHelper;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.items.wrapper.EntityArmorInvWrapper;
import net.minecraftforge.items.wrapper.EntityHandsInvWrapper;

import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.JobDescriptor;
import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.ai.LogisticsService;
import com.x4yi.automata.ai.TaskCompletionBehavior;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Server-authoritative inventory and telemetry container for {@link EntityAutomaton},
 * integrated with X4UI's {@link IServerActionHandler} and {@link NetworkSyncHelper}.
 */
public class ContainerAutomaton extends Container implements IServerActionHandler {

    public static final int PROP_HEALTH_LOW = 0;
    public static final int PROP_HEALTH_HIGH = 1;
    public static final int PROP_MAX_HEALTH_LOW = 2;
    public static final int PROP_MAX_HEALTH_HIGH = 3;
    public static final int PROP_HUNGER_LOW = 4;
    public static final int PROP_HUNGER_HIGH = 5;
    public static final int PROP_SATURATION_LOW = 6;
    public static final int PROP_SATURATION_HIGH = 7;
    public static final int PROP_LOAD_LOW = 8;
    public static final int PROP_LOAD_HIGH = 9;
    public static final int PROP_JOB_ORDINAL = 10;
    public static final int PROP_DOWNED = 11;
    public static final int PROP_WHITELIST = 12;
    public static final int PROP_SQUAD_COOP = 13;
    public static final int PROP_TERRAIN_MOD = 14;
    public static final int PROP_SLIM = 15;
    public static final int PROP_PARKOUR = 16;
    public static final int PROP_SCAFFOLDING = 17;
    public static final int PROP_FILL_DEPRESSIONS = 18;
    public static final int PROP_RADIUS_LOW = 19;
    public static final int PROP_RADIUS_HIGH = 20;
    public static final int PROP_COMPLETION_ORDINAL = 21;

    private static final EntityEquipmentSlot[] ARMOR_ORDER = {
            EntityEquipmentSlot.HEAD,
            EntityEquipmentSlot.CHEST,
            EntityEquipmentSlot.LEGS,
            EntityEquipmentSlot.FEET
    };
    private static final int[] ARMOR_INDICES = { 3, 2, 1, 0 };

    private static final int BOT_SLOT_COUNT = 34;
    private static final int TOTAL_SLOT_COUNT = 70;

    private final EntityAutomaton bot;
    private final EntityPlayer player;

    public ContainerAutomaton(EntityAutomaton bot, EntityPlayer player) {
        this.bot = bot;
        this.player = player;

        IItemHandlerModifiable armor = new EntityArmorInvWrapper(bot);
        for (int i = 0; i < 4; i++) {
            final EntityEquipmentSlot eqSlot = ARMOR_ORDER[i];
            addSlotToContainer(new SlotItemHandler(armor, ARMOR_INDICES[i], -9999, -9999) {
                @Override
                public boolean isItemValid(@Nonnull ItemStack stack) {
                    return !stack.isEmpty() && stack.getItem().isValidArmor(stack, eqSlot, bot);
                }

                @Override
                public int getSlotStackLimit() {
                    return 1;
                }
            });
        }

        IItemHandlerModifiable hands = new EntityHandsInvWrapper(bot);
        addSlotToContainer(new SlotItemHandler(hands, 0, -9999, -9999));
        addSlotToContainer(new SlotItemHandler(hands, 1, -9999, -9999));

        addSlotToContainer(new SlotItemHandler(bot.getInventory().getBackpackSlot(), 0, -9999, -9999));

        IItemHandler mainInv = bot.getInventory().getMainInventory();
        for (int i = 0; i < 27; i++) {
            addSlotToContainer(new SlotItemHandler(mainInv, i, -9999, -9999));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(player.inventory, col + row * 9 + 9, -9999, -9999));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(player.inventory, col, -9999, -9999));
        }
    }

    public EntityAutomaton getBot() {
        return bot;
    }

    public EntityPlayer getPlayer() {
        return player;
    }

    @Override
    public boolean canInteractWith(@Nonnull EntityPlayer playerIn) {
        return !bot.isDead
                && bot.world == playerIn.world
                && (bot.isOwnedBy(playerIn) || playerIn.canUseCommand(2, ""));
    }

    private float cachedLoadRatio;
    private int syncTickCounter;

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        AutomatonTaskConfig cfg = bot.getTaskConfig();
        if (syncTickCounter++ % 5 == 0) {
            cachedLoadRatio = LogisticsService.getInventoryOccupancyRatio(bot.getInventory().getMainInventory());
        }
        float load = cachedLoadRatio;
        for (IContainerListener listener : listeners) {
            NetworkSyncHelper.sendFloat32Property(listener, this, PROP_HEALTH_LOW, PROP_HEALTH_HIGH, bot.getHealth());
            NetworkSyncHelper.sendFloat32Property(listener, this, PROP_MAX_HEALTH_LOW, PROP_MAX_HEALTH_HIGH, bot.getMaxHealth());
            NetworkSyncHelper.sendFloat32Property(listener, this, PROP_HUNGER_LOW, PROP_HUNGER_HIGH, bot.getHunger());
            NetworkSyncHelper.sendFloat32Property(listener, this, PROP_SATURATION_LOW, PROP_SATURATION_HIGH, bot.getSaturation());
            NetworkSyncHelper.sendFloat32Property(listener, this, PROP_LOAD_LOW, PROP_LOAD_HIGH, load);
            listener.sendWindowProperty(this, PROP_JOB_ORDINAL, Math.max(0, AutomataAPI.getJobRegistry().indexOf(cfg.getJobId())));
            listener.sendWindowProperty(this, PROP_DOWNED, bot.isDowned() ? 1 : 0);
            listener.sendWindowProperty(this, PROP_WHITELIST, cfg.isWhitelistMode() ? 1 : 0);
            listener.sendWindowProperty(this, PROP_SQUAD_COOP, cfg.isSquadCooperation() ? 1 : 0);
            listener.sendWindowProperty(this, PROP_TERRAIN_MOD, cfg.isAllowBreakThrough() ? 1 : 0);
            listener.sendWindowProperty(this, PROP_SLIM, bot.isSlimModel() ? 1 : 0);
            listener.sendWindowProperty(this, PROP_PARKOUR, cfg.isAllowParkour() ? 1 : 0);
            listener.sendWindowProperty(this, PROP_SCAFFOLDING, cfg.isAllowScaffolding() ? 1 : 0);
            listener.sendWindowProperty(this, PROP_FILL_DEPRESSIONS, cfg.isFillDepressions() ? 1 : 0);
            NetworkSyncHelper.sendFloat32Property(listener, this, PROP_RADIUS_LOW, PROP_RADIUS_HIGH, (float) cfg.getCombatRadius());
            listener.sendWindowProperty(this, PROP_COMPLETION_ORDINAL, cfg.getCompletionBehavior().ordinal());
        }
    }

    @Nonnull
    @Override
    public ItemStack transferStackInSlot(@Nonnull EntityPlayer playerIn, int index) {
        Slot slot = inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack current = slot.getStack();
        ItemStack copy = current.copy();
        if (index < BOT_SLOT_COUNT) {
            if (!mergeItemStack(current, BOT_SLOT_COUNT, TOTAL_SLOT_COUNT, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!mergeItemStack(current, 7, BOT_SLOT_COUNT, false)
                    && !mergeItemStack(current, 0, 7, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (current.isEmpty()) {
            slot.putStack(ItemStack.EMPTY);
        } else {
            slot.onSlotChanged();
        }
        return copy;
    }

    @Override
    public void onAction(EntityPlayerMP sender, String actionId, @Nullable NBTTagCompound data) {
        if (!canInteractWith(sender)) {
            return;
        }
        AutomatonTaskConfig cfg = bot.getTaskConfig();
        NBTTagCompound payload = data != null ? data : new NBTTagCompound();
        switch (actionId) {
            case "automata:set_job":
                JobDescriptor selectedJob = null;
                if (payload.hasKey("jobId")) {
                    selectedJob = AutomataAPI.getJob(new net.minecraft.util.ResourceLocation(payload.getString("jobId")));
                } else {
                    selectedJob = AutomataAPI.getJobRegistry().getByIndex(payload.getInteger("ordinal"));
                }
                if (selectedJob != null) {
                    cfg.setJobId(selectedJob.getId());
                    UINetwork.sendToast(
                            sender,
                            "Automata",
                            "Job: " + net.minecraft.util.text.translation.I18n.translateToLocal(selectedJob.getTitleKey()),
                            2.0F);
                }
                break;
            case "automata:set_pos1_here":
                if (!com.x4yi.automata.ai.jobs.MineJob.rejectAreaIfMiner(sender, bot)) {
                    cfg.setPos1(sender.getPosition());
                }
                break;
            case "automata:set_pos2_here":
                if (!com.x4yi.automata.ai.jobs.MineJob.rejectAreaIfMiner(sender, bot)) {
                    cfg.setPos2(sender.getPosition());
                }
                break;
            case "automata:clear_zone":
                cfg.setPos1(null);
                cfg.setPos2(null);
                break;
            case "automata:set_chest":
                applyChestTarget(cfg, payload.getString("chest"), resolveTargetBlock(sender));
                UINetwork.sendToast(sender, "Automata", "Chest updated: " + payload.getString("chest"), 1.8F);
                break;
            case "automata:clear_chest":
                applyChestTarget(cfg, payload.getString("chest"), null);
                break;
            case "automata:set_deposit_here":
                cfg.setDepositChest(resolveTargetBlock(sender));
                break;
            case "automata:clear_deposit":
                cfg.setDepositChest(null);
                break;
            case "automata:set_source_here":
                cfg.setSourceChest(resolveTargetBlock(sender));
                break;
            case "automata:clear_source":
                cfg.setSourceChest(null);
                break;
            case "automata:toggle_whitelist":
                cfg.setWhitelistMode(!cfg.isWhitelistMode());
                break;
            case "automata:toggle_filter_entity":
                String entityId = payload.getString("entityId");
                if (!entityId.isEmpty()) {
                    if (!cfg.getEntityFilterIds().remove(entityId)) {
                        cfg.getEntityFilterIds().add(entityId);
                    }
                }
                break;
            case "automata:clear_filters":
                cfg.getEntityFilterIds().clear();
                UINetwork.sendToast(sender, "Automata", "Entity filters cleared", 1.8F);
                break;
            case "automata:toggle_squad":
                cfg.setSquadCooperation(!cfg.isSquadCooperation());
                break;
            case "automata:set_squad_channel":
                cfg.setSquadChannel(payload.getString("channel"));
                UINetwork.sendToast(sender, "Automata", "Squad channel updated", 1.8F);
                break;
            case "automata:toggle_terrain":
                cfg.setAllowBreakThrough(!cfg.isAllowBreakThrough());
                break;
            case "automata:toggle_parkour":
                cfg.setAllowParkour(!cfg.isAllowParkour());
                break;
            case "automata:toggle_scaffolding":
                cfg.setAllowScaffolding(!cfg.isAllowScaffolding());
                break;
            case "automata:toggle_fill_depressions":
                cfg.setFillDepressions(!cfg.isFillDepressions());
                break;
            case "automata:set_skin":
                bot.setSkinName(payload.getString("skin"));
                UINetwork.sendToast(sender, "Automata", "Skin updated", 1.8F);
                break;
            case "automata:set_name":
                bot.setAutomatonName(payload.getString("name"));
                String updatedName = bot.getCustomNameTag();
                UINetwork.sendToast(
                        sender,
                        "Automata",
                        updatedName.isEmpty() ? "Name cleared" : "Name: " + updatedName,
                        1.8F);
                break;
            case "automata:toggle_slim":
                bot.setSlimModel(!bot.isSlimModel());
                break;
            case "automata:set_radius":
                cfg.setCombatRadius(payload.getDouble("radius"));
                break;
            case "automata:cycle_completion":
                TaskCompletionBehavior[] behaviors = TaskCompletionBehavior.values();
                int nextIdx = (cfg.getCompletionBehavior().ordinal() + 1) % behaviors.length;
                cfg.setCompletionBehavior(behaviors[nextIdx]);
                UINetwork.sendToast(sender, "Automata", "On Finish: " + behaviors[nextIdx].getDisplayName(), 1.8F);
                break;
            case "automata:backpack_extract":
                IItemHandlerModifiable bp = bot.getInventory().getBackpackHandler();
                int slotIdx = payload.getInteger("slot");
                if (bp != null && slotIdx >= 0 && slotIdx < bp.getSlots()) {
                    ItemStack extracted = bp.extractItem(slotIdx, 64, false);
                    if (!extracted.isEmpty() && !sender.inventory.addItemStackToInventory(extracted)) {
                        sender.dropItem(extracted, false);
                    }
                }
                break;
            default:
                return;
        }
        detectAndSendChanges();
    }

    private static BlockPos resolveTargetBlock(EntityPlayerMP sender) {
        Vec3d eyes = sender.getPositionEyes(1.0F);
        Vec3d look = sender.getLookVec();
        Vec3d end = eyes.add(look.x * 6.0D, look.y * 6.0D, look.z * 6.0D);
        RayTraceResult hit = sender.world.rayTraceBlocks(eyes, end, false, true, false);
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK && hit.getBlockPos() != null) {
            return hit.getBlockPos();
        }
        return sender.getPosition().down();
    }

    private static void applyChestTarget(AutomatonTaskConfig cfg, String chestType, @Nullable BlockPos pos) {
        switch (chestType) {
            case "source":
                cfg.setSourceChest(pos);
                break;
            case "deposit":
                cfg.setDepositChest(pos);
                break;
            case "tool":
                cfg.setToolChest(pos);
                break;
            case "food":
                cfg.setFoodChest(pos);
                break;
            default:
                break;
        }
    }

    public static NBTTagCompound buildSnapshot(EntityAutomaton bot) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("TaskConfig", bot.getTaskConfig().serializeNBT());
        tag.setString("SkinName", bot.getSkinName());
        tag.setString("AutomatonName", bot.getCustomNameTag());
        tag.setBoolean("SlimModel", bot.isSlimModel());
        tag.setBoolean("Downed", bot.isDowned());
        tag.setFloat("Health", bot.getHealth());
        tag.setFloat("MaxHealth", bot.getMaxHealth());
        tag.setFloat("Hunger", bot.getHunger());
        tag.setFloat("Saturation", bot.getSaturation());
        tag.setFloat("LoadRatio", LogisticsService.getInventoryOccupancyRatio(bot.getInventory().getMainInventory()));
        tag.setInteger("ChunkTickets", bot.getBrain().getContext().getWorld().getPersistentChunks().size());
        tag.setBoolean("HasPath", !bot.getNavigator().noPath());

        ItemStack backpackStack = bot.getInventory().getBackpackSlot().getStackInSlot(0);
        if (!backpackStack.isEmpty()) {
            tag.setString("BackpackName", backpackStack.getDisplayName());
            IItemHandlerModifiable backpackHandler = bot.getInventory().getBackpackHandler();
            if (backpackHandler != null) {
                int used = 0;
                int total = backpackHandler.getSlots();
                NBTTagList bpItems = new NBTTagList();
                for (int i = 0; i < total; i++) {
                    ItemStack bpStack = backpackHandler.getStackInSlot(i);
                    if (!bpStack.isEmpty()) {
                        used++;
                        NBTTagCompound itemTag = new NBTTagCompound();
                        itemTag.setInteger("Slot", i);
                        itemTag.setString("Name", bpStack.getDisplayName());
                        itemTag.setInteger("Count", bpStack.getCount());
                        bpItems.appendTag(itemTag);
                    }
                }
                tag.setInteger("BackpackUsed", used);
                tag.setInteger("BackpackTotal", total);
                tag.setTag("BackpackItems", bpItems);
            }
        }

        NBTTagList squadList = new NBTTagList();
        UUID owner = bot.getOwnerUuid();
        String channel = bot.getTaskConfig().getSquadChannel();
        List<EntityAutomaton> nearby = bot.world.getEntitiesWithinAABB(
                EntityAutomaton.class,
                bot.getEntityBoundingBox().grow(96.0D));
        for (EntityAutomaton other : nearby) {
            if (other == bot || other.isDead) {
                continue;
            }
            boolean sameOwner = owner != null && owner.equals(other.getOwnerUuid());
            boolean sameChannel = !channel.isEmpty() && channel.equals(other.getTaskConfig().getSquadChannel());
            if (!sameOwner && !sameChannel) {
                continue;
            }
            NBTTagCompound mate = new NBTTagCompound();
            mate.setInteger("EntityId", other.getEntityId());
            mate.setString("Name", other.getName());
            mate.setString("Job", other.getTaskConfig().getJobId().toString());
            mate.setFloat("Health", other.getHealth());
            mate.setFloat("MaxHealth", other.getMaxHealth());
            mate.setBoolean("Downed", other.isDowned());
            mate.setDouble("Distance", bot.getDistance(other));
            squadList.appendTag(mate);
        }
        tag.setTag("Squadmates", squadList);
        return tag;
    }
}
