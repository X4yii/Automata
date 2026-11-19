package com.x4yi.automata.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.x4yi.x4ui.api.client.gui.IGraphics;
import com.x4yi.x4ui.client.gui.base.GuiBaseContainer;
import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiComponent;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiModelRender;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiSlot;
import com.x4yi.x4ui.client.gui.component.layout.DockSide;
import com.x4yi.x4ui.client.gui.component.layout.FlexAlign;
import com.x4yi.x4ui.client.gui.component.layout.FlexDirection;
import com.x4yi.x4ui.client.gui.utils.Insets;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.client.RenderAutomaton;
import com.x4yi.automata.client.gui.AutomataUiTheme.StatBarComponent;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.gui.ContainerAutomaton;

@SideOnly(Side.CLIENT)
public class GuiAutomatonScreen extends GuiBaseContainer {

    private static final String[] TAB_KEYS = {
        "gui.automata.tab.overview",
        "gui.automata.tab.tasks",
        "gui.automata.tab.squad",
        "gui.automata.tab.telemetry"
    };
    private static final String[] TAB_FALLBACKS = { "Overview", "Tasks", "Squad", "Telemetry" };

    private final ContainerAutomaton container;
    private final EntityAutomaton bot;
    private final AutomatonGuiState state;

    private final List<GuiSlot> allGuiSlots = new ArrayList<>();
    private final List<GuiButton> navButtons = new ArrayList<>();
    private final List<GuiComponent> tabViews = new ArrayList<>();
    private OverviewTabView overviewTab;

    private GuiPanel window;
    private GuiPanel bodyRow;
    private GuiLabel titleLabel;
    private GuiLabel statusBadge;
    private StatBarComponent healthBar;
    private StatBarComponent hungerBar;
    private StatBarComponent loadBar;

    private int activeTabIndex = 0;

    public GuiAutomatonScreen(ContainerAutomaton container) {
        super(container);
        this.container = container;
        this.bot = container.getBot();
        this.state = new AutomatonGuiState(bot);
        this.xSize = AutomataUiTheme.MAX_WINDOW_W;
        this.ySize = AutomataUiTheme.MAX_WINDOW_H;
        setAnimateOpen(false);
    }

    @Override
    protected void initComponents() {
        allGuiSlots.clear();
        navButtons.clear();
        tabViews.clear();

        window = createCenteredWindow(AutomataUiTheme.MAX_WINDOW_W, AutomataUiTheme.MAX_WINDOW_H)
                .withFlexDirection(FlexDirection.VERTICAL)
                .withAlignItems(FlexAlign.STRETCH)
                .withGap(6)
                .withPadding(8)
                .withBackgroundColor(AutomataUiTheme.COLOR_BG)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(6);

        window.add(buildHeaderBar());

        bodyRow = window.add(GuiPanel.row(8)
                .withHugContent(false)
                .withPercentWidth(-1.0F)
                .withAlignItems(FlexAlign.STRETCH));
        bodyRow.withFlex(1.0F, 1.0F).withMinHeight(110);

        bodyRow.add(buildSidebar());

        GuiPanel contentHost = bodyRow.add(GuiPanel.column(0)
                .withHugContent(false)
                .withPercentWidth(-1.0F)
                .withPadding(0)
                .withBackgroundColor(AutomataUiTheme.COLOR_SURFACE)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(4));
        contentHost.withFlex(1.0F, 1.0F);

        this.overviewTab = new OverviewTabView(
                this,
                container,
                bot,
                state,
                allGuiSlots,
                this::sendActionToServer,
                this::refreshSnapshot,
                this::syncAttachedBackpackDrawer);
        TasksLogisticsTabView tasksTab = new TasksLogisticsTabView(
                bot, state, this::sendActionToServer, this::refreshSnapshot);
        BehaviorSquadTabView squadTab = new BehaviorSquadTabView(
                state, this::sendActionToServer, this::refreshSnapshot);
        TelemetryTabView telemetryTab = new TelemetryTabView(
                bot, state, this::refreshSnapshot);

        registerTab(contentHost, overviewTab.getRoot());
        registerTab(contentHost, tasksTab.getRoot());
        registerTab(contentHost, squadTab.getRoot());
        registerTab(contentHost, telemetryTab.getRoot());

        attachSidePanel(DockSide.RIGHT, overviewTab.getBackpackDrawer(), 24, 0);

        state.bindProperties(container);
        window.bindState(state.healthState, v -> updateHeaderBars());
        window.bindState(state.maxHealthState, v -> updateHeaderBars());
        window.bindState(state.hungerState, v -> updateHeaderBars());
        window.bindState(state.loadState, v -> updateHeaderBars());
        window.bindState(state.jobOrdinalState, v -> updateHeaderStatus());
        window.bindState(state.downedState, v -> updateHeaderStatus());
        state.addSnapshotListener(() -> {
            updateHeaderStatus();
            updateHeaderBars();
        });

        selectTab(activeTabIndex);
        updateHeaderStatus();
        updateHeaderBars();
        refreshSnapshot();
    }

    @Override
    protected void onScreenResize(int newWidth, int newHeight) {
        if (overviewTab != null) {
            overviewTab.syncBackpackUi();
            syncAttachedBackpackDrawer();
        }
    }

    private void syncAttachedBackpackDrawer() {
        if (overviewTab == null) {
            return;
        }
        GuiPanel drawer = overviewTab.getBackpackDrawer();
        boolean showDrawer = activeTabIndex == 0 && drawer.isVisible();
        if (!showDrawer && drawer.isVisible()) {
            drawer.setVisible(false);
        }
        requestSidePanelLayout();
        layoutSidePanelsAndSync();
        if (showDrawer) {
            overviewTab.syncDrawerGridBounds();
            layoutSidePanelsAndSync();
        } else {
            overviewTab.hideBackpackSlots();
        }
    }

    private GuiPanel buildHeaderBar() {
        GuiPanel header = GuiPanel.row(6)
                .withHugContent(false)
                .withPercentWidth(1.0F)
                .withPadding(new Insets(0, 6, 0, 6))
                .withBackgroundColor(AutomataUiTheme.COLOR_SURFACE)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(4)
                .withAlignItems(FlexAlign.CENTER);
        header.withSize(0, 24);

        this.titleLabel = header.add(GuiLabel.of("AUTOMATON #" + bot.getEntityId(), AutomataUiTheme.COLOR_TEXT));
        this.statusBadge = header.add(GuiLabel.of("[IDLE]", AutomataUiTheme.COLOR_ACCENT));
        header.add(GuiPanel.spacer());

        this.healthBar = header.add(new StatBarComponent(58, 12, "HP", AutomataUiTheme.COLOR_GREEN),
                bar -> bar.withFlexShrink(1.0F).withMinWidth(36));
        this.hungerBar = header.add(new StatBarComponent(58, 12, "Food", AutomataUiTheme.COLOR_AMBER),
                bar -> bar.withFlexShrink(1.0F).withMinWidth(36));
        this.loadBar = header.add(new StatBarComponent(54, 12, "Load", AutomataUiTheme.COLOR_ACCENT),
                bar -> bar.withFlexShrink(1.0F).withMinWidth(34));
        return header;
    }

    private GuiPanel buildSidebar() {
        GuiPanel sidebar = GuiPanel.column(4)
                .withHugContent(false)
                .withPercentWidth(0.24F)
                .withPadding(6)
                .withBackgroundColor(AutomataUiTheme.COLOR_SURFACE)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(4)
                .withAlignItems(FlexAlign.CENTER);
        sidebar.withMinWidth(68);

        GuiPanel previewBox = sidebar.add(GuiPanel.column(2)
                .withHugContent(true)
                .withPercentWidth(1.0F)
                .withPadding(4)
                .withBackgroundColor(AutomataUiTheme.COLOR_TRACK)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(3)
                .withAlignItems(FlexAlign.CENTER));

        previewBox.add(new AutomatonPreviewRender(bot, 58), preview -> {
            preview.withPercentWidth(1.0F);
            preview.setScale(24.0F)
                    .setTranslation(0.0F, 22.0F, 0.0F)
                    .setEnableEntityAnimations(false)
                    .setLookAtCursor(true)
                    .setAllowDragRotation(true);
        });

        for (int i = 0; i < TAB_KEYS.length; i++) {
            final int tabIdx = i;
            String tabLabel = tr(TAB_KEYS[i], TAB_FALLBACKS[i]);
            GuiButton navBtn = sidebar.add(
                    GuiButton.of(tabLabel, () -> selectTab(tabIdx)).withVariant(GuiButton.Variant.GHOST),
                    btn -> btn.withSize(64, 18).withPercentWidth(1.0F));
            navButtons.add(navBtn);
        }
        return sidebar;
    }

    private void registerTab(GuiPanel host, GuiComponent view) {
        view.withPercentSize(1.0F, 1.0F);
        tabViews.add(view);
        host.add(view);
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabViews.size()) {
            return;
        }
        this.activeTabIndex = index;
        for (int i = 0; i < tabViews.size(); i++) {
            tabViews.get(i).setVisible(i == index);
        }
        for (int i = 0; i < navButtons.size(); i++) {
            navButtons.get(i).setSelected(i == index);
        }
        if (overviewTab != null) {
            if (index == 0) {
                overviewTab.syncBackpackUi();
            } else {
                overviewTab.getBackpackDrawer().setVisible(false);
                syncAttachedBackpackDrawer();
            }
        }
        if (window != null) {
            window.requestLayout();
        }
    }

    private void updateHeaderBars() {
        if (healthBar == null) {
            return;
        }
        float hp = state.healthState.get();
        float maxHp = Math.max(1.0F, state.maxHealthState.get());
        float food = state.hungerState.get();
        float load = state.loadState.get();

        healthBar.setValue(hp / maxHp, String.format("HP %.0f/%.0f", hp, maxHp));
        hungerBar.setValue(food / 20.0F, String.format("Food %.0f", food));
        loadBar.setValue(load, "Bag " + Math.round(load * 100.0F) + "%");
    }

    private void updateHeaderStatus() {
        if (statusBadge == null) {
            return;
        }
        if (titleLabel != null) {
            String customName = state.getAutomatonName();
            titleLabel.setText(customName == null || customName.isEmpty()
                    ? "AUTOMATON #" + bot.getEntityId()
                    : customName + " (#" + bot.getEntityId() + ")");
        }
        boolean downed = Boolean.TRUE.equals(state.downedState.get());
        int ord = state.jobOrdinalState.get() != null ? state.jobOrdinalState.get() : 0;
        String jobTitle = TasksLogisticsTabView.formatJobTitle(AutomataAPI.getJobRegistry().getByIndex(ord));
        if (downed) {
            statusBadge.setText("[" + tr("gui.automata.status.downed", "DOWNED") + "]");
            statusBadge.setColor(AutomataUiTheme.COLOR_RED);
        } else {
            statusBadge.setText("[" + jobTitle.toUpperCase() + "]");
            statusBadge.setColor(AutomataUiTheme.COLOR_ACCENT);
        }
    }

    private static String tr(String key, String fallback) {
        return net.minecraft.client.resources.I18n.hasKey(key)
                ? net.minecraft.client.resources.I18n.format(key)
                : fallback;
    }

    private void refreshSnapshot() {
        NBTTagCompound params = new NBTTagCompound();
        params.setInteger("entityId", bot.getEntityId());
        request("automata:get_snapshot", params, result -> {
            if (result.isOk()) {
                state.applySnapshot(result.getData());
            }
        });
    }

    private static final class AutomatonPreviewRender extends GuiModelRender {

        private static final int PREVIEW_OFFSET_Y = 22;

        private final EntityAutomaton bot;

        AutomatonPreviewRender(EntityAutomaton bot, int height) {
            super(0, 0, 56, height);
            this.bot = bot;
            setEntity(bot);
        }

        @Override
        protected void renderSelf(IGraphics gfx, int mouseX, int mouseY, float partialTicks) {
            int absX = getAbsoluteX();
            int absY = getAbsoluteY();
            if (width <= 0 || height <= 0) {
                return;
            }
            int savedHurt = bot.hurtTime;
            int savedDeath = bot.deathTime;
            bot.hurtTime = 0;
            bot.deathTime = 0;
            RenderAutomaton.setGuiPreviewRender(true);
            gfx.pushClip(absX, absY, width, height);
            try {
                super.renderSelf(gfx, mouseX, mouseY - PREVIEW_OFFSET_Y, partialTicks);
            } finally {
                gfx.popClip();
                RenderAutomaton.setGuiPreviewRender(false);
                bot.hurtTime = savedHurt;
                bot.deathTime = savedDeath;
            }
        }
    }
}
