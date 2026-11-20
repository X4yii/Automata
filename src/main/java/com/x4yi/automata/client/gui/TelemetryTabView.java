
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

@SideOnly(Side.CLIENT)
public final class TelemetryTabView {

    private final GuiScrollPanel root;
    private final GuiMarkdown markdown;

    public TelemetryTabView(EntityAutomaton bot, AutomatonGuiState state, Runnable refreshSnapshot) {
        this.root = AutomataUiTheme.createTabScroll();

        GuiPanel card = AutomataUiTheme.makeCard(tr("gui.automata.telemetry.title", "Live Automaton Telemetry"), 6);
        this.markdown = card.add(new GuiMarkdown(0, 0, 260, ""), md -> md.withPercentWidth(1.0F));

        card.add(GuiButton.of(tr("gui.automata.telemetry.refresh", "Refresh Snapshot"), refreshSnapshot).withVariant(GuiButton.Variant.PRIMARY),
                btn -> btn.withSize(100, 16).withPercentWidth(1.0F));
        root.add(card);

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
        java.util.List<String> trace = state.getDecisionTrace();
        if (!trace.isEmpty()) {
            StringBuilder sb = new StringBuilder(md);
            sb.append("\n### Decision Trace\n");
            for (String entry : trace) {
                sb.append("- `").append(entry).append("`\n");
            }
            md = sb.toString();
        }
        markdown.setText(md);
    }

    public GuiScrollPanel getRoot() {
        return root;
    }

    private static String tr(String key, String fallback) {
        return net.minecraft.client.resources.I18n.hasKey(key)
                ? net.minecraft.client.resources.I18n.format(key)
                : fallback;
    }
}

