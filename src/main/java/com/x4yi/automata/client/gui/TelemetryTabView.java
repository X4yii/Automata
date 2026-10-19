/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.client.gui;

import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiMarkdown;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Debug-gated telemetry tab showing live pathfinding, chunk tickets, and state diagnostics.
 */
@SideOnly(Side.CLIENT)
public final class TelemetryTabView {

    private final GuiScrollPanel root;
    private final GuiMarkdown markdown;

    public TelemetryTabView(EntityAutomaton bot, AutomatonGuiState state, Runnable refreshSnapshot) {
        this.root = AutomataUiTheme.createTabScroll();

        GuiPanel card = AutomataUiTheme.makeCard("Live Automaton Telemetry", 6);
        this.markdown = (GuiMarkdown) new GuiMarkdown(0, 0, 260, "").withPercentWidth(1.0F);
        card.addChild(markdown);

        GuiButton refreshBtn = new GuiButton(0, 0, 100, 16, "Refresh Snapshot", refreshSnapshot)
                .withVariant(GuiButton.Variant.PRIMARY);
        refreshBtn.withPercentWidth(1.0F);
        card.addChild(refreshBtn);
        root.addChild(card);

        state.addSnapshotListener(() -> updateTelemetry(bot, state));
        updateTelemetry(bot, state);
    }

    private void updateTelemetry(EntityAutomaton bot, AutomatonGuiState state) {
        AutomatonTaskConfig cfg = state.getTaskConfig();
        String md = "### Automaton #" + bot.getEntityId() + "\n"
                + "- **Job:** `" + cfg.getJobId() + "`\n"
                + "- **Health:** `" + String.format("%.1f / %.1f", state.healthState.get(), state.maxHealthState.get()) + "`\n"
                + "- **Hunger:** `" + String.format("%.1f / 20.0", state.hungerState.get()) + "`\n"
                + "- **Inventory Load:** `" + Math.round(state.loadState.get() * 100.0F) + "%`\n"
                + "- **Zone:** `" + AutomataUiTheme.formatPos(cfg.getPos1()) + "` -> `" + AutomataUiTheme.formatPos(cfg.getPos2()) + "`\n"
                + "- **Squadmates:** `" + state.getSquadmates().size() + "` (Channel: `"
                + (cfg.getSquadChannel().isEmpty() ? "none" : cfg.getSquadChannel()) + "`)\n"
                + "- **Has Active Path:** `" + state.hasPath() + "`\n"
                + "- **Loaded Chunk Tickets:** `" + state.getChunkTickets() + "`\n"
                + "- **Max Path Threads:** `" + AutomataConfig.maxPathfindingThreads + "`\n";
        markdown.setText(md);
    }

    public GuiScrollPanel getRoot() {
        return root;
    }
}

