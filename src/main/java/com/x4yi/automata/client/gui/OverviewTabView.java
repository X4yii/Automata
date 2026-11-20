
package com.x4yi.automata.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import com.x4yi.x4ui.client.gui.base.GuiBaseContainer;
import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiGridPanel;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;
import com.x4yi.x4ui.client.gui.component.GuiSlot;
import com.x4yi.x4ui.client.gui.component.layout.FlexAlign;
import com.x4yi.x4ui.client.gui.component.layout.FlexDirection;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.compat.UsefulBackpacksCompat;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.gui.ContainerAutomaton;

@SideOnly(Side.CLIENT)
public final class OverviewTabView {

    private final GuiBaseContainer screen;
    private final ContainerAutomaton container;
    private final List<GuiSlot> allGuiSlots;
    private final List<GuiSlot> backpackGuiSlots = new ArrayList<>();

    private final GuiScrollPanel root;
    private final GuiLabel backpackLabel;
    private final GuiButton backpackToggleBtn;
    private final GuiPanel backpackDrawer;
    private final GuiLabel backpackSideTitle;
    private final GuiGridPanel backpackGrid;
    private final Runnable onDrawerChanged;

    private boolean backpackOpen = false;
    private int renderedSlots = -1;

    public OverviewTabView(
            GuiBaseContainer screen,
            ContainerAutomaton container,
            EntityAutomaton bot,
            AutomatonGuiState state,
            List<GuiSlot> allGuiSlots,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot,
            Runnable onDrawerChanged) {
        this.screen = screen;
        this.container = container;
        this.allGuiSlots = allGuiSlots;
        this.onDrawerChanged = onDrawerChanged;
        this.root = AutomataUiTheme.createTabScroll();

        GuiPanel gearCard = AutomataUiTheme.makeCard(tr("gui.automata.overview.loadout", "Loadout & Equipment"), 4);
        GuiPanel armorRow = GuiPanel.row(2)
                .withHugContent(true)
                .withAlignItems(FlexAlign.CENTER);
        armorRow.withSize(0, 18);
        for (int i = 0; i < 4; i++) {
            GuiSlot slot = armorRow.add(GuiSlot.of(container.getSlot(i), screen));
            allGuiSlots.add(slot);
        }
        gearCard.add(armorRow);

        gearCard.add(GuiLabel.of(tr("gui.automata.overview.hands_backpack", "Mainhand / Offhand / Backpack"), AutomataUiTheme.COLOR_MUTED));
        GuiPanel handsRow = GuiPanel.row(4)
                .withHugContent(true)
                .withAlignItems(FlexAlign.CENTER);
        handsRow.withSize(0, 18);
        for (int i = 4; i <= 6; i++) {
            GuiSlot slot = handsRow.add(GuiSlot.of(container.getSlot(i), screen));
            allGuiSlots.add(slot);
        }
        this.backpackToggleBtn = handsRow.add(GuiButton.of(tr("gui.automata.overview.backpack", "Backpack"), () -> {
            backpackOpen = !backpackOpen;
            syncBackpackUi();
        }), btn -> btn.withSize(68, 16).withVisible(false));
        gearCard.add(handsRow);
        this.backpackLabel = gearCard.add(GuiLabel.of(state.getBackpackSummary(), AutomataUiTheme.COLOR_MUTED));
        gearCard.add(GuiLabel.of(tr("gui.automata.overview.baubles", "Accessories (Baubles)"), AutomataUiTheme.COLOR_MUTED));
        GuiPanel baublesRow = GuiPanel.row(2)
                .withHugContent(true)
                .withAlignItems(FlexAlign.CENTER);
        baublesRow.withSize(0, 18);
        for (int i = 0; i < ContainerAutomaton.BAUBLE_SLOT_COUNT; i++) {
            GuiSlot slot = baublesRow.add(GuiSlot.of(container.getSlot(ContainerAutomaton.BAUBLE_SLOT_START + i), screen));
            allGuiSlots.add(slot);
        }
        gearCard.add(baublesRow);
        root.add(gearCard);

        this.backpackDrawer = new GuiPanel(0, 0, 159, 220)
                .withHugContent(false)
                .withFlexDirection(FlexDirection.VERTICAL)
                .withAlignItems(FlexAlign.STRETCH)
                .withGap(4)
                .withPadding(8)
                .withBackgroundColor(AutomataUiTheme.COLOR_BG)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(6);
        this.backpackDrawer.withFlexShrink(1.0F).withMinWidth(4 * 19 + 26).withVisible(false);
        this.backpackSideTitle = this.backpackDrawer.add(GuiLabel.of(tr("gui.automata.overview.backpack", "Backpack"), AutomataUiTheme.COLOR_TEXT));
        this.backpackGrid = this.backpackDrawer.add(GuiGridPanel.slotGrid(180, 1).withAutoColumns());
        this.backpackGrid.withPercentWidth(-1.0F).withFlex(1.0F, 1.0F).withMinHeight(40);

        GuiPanel storageCard = AutomataUiTheme.makeCard(tr("gui.automata.overview.bot_hotbar", "Bot Hotbar (9)"), 4);
        addSlotGrid(screen, container, allGuiSlots, storageCard, 7, 1, 9);
        storageCard.add(GuiLabel.of(tr("gui.automata.overview.bot_storage", "Bot Storage (18)"), AutomataUiTheme.COLOR_MUTED));
        addSlotGrid(screen, container, allGuiSlots, storageCard, 16, 2, 9);
        storageCard.add(GuiLabel.of(tr("gui.automata.overview.player_inventory", "Player Inventory"), AutomataUiTheme.COLOR_MUTED));
        addSlotGrid(screen, container, allGuiSlots, storageCard, 34, 3, 9);
        addSlotGrid(screen, container, allGuiSlots, storageCard, 61, 1, 9);
        root.add(storageCard);

        state.addSnapshotListener(() -> {
            backpackLabel.setText(state.getBackpackSummary());
            syncBackpackUi();
        });
        syncBackpackUi();
    }

    public void syncBackpackUi() {
        ItemStack equipped = container.getSlot(6).getStack();
        boolean hasBackpack = !equipped.isEmpty() && UsefulBackpacksCompat.isUsefulBackpack(equipped);
        backpackToggleBtn.setVisible(hasBackpack);
        backpackToggleBtn.setSelected(hasBackpack && backpackOpen);
        if (!hasBackpack || !backpackOpen) {
            if (!hasBackpack) {
                backpackOpen = false;
            }
            backpackDrawer.setVisible(false);
            hideBackpackSlots();
            onDrawerChanged.run();
            return;
        }
        int[] dims = UsefulBackpacksCompat.getBackpackDimensions(equipped);
        int cols = Math.max(1, dims[0]);
        int totalSlots = Math.min(container.getMaxBackpackSlots(), Math.max(1, dims[2]));
        backpackSideTitle.setText("Backpack (" + totalSlots + ")");
        if (totalSlots != renderedSlots) {
            rebuildBackpackGrid(totalSlots);
        }
        int cappedCols = Math.min(7, Math.max(4, cols));
        int desiredW = cappedCols * 19 + 26;
        int neededRows = (totalSlots + cappedCols - 1) / cappedCols;
        int desiredH = Math.min(256, Math.max(110, neededRows * 19 + 36));
        backpackDrawer.setWidth(desiredW);
        backpackDrawer.setHeight(desiredH);
        backpackDrawer.setVisible(true);
        onDrawerChanged.run();
    }

    public void syncDrawerGridBounds() {
        if (!backpackDrawer.isVisible()) {
            hideBackpackSlots();
            return;
        }
        backpackDrawer.requestLayout();
        backpackDrawer.update(0.0F);
        backpackGrid.layoutGrid();
        int shownCols = backpackGrid.getComputedColumns();
        int shownRows = (Math.max(1, renderedSlots) + shownCols - 1) / shownCols;
        backpackSideTitle.setText("Backpack " + shownCols + "x" + shownRows + " (" + renderedSlots + ")");
    }

    private void rebuildBackpackGrid(int totalSlots) {
        allGuiSlots.removeAll(backpackGuiSlots);
        backpackGuiSlots.clear();
        backpackGrid.clearChildren();
        for (int i = 0; i < totalSlots; i++) {
            GuiSlot slot = backpackGrid.add(GuiSlot.of(container.getSlot(ContainerAutomaton.BACKPACK_SLOT_START + i), screen));
            backpackGuiSlots.add(slot);
            allGuiSlots.add(slot);
        }
        for (int i = totalSlots; i < container.getMaxBackpackSlots(); i++) {
            net.minecraft.inventory.Slot hidden = container.getSlot(ContainerAutomaton.BACKPACK_SLOT_START + i);
            hidden.xPos = -9999;
            hidden.yPos = -9999;
        }
        renderedSlots = totalSlots;
    }

    public void hideBackpackSlots() {
        for (int i = 0; i < container.getMaxBackpackSlots(); i++) {
            net.minecraft.inventory.Slot hidden = container.getSlot(ContainerAutomaton.BACKPACK_SLOT_START + i);
            hidden.xPos = -9999;
            hidden.yPos = -9999;
        }
    }

    public GuiPanel getBackpackDrawer() {
        return backpackDrawer;
    }

    private static void addSlotGrid(
            GuiBaseContainer screen,
            ContainerAutomaton container,
            List<GuiSlot> allGuiSlots,
            GuiPanel parent,
            int startIdx,
            int rows,
            int cols) {
        GuiPanel grid = GuiPanel.column(1).withHugContent(true);
        for (int r = 0; r < rows; r++) {
            GuiPanel row = GuiPanel.row(1).withHugContent(true);
            row.withSize(0, 18);
            for (int c = 0; c < cols; c++) {
                GuiSlot slot = row.add(GuiSlot.of(container.getSlot(startIdx + r * cols + c), screen));
                allGuiSlots.add(slot);
            }
            grid.add(row);
        }
        parent.add(grid);
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
