/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.x4yi.x4ui.common.State;
import com.x4yi.x4ui.common.sync.NetworkSyncHelper;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.client.RegionSelectionRenderer;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.gui.ContainerAutomaton;

/**
 * Reactive client-side state store and RPC snapshot parser for {@link GuiAutomatonScreen}.
 */
@SideOnly(Side.CLIENT)
public final class AutomatonGuiState {

    public final State<Float> healthState = new State<>(20.0F);
    public final State<Float> maxHealthState = new State<>(20.0F);
    public final State<Float> hungerState = new State<>(20.0F);
    public final State<Float> loadState = new State<>(0.0F);
    public final State<Integer> jobOrdinalState = new State<>(0);
    public final State<Boolean> downedState = new State<>(false);
    public final State<Boolean> whitelistState = new State<>(false);
    public final State<Boolean> squadCoopState = new State<>(true);
    public final State<Boolean> breakThroughState = new State<>(true);
    public final State<Boolean> parkourState = new State<>(true);
    public final State<Boolean> scaffoldingState = new State<>(false);
    public final State<Boolean> fillDepressionsState = new State<>(false);
    public final State<Boolean> slimState = new State<>(false);
    public final State<Float> syncedRadiusState = new State<>(24.0F);
    public final State<Double> radiusState = new State<>(24.0D);
    public final State<Integer> completionOrdinalState = new State<>(0);

    private final int entityId;
    private final AutomatonTaskConfig taskConfig = new AutomatonTaskConfig();
    private final List<SquadmateEntry> squadmates = new ArrayList<>();
    private final List<BackpackItemEntry> backpackItems = new ArrayList<>();
    private final List<Runnable> snapshotListeners = new ArrayList<>();

    private String skinName = "";
    private String automatonName = "";
    private String backpackSummary = "Backpack: None equipped";
    private int chunkTickets;
    private boolean hasPath;
    private boolean applyingSnapshot;

    public AutomatonGuiState(EntityAutomaton bot) {
        this.entityId = bot.getEntityId();
        this.taskConfig.deserializeNBT(bot.getTaskConfig().serializeNBT());
        this.skinName = bot.getSkinName();
        this.automatonName = bot.getCustomNameTag();
        this.healthState.set(bot.getHealth());
        this.maxHealthState.set(Math.max(1.0F, bot.getMaxHealth()));
        this.hungerState.set(bot.getHunger());
        this.jobOrdinalState.set(Math.max(0, AutomataAPI.getJobRegistry().indexOf(this.taskConfig.getJobId())));
        this.downedState.set(bot.isDowned());
        this.whitelistState.set(this.taskConfig.isWhitelistMode());
        this.squadCoopState.set(this.taskConfig.isSquadCooperation());
        this.breakThroughState.set(this.taskConfig.isAllowBreakThrough());
        this.parkourState.set(this.taskConfig.isAllowParkour());
        this.scaffoldingState.set(this.taskConfig.isAllowScaffolding());
        this.fillDepressionsState.set(this.taskConfig.isFillDepressions());
        this.slimState.set(bot.isSlimModel());
        this.radiusState.set(this.taskConfig.getCombatRadius());
        this.completionOrdinalState.set(this.taskConfig.getCompletionBehavior().ordinal());
    }

    public void bindProperties(ContainerAutomaton container) {
        NetworkSyncHelper.bindContainerPropertyToFloat32(
                container, ContainerAutomaton.PROP_HEALTH_LOW, ContainerAutomaton.PROP_HEALTH_HIGH, healthState);
        NetworkSyncHelper.bindContainerPropertyToFloat32(
                container, ContainerAutomaton.PROP_MAX_HEALTH_LOW, ContainerAutomaton.PROP_MAX_HEALTH_HIGH, maxHealthState);
        NetworkSyncHelper.bindContainerPropertyToFloat32(
                container, ContainerAutomaton.PROP_HUNGER_LOW, ContainerAutomaton.PROP_HUNGER_HIGH, hungerState);
        NetworkSyncHelper.bindContainerPropertyToFloat32(
                container, ContainerAutomaton.PROP_LOAD_LOW, ContainerAutomaton.PROP_LOAD_HIGH, loadState);
        NetworkSyncHelper.bindContainerPropertyToFloat32(
                container, ContainerAutomaton.PROP_RADIUS_LOW, ContainerAutomaton.PROP_RADIUS_HIGH, syncedRadiusState);
        NetworkSyncHelper.bindContainerPropertyToState(container, ContainerAutomaton.PROP_JOB_ORDINAL, jobOrdinalState);
        NetworkSyncHelper.bindContainerPropertyToBoolean(container, ContainerAutomaton.PROP_DOWNED, downedState);
        NetworkSyncHelper.bindContainerPropertyToBoolean(container, ContainerAutomaton.PROP_WHITELIST, whitelistState);
        NetworkSyncHelper.bindContainerPropertyToBoolean(container, ContainerAutomaton.PROP_SQUAD_COOP, squadCoopState);
        NetworkSyncHelper.bindContainerPropertyToBoolean(container, ContainerAutomaton.PROP_TERRAIN_MOD, breakThroughState);
        NetworkSyncHelper.bindContainerPropertyToBoolean(container, ContainerAutomaton.PROP_SLIM, slimState);
        NetworkSyncHelper.bindContainerPropertyToBoolean(container, ContainerAutomaton.PROP_PARKOUR, parkourState);
        NetworkSyncHelper.bindContainerPropertyToBoolean(container, ContainerAutomaton.PROP_SCAFFOLDING, scaffoldingState);
        NetworkSyncHelper.bindContainerPropertyToBoolean(container, ContainerAutomaton.PROP_FILL_DEPRESSIONS, fillDepressionsState);
        NetworkSyncHelper.bindContainerPropertyToState(container, ContainerAutomaton.PROP_COMPLETION_ORDINAL, completionOrdinalState);
        syncedRadiusState.addListener(val -> {
            if (val != null) {
                applyingSnapshot = true;
                try {
                    radiusState.set(val.doubleValue());
                } finally {
                    applyingSnapshot = false;
                }
            }
        });
    }

    public void addSnapshotListener(Runnable listener) {
        snapshotListeners.add(listener);
    }

    public void applySnapshot(NBTTagCompound tag) {
        applyingSnapshot = true;
        try {
            if (tag.hasKey("TaskConfig", Constants.NBT.TAG_COMPOUND)) {
                taskConfig.deserializeNBT(tag.getCompoundTag("TaskConfig"));
                jobOrdinalState.set(Math.max(0, AutomataAPI.getJobRegistry().indexOf(taskConfig.getJobId())));
                whitelistState.set(taskConfig.isWhitelistMode());
                squadCoopState.set(taskConfig.isSquadCooperation());
                breakThroughState.set(taskConfig.isAllowBreakThrough());
                parkourState.set(taskConfig.isAllowParkour());
                scaffoldingState.set(taskConfig.isAllowScaffolding());
                fillDepressionsState.set(taskConfig.isFillDepressions());
                radiusState.set(taskConfig.getCombatRadius());
                completionOrdinalState.set(taskConfig.getCompletionBehavior().ordinal());
                RegionSelectionRenderer.setLastConfiguredZone(entityId, taskConfig.getPos1(), taskConfig.getPos2());
            }
            if (tag.hasKey("SkinName")) {
                skinName = tag.getString("SkinName");
            }
            if (tag.hasKey("AutomatonName")) {
                automatonName = tag.getString("AutomatonName");
            }
            if (tag.hasKey("SlimModel")) {
                slimState.set(tag.getBoolean("SlimModel"));
            }
            if (tag.hasKey("LoadRatio")) {
                loadState.set(tag.getFloat("LoadRatio"));
            }
            backpackItems.clear();
            if (tag.hasKey("BackpackName")) {
                String bpName = tag.getString("BackpackName");
                if (tag.hasKey("BackpackTotal")) {
                    backpackSummary = "Backpack: " + bpName + " ("
                            + tag.getInteger("BackpackUsed") + "/" + tag.getInteger("BackpackTotal") + " used)";
                } else {
                    backpackSummary = "Backpack: " + bpName;
                }
                if (tag.hasKey("BackpackItems", Constants.NBT.TAG_LIST)) {
                    NBTTagList bpList = tag.getTagList("BackpackItems", Constants.NBT.TAG_COMPOUND);
                    for (int i = 0; i < bpList.tagCount(); i++) {
                        NBTTagCompound itemTag = bpList.getCompoundTagAt(i);
                        backpackItems.add(new BackpackItemEntry(
                                itemTag.getInteger("Slot"),
                                itemTag.getString("Name"),
                                itemTag.getInteger("Count")));
                    }
                }
            } else {
                backpackSummary = "Backpack: None equipped";
            }
            squadmates.clear();
            if (tag.hasKey("Squadmates", Constants.NBT.TAG_LIST)) {
                NBTTagList list = tag.getTagList("Squadmates", Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound mate = list.getCompoundTagAt(i);
                    squadmates.add(new SquadmateEntry(
                            mate.getInteger("EntityId"),
                            mate.getString("Name"),
                            mate.getString("Job"),
                            mate.getFloat("Health"),
                            mate.getFloat("MaxHealth"),
                            mate.getBoolean("Downed"),
                            mate.getDouble("Distance")));
                }
            }
            chunkTickets = tag.getInteger("ChunkTickets");
            hasPath = tag.getBoolean("HasPath");
        } finally {
            applyingSnapshot = false;
        }
        for (Runnable listener : snapshotListeners) {
            listener.run();
        }
    }

    public AutomatonTaskConfig getTaskConfig() {
        return taskConfig;
    }

    public List<SquadmateEntry> getSquadmates() {
        return Collections.unmodifiableList(squadmates);
    }

    public List<BackpackItemEntry> getBackpackItems() {
        return Collections.unmodifiableList(backpackItems);
    }

    public String getSkinName() {
        return skinName;
    }

    public String getAutomatonName() {
        return automatonName;
    }

    public String getBackpackSummary() {
        return backpackSummary;
    }

    public int getChunkTickets() {
        return chunkTickets;
    }

    public boolean hasPath() {
        return hasPath;
    }

    public boolean isApplyingSnapshot() {
        return applyingSnapshot;
    }

    public static final class BackpackItemEntry {
        public final int slot;
        public final String name;
        public final int count;

        public BackpackItemEntry(int slot, String name, int count) {
            this.slot = slot;
            this.name = name;
            this.count = count;
        }
    }

    public static final class SquadmateEntry {
        public final int entityId;
        public final String name;
        public final String job;
        public final float health;
        public final float maxHealth;
        public final boolean downed;
        public final double distance;

        public SquadmateEntry(
                int entityId,
                String name,
                String job,
                float health,
                float maxHealth,
                boolean downed,
                double distance) {
            this.entityId = entityId;
            this.name = name;
            this.job = job;
            this.health = health;
            this.maxHealth = maxHealth;
            this.downed = downed;
            this.distance = distance;
        }
    }
}
