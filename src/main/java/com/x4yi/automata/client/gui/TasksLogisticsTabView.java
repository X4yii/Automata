/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;
import com.x4yi.x4ui.client.gui.component.layout.FlexAlign;
import com.x4yi.x4ui.client.gui.component.slider.GuiSliderDouble;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.JobDescriptor;
import com.x4yi.automata.ai.TaskCompletionBehavior;
import com.x4yi.automata.client.RegionSelectionController;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Consolidated Tasks view uniting jobs, A-B zone selection, job parameters, and logistics chests.
 */
@SideOnly(Side.CLIENT)
public final class TasksLogisticsTabView {

    private final GuiScrollPanel root;
    private final List<GuiButton> jobButtons = new ArrayList<>();
    private final GuiLabel zoneSummaryLabel;
    private final GuiLabel radiusLabel;
    private final GuiLabel sourceChestLabel;
    private final GuiLabel depositChestLabel;
    private final GuiLabel toolChestLabel;
    private final GuiLabel foodChestLabel;

    public TasksLogisticsTabView(
            EntityAutomaton bot,
            AutomatonGuiState state,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        this.root = AutomataUiTheme.createTabScroll();

        GuiPanel jobsCard = AutomataUiTheme.makeCard("Active Job Mode", 4);
        List<JobDescriptor> jobs = AutomataAPI.getAllJobs();
        for (int i = 0; i < jobs.size(); i += 2) {
            GuiPanel row = GuiPanel.row(4);
            row.setHugContent(true);
            row.withPercentWidth(1.0F);
            row.addChild(createJobButton(jobs.get(i), sendAction, refreshSnapshot));
            if (i + 1 < jobs.size()) {
                row.addChild(createJobButton(jobs.get(i + 1), sendAction, refreshSnapshot));
            } else {
                GuiButton stopBtn = new GuiButton(0, 0, 80, 16, "Stop / Idle", () -> {
                    NBTTagCompound data = new NBTTagCompound();
                    data.setString("jobId", AutomatonBuiltInJobs.IDLE.toString());
                    sendAction.accept("automata:set_job", data);
                    refreshSnapshot.run();
                }).withVariant(GuiButton.Variant.DANGER);
                stopBtn.withPercentWidth(0.49F);
                row.addChild(stopBtn);
            }
            jobsCard.addChild(row);
        }
        root.addChild(jobsCard);

        GuiPanel zoneCard = AutomataUiTheme.makeCard("Work Zone (A -> B)", 4);
        this.zoneSummaryLabel = new GuiLabel(0, 0, "", AutomataUiTheme.COLOR_MUTED);
        zoneCard.addChild(zoneSummaryLabel);

        GuiPanel primaryZoneRow = GuiPanel.row(4);
        primaryZoneRow.setHugContent(true);
        primaryZoneRow.withPercentWidth(1.0F);
        GuiButton selectInWorldBtn = new GuiButton(0, 0, 120, 18, "Select A -> B in World", () -> {
            AutomatonTaskConfig cfg = state.getTaskConfig();
            RegionSelectionController.startSession(bot.getEntityId(), cfg.getPos1(), cfg.getPos2());
        }).withVariant(GuiButton.Variant.PRIMARY);
        selectInWorldBtn.withPercentWidth(0.66F);
        selectInWorldBtn.withTooltip("Right-Click block for A, ENTER to confirm, then B and ENTER");
        primaryZoneRow.addChild(selectInWorldBtn);

        GuiButton clearZoneBtn = new GuiButton(0, 0, 50, 18, "Clear", () -> {
            sendAction.accept("automata:clear_zone", null);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.DANGER);
        clearZoneBtn.withPercentWidth(0.32F);
        primaryZoneRow.addChild(clearZoneBtn);
        zoneCard.addChild(primaryZoneRow);

        GuiPanel quickZoneRow = GuiPanel.row(4);
        quickZoneRow.setHugContent(true);
        quickZoneRow.withPercentWidth(1.0F);
        GuiButton ptABtn = new GuiButton(0, 0, 60, 16, "Pt A Here", () -> {
            sendAction.accept("automata:set_pos1_here", null);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.GHOST);
        ptABtn.withPercentWidth(0.49F);
        GuiButton ptBBtn = new GuiButton(0, 0, 60, 16, "Pt B Here", () -> {
            sendAction.accept("automata:set_pos2_here", null);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.GHOST);
        ptBBtn.withPercentWidth(0.49F);
        quickZoneRow.addChild(ptABtn);
        quickZoneRow.addChild(ptBBtn);
        zoneCard.addChild(quickZoneRow);
        root.addChild(zoneCard);

        GuiPanel paramsCard = AutomataUiTheme.makeCard("Job Options & Combat Radius", 4);
        this.radiusLabel = new GuiLabel(
                0, 0,
                "Combat Radius: " + Math.round(state.radiusState.get()) + "m",
                AutomataUiTheme.COLOR_MUTED);
        paramsCard.addChild(radiusLabel);

        GuiSliderDouble radiusSlider = (GuiSliderDouble) new GuiSliderDouble(
                0, 0, 0, 16, state.radiusState, 4.0D, 64.0D, 1.0D).withPercentWidth(1.0F);
        radiusSlider.bindState(state.radiusState, val -> {
            if (val != null) {
                radiusLabel.setText("Combat Radius: " + Math.round(val) + "m");
                if (!state.isApplyingSnapshot()) {
                    NBTTagCompound data = new NBTTagCompound();
                    data.setDouble("radius", val);
                    sendAction.accept("automata:set_radius", data);
                }
            }
        });
        paramsCard.addChild(radiusSlider);

        GuiButton completionBtn = new GuiButton(
                0, 0, 120, 18,
                "On Finish: " + state.getTaskConfig().getCompletionBehavior().getDisplayName(),
                () -> {
                    sendAction.accept("automata:cycle_completion", null);
                    refreshSnapshot.run();
                }).withVariant(GuiButton.Variant.GHOST);
        completionBtn.withPercentWidth(1.0F);
        completionBtn.withTooltip("Behavior when Excavation, Lumberjack, or Courier finishes");
        state.completionOrdinalState.addListener(ord -> {
            TaskCompletionBehavior[] vals = TaskCompletionBehavior.values();
            if (ord != null && ord >= 0 && ord < vals.length) {
                completionBtn.setText("On Finish: " + vals[ord].getDisplayName());
            }
        });
        paramsCard.addChild(completionBtn);
        root.addChild(paramsCard);

        GuiPanel logisticsCard = AutomataUiTheme.makeCard("Logistics Chests (Look at Chest)", 4);
        this.sourceChestLabel = addChestRow(logisticsCard, "Source", "source", sendAction, refreshSnapshot);
        this.depositChestLabel = addChestRow(logisticsCard, "Deposit", "deposit", sendAction, refreshSnapshot);
        this.toolChestLabel = addChestRow(logisticsCard, "Tool", "tool", sendAction, refreshSnapshot);
        this.foodChestLabel = addChestRow(logisticsCard, "Food", "food", sendAction, refreshSnapshot);
        root.addChild(logisticsCard);

        state.jobOrdinalState.addListener(this::refreshJobSelection);
        state.addSnapshotListener(() -> syncFromState(state));
        refreshJobSelection(state.jobOrdinalState.get());
        syncFromState(state);
    }

    private GuiButton createJobButton(
            JobDescriptor job,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        GuiButton btn = new GuiButton(0, 0, 80, 16, formatJobTitle(job), () -> {
            NBTTagCompound data = new NBTTagCompound();
            data.setString("jobId", job.getId().toString());
            sendAction.accept("automata:set_job", data);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.GHOST);
        btn.withPercentWidth(0.49F);
        btn.withTooltip(translateOrRaw(job.getDescriptionKey()));
        jobButtons.add(btn);
        return btn;
    }

    private static GuiLabel addChestRow(
            GuiPanel parent,
            String title,
            String key,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        GuiPanel row = GuiPanel.row(4);
        row.setHugContent(true);
        row.withPercentWidth(1.0F);
        row.setAlignItems(FlexAlign.CENTER);

        GuiLabel label = new GuiLabel(0, 0, title + ": Unset", AutomataUiTheme.COLOR_MUTED);
        label.withPercentWidth(0.56F);
        row.addChild(label);

        GuiButton setBtn = new GuiButton(0, 0, 50, 16, "Set Looked", () -> {
            NBTTagCompound data = new NBTTagCompound();
            data.setString("chest", key);
            sendAction.accept("automata:set_chest", data);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.PRIMARY);
        setBtn.withPercentWidth(0.24F);
        row.addChild(setBtn);

        GuiButton clearBtn = new GuiButton(0, 0, 32, 16, "Clr", () -> {
            NBTTagCompound data = new NBTTagCompound();
            data.setString("chest", key);
            sendAction.accept("automata:clear_chest", data);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.GHOST);
        clearBtn.withPercentWidth(0.16F);
        row.addChild(clearBtn);

        parent.addChild(row);
        return label;
    }

    private void refreshJobSelection(int ordinal) {
        for (int i = 0; i < jobButtons.size(); i++) {
            jobButtons.get(i).setSelected(i == ordinal);
        }
    }

    private void syncFromState(AutomatonGuiState state) {
        AutomatonTaskConfig cfg = state.getTaskConfig();
        zoneSummaryLabel.setText("A: " + AutomataUiTheme.formatPos(cfg.getPos1())
                + " | B: " + AutomataUiTheme.formatPos(cfg.getPos2()));
        sourceChestLabel.setText("Source: " + AutomataUiTheme.formatPos(cfg.getSourceChest()));
        depositChestLabel.setText("Deposit: " + AutomataUiTheme.formatPos(cfg.getDepositChest()));
        toolChestLabel.setText("Tool: " + AutomataUiTheme.formatPos(cfg.getToolChest()));
        foodChestLabel.setText("Food: " + AutomataUiTheme.formatPos(cfg.getFoodChest()));
    }

    public GuiScrollPanel getRoot() {
        return root;
    }

    public static String formatJobTitle(JobDescriptor job) {
        return job == null ? "Idle" : translateOrRaw(job.getTitleKey());
    }

    private static String translateOrRaw(String key) {
        return net.minecraft.client.resources.I18n.hasKey(key)
                ? net.minecraft.client.resources.I18n.format(key)
                : key;
    }
}
