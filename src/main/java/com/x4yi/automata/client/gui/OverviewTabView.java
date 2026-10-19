/*
 * Copyright (c) 2026 x4yi
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.x4yi.automata.client.gui;

import java.util.List;
import java.util.function.BiConsumer;

import com.x4yi.x4ui.client.gui.base.GuiBaseContainer;
import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiModelRender;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;
import com.x4yi.x4ui.client.gui.component.GuiSlot;
import com.x4yi.x4ui.client.gui.component.GuiVirtualList;
import com.x4yi.x4ui.client.gui.component.layout.FlexAlign;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.client.gui.AutomatonGuiState.BackpackItemEntry;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.gui.ContainerAutomaton;

/**
 * Inventory tab presenting 3D preview, equipment loadout, backpack contents, and inventory grids.
 */
@SideOnly(Side.CLIENT)
public final class OverviewTabView {

    private final GuiScrollPanel root;
    private final GuiLabel backpackLabel;
    private final GuiPanel backpackCard;
    private final GuiVirtualList<BackpackItemEntry> backpackList;

    public OverviewTabView(
            GuiBaseContainer screen,
            ContainerAutomaton container,
            EntityAutomaton bot,
            AutomatonGuiState state,
            List<GuiSlot> allGuiSlots,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        this.root = AutomataUiTheme.createTabScroll();

        GuiPanel gearCard = AutomataUiTheme.makeCard("Loadout & Equipment", 4);
        GuiPanel previewRow = GuiPanel.row(8);
        previewRow.setHugContent(true);
        previewRow.setAlignItems(FlexAlign.CENTER);

        GuiPanel armorCol = GuiPanel.column(2);
        armorCol.setHugContent(false);
        armorCol.setWidth(18);
        armorCol.setHeight(78);
        for (int i = 0; i < 4; i++) {
            GuiSlot slot = new GuiSlot(container.getSlot(i), screen);
            allGuiSlots.add(slot);
            armorCol.addChild(slot);
        }
        previewRow.addChild(armorCol);

        GuiModelRender preview = new GuiModelRender(0, 0, 68, 78)
                .setEntity(bot)
                .setScale(28.0F)
                .setTranslation(0.0F, 24.0F, 0.0F)
                .setLookAtCursor(true)
                .setAllowDragRotation(true);
        previewRow.addChild(preview);
        gearCard.addChild(previewRow);

        gearCard.addChild(new GuiLabel(0, 0, "Mainhand / Offhand / Backpack", AutomataUiTheme.COLOR_MUTED));
        GuiPanel handsRow = GuiPanel.row(4);
        handsRow.setHugContent(true);
        handsRow.setHeight(18);
        handsRow.setAlignItems(FlexAlign.CENTER);
        for (int i = 4; i <= 6; i++) {
            GuiSlot slot = new GuiSlot(container.getSlot(i), screen);
            allGuiSlots.add(slot);
            handsRow.addChild(slot);
        }
        this.backpackLabel = new GuiLabel(0, 0, state.getBackpackSummary(), AutomataUiTheme.COLOR_MUTED);
        handsRow.addChild(backpackLabel);
        gearCard.addChild(handsRow);
        root.addChild(gearCard);

        this.backpackCard = AutomataUiTheme.makeCard("Backpack Contents (Click to Extract)", 4);
        this.backpackList = (GuiVirtualList<BackpackItemEntry>) new GuiVirtualList<BackpackItemEntry>(
                0, 0, 0, 60, 18,
                entry -> new GuiButton(0, 0, 0, 16, "", () -> {}).withPercentWidth(1.0F),
                (comp, entry) -> {
                    GuiButton btn = (GuiButton) comp;
                    btn.setText("[" + entry.count + "x] " + entry.name);
                    btn.setOnClick(() -> {
                        NBTTagCompound data = new NBTTagCompound();
                        data.setInteger("slot", entry.slot);
                        sendAction.accept("automata:backpack_extract", data);
                        refreshSnapshot.run();
                    });
                }).withPercentWidth(1.0F);
        backpackCard.addChild(backpackList);
        backpackCard.setVisible(false);
        root.addChild(backpackCard);

        GuiPanel storageCard = AutomataUiTheme.makeCard("Bot Hotbar (9)", 4);
        addSlotGrid(screen, container, allGuiSlots, storageCard, 7, 1, 9);
        storageCard.addChild(new GuiLabel(0, 0, "Bot Storage (18)", AutomataUiTheme.COLOR_MUTED));
        addSlotGrid(screen, container, allGuiSlots, storageCard, 16, 2, 9);
        storageCard.addChild(new GuiLabel(0, 0, "Player Inventory", AutomataUiTheme.COLOR_MUTED));
        addSlotGrid(screen, container, allGuiSlots, storageCard, 34, 3, 9);
        addSlotGrid(screen, container, allGuiSlots, storageCard, 61, 1, 9);
        root.addChild(storageCard);

        state.addSnapshotListener(() -> {
            backpackLabel.setText(state.getBackpackSummary());
            List<BackpackItemEntry> items = state.getBackpackItems();
            backpackList.setItems(items);
            backpackCard.setVisible(!items.isEmpty());
        });
    }

    private static void addSlotGrid(
            GuiBaseContainer screen,
            ContainerAutomaton container,
            List<GuiSlot> allGuiSlots,
            GuiPanel parent,
            int startIdx,
            int rows,
            int cols) {
        GuiPanel grid = GuiPanel.column(1);
        grid.setHugContent(true);
        for (int r = 0; r < rows; r++) {
            GuiPanel row = GuiPanel.row(1);
            row.setHugContent(true);
            row.setHeight(18);
            for (int c = 0; c < cols; c++) {
                GuiSlot slot = new GuiSlot(container.getSlot(startIdx + r * cols + c), screen);
                allGuiSlots.add(slot);
                row.addChild(slot);
            }
            grid.addChild(row);
        }
        parent.addChild(grid);
    }

    public GuiScrollPanel getRoot() {
        return root;
    }
}
