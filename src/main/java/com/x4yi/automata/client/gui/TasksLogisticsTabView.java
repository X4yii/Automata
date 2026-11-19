
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
import com.x4yi.automata.ai.TaskCompletionBehavior;
import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.api.JobDescriptor;
import com.x4yi.automata.client.RegionSelectionController;
import com.x4yi.automata.entity.EntityAutomaton;

@SideOnly(Side.CLIENT)
public final class TasksLogisticsTabView {

    private final GuiScrollPanel root;
    private final List<GuiButton> jobButtons = new ArrayList<>();
    private final GuiLabel zoneSummaryLabel;
    private final GuiLabel radiusLabel;
    private final GuiLabel sourceChestLabel;
    private final GuiLabel savingChestLabel;

    public TasksLogisticsTabView(
            EntityAutomaton bot,
            AutomatonGuiState state,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        this.root = AutomataUiTheme.createTabScroll();

        GuiPanel jobsCard = AutomataUiTheme.makeCard(tr("gui.automata.tasks.active_job", "Active Job Mode"), 4);
        List<JobDescriptor> jobs = AutomataAPI.getAllJobs();
        for (int i = 0; i < jobs.size(); i += 2) {
            GuiPanel row = GuiPanel.row(4)
                    .withHugContent(true)
                    .withPercentWidth(1.0F);
            row.add(createJobButton(jobs.get(i), sendAction, refreshSnapshot));
            if (i + 1 < jobs.size()) {
                row.add(createJobButton(jobs.get(i + 1), sendAction, refreshSnapshot));
            } else {
                row.add(GuiButton.of(tr("gui.automata.tasks.stop_idle", "Stop / Idle"), () -> {
                    NBTTagCompound data = new NBTTagCompound();
                    data.setString("jobId", AutomatonBuiltInJobs.IDLE.toString());
                    sendAction.accept("automata:set_job", data);
                    refreshSnapshot.run();
                }).withVariant(GuiButton.Variant.DANGER), btn -> btn.withSize(0, 16).withPercentWidth(-1.0F).withFlex(1.0F, 1.0F));
            }
            jobsCard.add(row);
        }
        root.add(jobsCard);

        GuiPanel zoneCard = AutomataUiTheme.makeCard(tr("gui.automata.tasks.work_zone", "Work Zone (A -> B)"), 4);
        this.zoneSummaryLabel = zoneCard.add(GuiLabel.of("", AutomataUiTheme.COLOR_MUTED));

        GuiPanel primaryZoneRow = GuiPanel.row(4)
                .withHugContent(true)
                .withPercentWidth(1.0F);
        primaryZoneRow.add(GuiButton.of(tr("gui.automata.tasks.select_world", "Select A -> B in World"), () -> {
            AutomatonTaskConfig cfg = state.getTaskConfig();
            RegionSelectionController.startSession(bot.getEntityId(), cfg.getPos1(), cfg.getPos2());
        }).withVariant(GuiButton.Variant.PRIMARY), btn -> {
            btn.withSize(0, 18).withPercentWidth(-1.0F).withFlex(1.0F, 1.0F);
            btn.withTooltip(tr("gui.automata.tasks.select_world_tip", "Right-Click block for A, ENTER to confirm, then B and ENTER"));
        });

        primaryZoneRow.add(GuiButton.of(tr("gui.automata.tasks.clear", "Clear"), () -> {
            sendAction.accept("automata:clear_zone", null);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.DANGER), btn -> btn.withSize(48, 18));
        zoneCard.add(primaryZoneRow);

        GuiPanel quickZoneRow = GuiPanel.row(4)
                .withHugContent(true)
                .withPercentWidth(1.0F);
        quickZoneRow.add(GuiButton.of(tr("gui.automata.tasks.pt_a_here", "Pt A Here"), () -> {
            sendAction.accept("automata:set_pos1_here", null);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.GHOST), btn -> btn.withSize(0, 16).withPercentWidth(-1.0F).withFlex(1.0F, 1.0F));
        quickZoneRow.add(GuiButton.of(tr("gui.automata.tasks.pt_b_here", "Pt B Here"), () -> {
            sendAction.accept("automata:set_pos2_here", null);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.GHOST), btn -> btn.withSize(0, 16).withPercentWidth(-1.0F).withFlex(1.0F, 1.0F));
        zoneCard.add(quickZoneRow);
        root.add(zoneCard);

        GuiPanel paramsCard = AutomataUiTheme.makeCard(tr("gui.automata.tasks.job_options", "Job Options & Combat Radius"), 4);
        this.radiusLabel = paramsCard.add(GuiLabel.of(
                formatCombatRadius(Math.round(state.radiusState.get())),
                AutomataUiTheme.COLOR_MUTED));

        GuiSliderDouble radiusSlider = paramsCard.add(GuiSliderDouble.of(state.radiusState, 4.0D, 64.0D, 1.0D));
        radiusSlider.withSize(0, 16);
        radiusSlider.bindState(state.radiusState, val -> {
            if (val != null) {
                radiusLabel.setText(formatCombatRadius(Math.round(val)));
                if (!state.isApplyingSnapshot()) {
                    NBTTagCompound data = new NBTTagCompound();
                    data.setDouble("radius", val);
                    sendAction.accept("automata:set_radius", data);
                }
            }
        });

        GuiButton completionBtn = paramsCard.add(GuiButton.of(
                formatOnFinish(state.getTaskConfig().getCompletionBehavior().getDisplayName()),
                () -> {
                    sendAction.accept("automata:cycle_completion", null);
                    refreshSnapshot.run();
                }).withVariant(GuiButton.Variant.GHOST));
        completionBtn.withSize(120, 18).withPercentWidth(1.0F)
                .withTooltip(tr("gui.automata.tasks.on_finish_tip", "Behavior when Excavation, Lumberjack, or Courier finishes"));
        completionBtn.bindState(state.completionOrdinalState, ord -> {
            TaskCompletionBehavior[] vals = TaskCompletionBehavior.values();
            if (ord != null && ord >= 0 && ord < vals.length) {
                completionBtn.setText(formatOnFinish(vals[ord].getDisplayName()));
            }
        });
        root.add(paramsCard);

        GuiPanel logisticsCard = AutomataUiTheme.makeCard(tr("gui.automata.tasks.logistics_chests", "Logistics Chests (Look at Chest)"), 4);
        this.sourceChestLabel = addChestRow(logisticsCard, tr("gui.automata.tasks.source", "Source"), "source", sendAction, refreshSnapshot);
        this.savingChestLabel = addChestRow(logisticsCard, tr("gui.automata.tasks.saving", "Saving"), "saving", sendAction, refreshSnapshot);
        root.add(logisticsCard);

        jobsCard.bindState(state.jobOrdinalState, ord -> {
            if (ord != null) {
                refreshJobSelection(ord);
            }
        });
        state.addSnapshotListener(() -> syncFromState(state));
        refreshJobSelection(state.jobOrdinalState.get());
        syncFromState(state);
    }

    private GuiButton createJobButton(
            JobDescriptor job,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        GuiButton btn = GuiButton.of(formatJobTitle(job), () -> {
            NBTTagCompound data = new NBTTagCompound();
            data.setString("jobId", job.getId().toString());
            sendAction.accept("automata:set_job", data);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.GHOST);
        btn.withSize(0, 16).withPercentWidth(-1.0F).withFlex(1.0F, 1.0F);
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
        GuiPanel row = GuiPanel.row(4)
                .withHugContent(true)
                .withPercentWidth(1.0F)
                .withAlignItems(FlexAlign.CENTER);

        GuiLabel label = row.add(GuiLabel.of(title + ": Unset", AutomataUiTheme.COLOR_MUTED));
        label.withPercentWidth(-1.0F).withFlex(1.0F, 1.0F);

        row.add(GuiButton.of(tr("gui.automata.tasks.set_looked", "Set Looked"), () -> {
            NBTTagCompound data = new NBTTagCompound();
            data.setString("chest", key);
            sendAction.accept("automata:set_chest", data);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.PRIMARY), btn -> btn.withSize(62, 16));

        row.add(GuiButton.of(tr("gui.automata.tasks.clr", "Clr"), () -> {
            NBTTagCompound data = new NBTTagCompound();
            data.setString("chest", key);
            sendAction.accept("automata:clear_chest", data);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.GHOST), btn -> btn.withSize(28, 16));

        parent.add(row);
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
        sourceChestLabel.setText(tr("gui.automata.tasks.source", "Source") + ": " + AutomataUiTheme.formatPos(cfg.getSourceChest()));
        savingChestLabel.setText(tr("gui.automata.tasks.saving", "Saving") + ": " + AutomataUiTheme.formatPos(cfg.getSavingChest()));
    }

    private static String formatCombatRadius(long meters) {
        return net.minecraft.client.resources.I18n.hasKey("gui.automata.tasks.combat_radius")
                ? net.minecraft.client.resources.I18n.format("gui.automata.tasks.combat_radius", meters)
                : "Combat Radius: " + meters + "m";
    }

    private static String formatOnFinish(String behavior) {
        return net.minecraft.client.resources.I18n.hasKey("gui.automata.tasks.on_finish")
                ? net.minecraft.client.resources.I18n.format("gui.automata.tasks.on_finish", behavior)
                : "On Finish: " + behavior;
    }

    private static String tr(String key, String fallback) {
        return net.minecraft.client.resources.I18n.hasKey(key)
                ? net.minecraft.client.resources.I18n.format(key)
                : fallback;
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
