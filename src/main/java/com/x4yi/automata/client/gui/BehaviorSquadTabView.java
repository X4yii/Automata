/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;
import com.x4yi.x4ui.client.gui.component.GuiTextInput;
import com.x4yi.x4ui.client.gui.component.GuiToggle;
import com.x4yi.x4ui.client.gui.component.GuiVirtualList;
import com.x4yi.x4ui.client.gui.input.KeyCodes;
import com.x4yi.x4ui.common.State;

import net.minecraft.entity.EntityList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.client.gui.AutomatonGuiState.SquadmateEntry;

/**
 * Consolidated Squad view uniting squad coordination, entity targeting filters, movement flags, and skin.
 */
@SideOnly(Side.CLIENT)
public final class BehaviorSquadTabView {

    private final GuiScrollPanel root;
    private final GuiTextInput squadChannelInput;
    private final GuiTextInput filterSearchInput;
    private final GuiButton whitelistButton;
    private final GuiVirtualList<EntityFilterEntry> filterVirtualList;
    private final GuiTextInput nameInput;
    private final GuiTextInput skinInput;
    private final GuiLabel squadCountLabel;
    private final GuiVirtualList<SquadmateEntry> squadList;
    private final AutomatonGuiState state;
    private String lastSyncedSquad;
    private String lastSyncedName;
    private String lastSyncedSkin;

    public BehaviorSquadTabView(
            AutomatonGuiState state,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        this.state = state;
        this.root = AutomataUiTheme.createTabScroll();

        GuiPanel squadCard = AutomataUiTheme.makeCard(tr("gui.automata.squad.title", "Squad Coordination"), 4);
        squadCard.addChild(makeSyncedToggle(
                "Squad Cooperation",
                state.squadCoopState,
                () -> state.getTaskConfig().isSquadCooperation(),
                "automata:toggle_squad",
                sendAction,
                refreshSnapshot));

        GuiPanel chanRow = GuiPanel.row(4);
        chanRow.setHugContent(true);
        chanRow.withPercentWidth(1.0F);
        this.squadChannelInput = (GuiTextInput) new GuiTextInput(0, 0, 0, 18).withPercentWidth(0.62F);
        squadChannelInput.setPlaceholder(tr("gui.automata.squad.channel_placeholder", "Squad channel..."));
        squadChannelInput.setSubmitKey(KeyCodes.ENTER);
        squadChannelInput.onEnter(() -> submitSquadChannel(sendAction, refreshSnapshot));
        chanRow.addChild(squadChannelInput);

        GuiButton applyChanBtn = new GuiButton(
                0, 0, 44, 18, tr("gui.automata.squad.apply", "Apply"),
                () -> submitSquadChannel(sendAction, refreshSnapshot)).withVariant(GuiButton.Variant.PRIMARY);
        applyChanBtn.withPercentWidth(0.24F);
        chanRow.addChild(applyChanBtn);

        GuiButton clearChanBtn = new GuiButton(
                0, 0, 18, 18, "x",
                () -> squadChannelInput.setText("")).withVariant(GuiButton.Variant.DANGER);
        clearChanBtn.withPercentWidth(0.10F);
        chanRow.addChild(clearChanBtn);
        squadCard.addChild(chanRow);

        this.squadCountLabel = new GuiLabel(0, 0, "Nearby Squadmates: 0", AutomataUiTheme.COLOR_MUTED);
        squadCard.addChild(squadCountLabel);

        this.squadList = (GuiVirtualList<SquadmateEntry>) new GuiVirtualList<SquadmateEntry>(
                0, 0, 0, 54, 16,
                entry -> new GuiLabel(0, 0, "", AutomataUiTheme.COLOR_TEXT),
                (comp, entry) -> {
                    GuiLabel lbl = (GuiLabel) comp;
                    String status = entry.downed
                            ? "DOWN"
                            : String.format("%.0f/%.0f HP", entry.health, entry.maxHealth);
                    lbl.setText(String.format(
                            "%s | %s | %s | %.0fm",
                            entry.name,
                            entry.job,
                            status,
                            entry.distance));
                    lbl.setColor(entry.downed ? AutomataUiTheme.COLOR_RED : AutomataUiTheme.COLOR_TEXT);
                }).withPercentWidth(1.0F);
        squadCard.addChild(squadList);
        root.addChild(squadCard);

        GuiPanel filterCard = AutomataUiTheme.makeCard("Entity Targeting Filter", 4);
        GuiPanel searchRow = GuiPanel.row(4);
        searchRow.setHugContent(true);
        searchRow.withPercentWidth(1.0F);

        this.filterSearchInput = (GuiTextInput) new GuiTextInput(0, 0, 0, 18).withPercentWidth(0.52F);
        filterSearchInput.setPlaceholder("Search entity id...");
        filterSearchInput.setSubmitKey(KeyCodes.ENTER);
        filterSearchInput.setOnTextChanged(q -> rebuildFilterList());
        searchRow.addChild(filterSearchInput);

        this.whitelistButton = new GuiButton(
                0, 0, 60, 18,
                state.whitelistState.get() ? "Whitelist" : "Blacklist",
                () -> {
                    sendAction.accept("automata:toggle_whitelist", null);
                    refreshSnapshot.run();
                }).withVariant(GuiButton.Variant.PRIMARY);
        whitelistButton.withPercentWidth(0.26F);
        searchRow.addChild(whitelistButton);

        GuiButton clearFiltersBtn = new GuiButton(0, 0, 44, 18, "Clear", () -> {
            sendAction.accept("automata:clear_filters", null);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.DANGER);
        clearFiltersBtn.withPercentWidth(0.18F);
        searchRow.addChild(clearFiltersBtn);
        filterCard.addChild(searchRow);

        this.filterVirtualList = (GuiVirtualList<EntityFilterEntry>) new GuiVirtualList<EntityFilterEntry>(
                0, 0, 0, 96, 20,
                entry -> new GuiButton(0, 0, 0, 18, "", () -> {}).withPercentWidth(1.0F),
                (comp, entry) -> {
                    GuiButton btn = (GuiButton) comp;
                    btn.setText((entry.selected ? "[x] " : "[ ] ") + entry.id);
                    btn.setSelected(entry.selected);
                    btn.setOnClick(() -> {
                        NBTTagCompound data = new NBTTagCompound();
                        data.setString("entityId", entry.id);
                        sendAction.accept("automata:toggle_filter_entity", data);
                        refreshSnapshot.run();
                    });
                }).withPercentWidth(1.0F);
        filterCard.addChild(filterVirtualList);
        root.addChild(filterCard);

        GuiPanel moveCard = AutomataUiTheme.makeCard("Movement & Appearance", 4);
        moveCard.addChild(makeSyncedToggle(
                "Allow Break-Through",
                state.breakThroughState,
                () -> state.getTaskConfig().isAllowBreakThrough(),
                "automata:toggle_terrain",
                sendAction,
                refreshSnapshot));
        moveCard.addChild(makeSyncedToggle(
                "Allow Parkour Jumps",
                state.parkourState,
                () -> state.getTaskConfig().isAllowParkour(),
                "automata:toggle_parkour",
                sendAction,
                refreshSnapshot));
        moveCard.addChild(makeSyncedToggle(
                "Allow Pillar / Bridge",
                state.scaffoldingState,
                () -> state.getTaskConfig().isAllowScaffolding(),
                "automata:toggle_scaffolding",
                sendAction,
                refreshSnapshot));
        moveCard.addChild(makeSyncedToggle(
                "Slim Model (Alex)",
                state.slimState,
                () -> Boolean.TRUE.equals(state.slimState.get()),
                "automata:toggle_slim",
                sendAction,
                refreshSnapshot));

        GuiPanel nameRow = GuiPanel.row(4);
        nameRow.setHugContent(true);
        nameRow.withPercentWidth(1.0F);
        this.nameInput = (GuiTextInput) new GuiTextInput(0, 0, 0, 18).withPercentWidth(0.62F);
        nameInput.setPlaceholder(tr("gui.automata.squad.name_placeholder", "Automaton name (.name)..."));
        nameInput.setSubmitKey(KeyCodes.ENTER);
        nameInput.onEnter(() -> submitName(sendAction, refreshSnapshot));
        nameRow.addChild(nameInput);

        GuiButton applyNameBtn = new GuiButton(
                0, 0, 44, 18, tr("gui.automata.squad.apply", "Apply"),
                () -> submitName(sendAction, refreshSnapshot)).withVariant(GuiButton.Variant.PRIMARY);
        applyNameBtn.withPercentWidth(0.24F);
        nameRow.addChild(applyNameBtn);

        GuiButton clearNameBtn = new GuiButton(
                0, 0, 18, 18, "x",
                () -> nameInput.setText("")).withVariant(GuiButton.Variant.DANGER);
        clearNameBtn.withPercentWidth(0.10F);
        nameRow.addChild(clearNameBtn);
        moveCard.addChild(nameRow);

        GuiPanel skinRow = GuiPanel.row(4);
        skinRow.setHugContent(true);
        skinRow.withPercentWidth(1.0F);
        this.skinInput = (GuiTextInput) new GuiTextInput(0, 0, 0, 18).withPercentWidth(0.62F);
        skinInput.setPlaceholder(tr("gui.automata.squad.skin_placeholder", "Skin username..."));
        skinInput.setSubmitKey(KeyCodes.ENTER);
        skinInput.onEnter(() -> submitSkin(sendAction, refreshSnapshot));
        skinRow.addChild(skinInput);

        GuiButton applySkinBtn = new GuiButton(
                0, 0, 44, 18, tr("gui.automata.squad.apply", "Apply"),
                () -> submitSkin(sendAction, refreshSnapshot)).withVariant(GuiButton.Variant.PRIMARY);
        applySkinBtn.withPercentWidth(0.24F);
        skinRow.addChild(applySkinBtn);

        GuiButton clearSkinBtn = new GuiButton(
                0, 0, 18, 18, "x",
                () -> skinInput.setText("")).withVariant(GuiButton.Variant.DANGER);
        clearSkinBtn.withPercentWidth(0.10F);
        skinRow.addChild(clearSkinBtn);
        moveCard.addChild(skinRow);
        root.addChild(moveCard);

        state.whitelistState.addListener(wl -> whitelistButton.setText(Boolean.TRUE.equals(wl) ? "Whitelist" : "Blacklist"));
        state.addSnapshotListener(this::syncFromState);
        syncFromState();
    }

    private GuiToggle makeSyncedToggle(
            String label,
            State<Boolean> boundState,
            BooleanSupplier currentVal,
            String actionId,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        GuiToggle toggle = (GuiToggle) new GuiToggle(0, 0, 0, 18, label, Boolean.TRUE.equals(boundState.get()), val -> {
            if (!state.isApplyingSnapshot() && val != currentVal.getAsBoolean()) {
                sendAction.accept(actionId, null);
                refreshSnapshot.run();
            }
        }).withPercentWidth(1.0F);
        toggle.bindState(boundState);
        return toggle;
    }

    private void submitSquadChannel(BiConsumer<String, NBTTagCompound> sendAction, Runnable refreshSnapshot) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("channel", squadChannelInput.getText().trim());
        sendAction.accept("automata:set_squad_channel", data);
        refreshSnapshot.run();
    }

    private void submitName(BiConsumer<String, NBTTagCompound> sendAction, Runnable refreshSnapshot) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("name", nameInput.getText().trim());
        sendAction.accept("automata:set_name", data);
        refreshSnapshot.run();
    }

    private void submitSkin(BiConsumer<String, NBTTagCompound> sendAction, Runnable refreshSnapshot) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("skin", skinInput.getText().trim());
        sendAction.accept("automata:set_skin", data);
        refreshSnapshot.run();
    }

    private void rebuildFilterList() {
        String query = filterSearchInput.getText().trim().toLowerCase(Locale.ROOT);
        Set<String> active = state.getTaskConfig().getEntityFilterIds();
        List<String> ids = new ArrayList<>();
        for (ResourceLocation rl : EntityList.getEntityNameList()) {
            String id = rl.toString();
            if (query.isEmpty() || id.toLowerCase(Locale.ROOT).contains(query)) {
                ids.add(id);
            }
        }
        ids.sort(Comparator.comparing((String id) -> !active.contains(id)).thenComparing(Comparator.naturalOrder()));
        List<EntityFilterEntry> entries = new ArrayList<>(ids.size());
        for (String id : ids) {
            entries.add(new EntityFilterEntry(id, active.contains(id)));
        }
        filterVirtualList.setItems(entries);
    }

    private void syncFromState() {
        String nextSquad = state.getTaskConfig().getSquadChannel();
        if (!squadChannelInput.isFocused() && !Objects.equals(nextSquad, lastSyncedSquad)) {
            squadChannelInput.setText(nextSquad);
            lastSyncedSquad = nextSquad;
        }
        String nextName = state.getAutomatonName();
        if (!nameInput.isFocused() && !Objects.equals(nextName, lastSyncedName)) {
            nameInput.setText(nextName);
            lastSyncedName = nextName;
        }
        String nextSkin = state.getSkinName();
        if (!skinInput.isFocused() && !Objects.equals(nextSkin, lastSyncedSkin)) {
            skinInput.setText(nextSkin);
            lastSyncedSkin = nextSkin;
        }
        List<SquadmateEntry> mates = state.getSquadmates();
        squadCountLabel.setText("Nearby Squadmates: " + mates.size());
        squadList.setItems(mates);
        rebuildFilterList();
    }

    private static String tr(String key, String fallback) {
        return net.minecraft.client.resources.I18n.hasKey(key)
                ? net.minecraft.client.resources.I18n.format(key)
                : fallback;
    }

    public GuiScrollPanel getRoot() {
        return root;
    }

    public static final class EntityFilterEntry {
        public final String id;
        public final boolean selected;

        public EntityFilterEntry(String id, boolean selected) {
            this.id = id;
            this.selected = selected;
        }
    }
}
