/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.x4yi.x4ui.client.gui.base.GuiBaseContainer;
import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;
import com.x4yi.x4ui.client.gui.component.GuiSlot;
import com.x4yi.x4ui.client.gui.component.layout.FlexAlign;
import com.x4yi.x4ui.client.gui.component.layout.FlexDirection;
import com.x4yi.x4ui.client.gui.utils.Insets;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.JobDescriptor;
import com.x4yi.automata.client.gui.AutomataUiTheme.StatBarComponent;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.gui.ContainerAutomaton;

/**
 * Responsive, modular X4UI-powered dashboard coordinator for {@link EntityAutomaton}.
 */
@SideOnly(Side.CLIENT)
public class GuiAutomatonScreen extends GuiBaseContainer {

    private final ContainerAutomaton container;
    private final EntityAutomaton bot;
    private final AutomatonGuiState state;
    private final List<GuiSlot> allGuiSlots = new ArrayList<>();
    private final List<GuiButton> navButtons = new ArrayList<>();
    private final List<GuiScrollPanel> tabPanels = new ArrayList<>();

    private GuiPanel window;
    private GuiPanel bodyRow;
    private GuiLabel statusBadge;
    private GuiLabel sidebarJobLabel;
    private int activeTab = 0;

    public GuiAutomatonScreen(ContainerAutomaton container) {
        super(container);
        this.container = container;
        this.bot = container.getBot();
        this.state = new AutomatonGuiState(bot);
    }

    @Override
    public void initGui() {
        this.xSize = Math.min(AutomataUiTheme.MAX_WINDOW_W, Math.max(AutomataUiTheme.MIN_WINDOW_W, (int) (this.width * 0.90F)));
        this.ySize = Math.min(AutomataUiTheme.MAX_WINDOW_H, Math.max(AutomataUiTheme.MIN_WINDOW_H, (int) (this.height * 0.90F)));
        super.initGui();
    }

    @Override
    protected void initComponents() {
        allGuiSlots.clear();
        navButtons.clear();
        tabPanels.clear();

        state.bindProperties(container);

        window = new GuiPanel(guiLeft, guiTop, xSize, ySize);
        window.setFlexDirection(FlexDirection.VERTICAL);
        window.setGap(6);
        window.setPadding(new Insets(8));
        window.setBackgroundColor(AutomataUiTheme.COLOR_BG);
        window.setBorderColor(AutomataUiTheme.COLOR_BORDER);
        window.setBorderWidth(1);
        window.setCornerRadius(6);

        window.addChild(buildHeaderBar());

        bodyRow = GuiPanel.row(8);
        bodyRow.setHugContent(false);
        bodyRow.setHeight(Math.max(120, ySize - 46));
        bodyRow.setAlignItems(FlexAlign.STRETCH);
        bodyRow.addChild(buildSidebar());

        GuiPanel contentHost = new GuiPanel(0, 0, 0, 0);
        contentHost.withPercentSize(0.75F, 1.0F);
        contentHost.setFlexDirection(FlexDirection.ABSOLUTE);

        tabPanels.add(new TasksLogisticsTabView(bot, state, this::sendActionToServer, this::requestSnapshot).getRoot());
        tabPanels.add(new OverviewTabView(this, container, bot, state, allGuiSlots, this::sendActionToServer, this::requestSnapshot).getRoot());
        tabPanels.add(new BehaviorSquadTabView(state, this::sendActionToServer, this::requestSnapshot).getRoot());
        if (AutomataConfig.debugMode) {
            tabPanels.add(new TelemetryTabView(bot, state, this::requestSnapshot).getRoot());
        }
        for (GuiScrollPanel tab : tabPanels) {
            contentHost.addChild(tab);
        }
        bodyRow.addChild(contentHost);
        window.addChild(bodyRow);

        addComponent(window);

        switchTab(activeTab < tabPanels.size() ? activeTab : 0);
        requestSnapshot();
    }

    @Override
    protected void onScreenResize(int newWidth, int newHeight) {
        if (window != null) {
            window.setX(guiLeft);
            window.setY(guiTop);
            window.setWidth(xSize);
            window.setHeight(ySize);
            if (bodyRow != null) {
                bodyRow.setHeight(Math.max(120, ySize - 46));
            }
            window.requestLayout();
        }
    }

    private GuiPanel buildHeaderBar() {
        GuiPanel header = GuiPanel.row(8);
        header.setHugContent(false);
        header.setHeight(24);
        header.setPadding(new Insets(0, 6, 0, 6));
        header.setBackgroundColor(AutomataUiTheme.COLOR_SURFACE);
        header.setBorderColor(AutomataUiTheme.COLOR_BORDER);
        header.setBorderWidth(1);
        header.setCornerRadius(4);
        header.setAlignItems(FlexAlign.CENTER);

        header.addChild(new GuiLabel(0, 0, "AUTOMATA #" + bot.getEntityId(), AutomataUiTheme.COLOR_TEXT));

        statusBadge = new GuiLabel(0, 0, "[ACTIVE]", AutomataUiTheme.COLOR_GREEN);
        statusBadge.bindState(state.downedState, v -> refreshStatusBadge());
        statusBadge.bindState(state.hungerState, v -> refreshStatusBadge());
        statusBadge.bindState(state.loadState, v -> refreshStatusBadge());
        header.addChild(statusBadge);

        header.addChild(GuiPanel.spacer());

        StatBarComponent loadBar = new StatBarComponent(58, 12, "INV", AutomataUiTheme.COLOR_ACCENT);
        loadBar.bindState(state.loadState, ratio -> loadBar.setValue(
                ratio,
                String.format(Locale.ROOT, "INV %.0f%%", ratio * 100.0F)));
        header.addChild(loadBar);

        return header;
    }

    private void refreshStatusBadge() {
        if (statusBadge == null) {
            return;
        }
        if (Boolean.TRUE.equals(state.downedState.get())) {
            statusBadge.setText("[DOWNED]");
            statusBadge.setColor(AutomataUiTheme.COLOR_RED);
        } else if (state.hungerState.get() <= 6.0F) {
            statusBadge.setText("[HUNGRY]");
            statusBadge.setColor(AutomataUiTheme.COLOR_AMBER);
        } else if (state.loadState.get() >= 0.85F) {
            statusBadge.setText("[FULL INV]");
            statusBadge.setColor(AutomataUiTheme.COLOR_AMBER);
        } else {
            statusBadge.setText("[ACTIVE]");
            statusBadge.setColor(AutomataUiTheme.COLOR_GREEN);
        }
    }

    private GuiPanel buildSidebar() {
        GuiPanel sidebar = GuiPanel.column(4);
        sidebar.setHugContent(false);
        sidebar.withPercentSize(0.24F, 1.0F);
        sidebar.setPadding(new Insets(6));
        sidebar.setBackgroundColor(AutomataUiTheme.COLOR_SURFACE);
        sidebar.setBorderColor(AutomataUiTheme.COLOR_BORDER);
        sidebar.setBorderWidth(1);
        sidebar.setCornerRadius(4);

        addNavButton(sidebar, 0, "Tasks");
        addNavButton(sidebar, 1, "Inventory");
        addNavButton(sidebar, 2, "Squad");
        if (AutomataConfig.debugMode) {
            addNavButton(sidebar, 3, "Telemetry");
        }

        sidebar.addChild(GuiPanel.spacer());

        sidebarJobLabel = new GuiLabel(0, 0, "Job: Idle", AutomataUiTheme.COLOR_MUTED);
        sidebarJobLabel.bindState(state.jobOrdinalState, ord -> {
            if (ord != null) {
                JobDescriptor descriptor = AutomataAPI.getJobRegistry().getByIndex(ord);
                if (descriptor != null) {
                    sidebarJobLabel.setText("Job: " + TasksLogisticsTabView.formatJobTitle(descriptor));
                }
            }
        });
        sidebar.addChild(sidebarJobLabel);
        return sidebar;
    }

    private void addNavButton(GuiPanel sidebar, int tabIdx, String label) {
        GuiButton btn = (GuiButton) new GuiButton(0, 0, 0, 22, label, () -> switchTab(tabIdx))
                .withVariant(GuiButton.Variant.GHOST)
                .withPercentWidth(1.0F);
        navButtons.add(btn);
        sidebar.addChild(btn);
    }

    private void switchTab(int tabIdx) {
        activeTab = tabIdx;
        for (int i = 0; i < tabPanels.size(); i++) {
            tabPanels.get(i).setVisible(i == tabIdx);
        }
        for (int i = 0; i < navButtons.size(); i++) {
            navButtons.get(i).setSelected(i == tabIdx);
        }
        if (tabIdx != 1) {
            for (GuiSlot slot : allGuiSlots) {
                slot.getSlot().xPos = -9999;
                slot.getSlot().yPos = -9999;
            }
        } else if (window != null) {
            window.requestLayout();
        }
    }

    private void requestSnapshot() {
        NBTTagCompound params = new NBTTagCompound();
        params.setInteger("entityId", bot.getEntityId());
        request("automata:get_snapshot", params, result -> {
            if (result.isOk()) {
                state.applySnapshot(result.getData());
            }
        });
    }
}
