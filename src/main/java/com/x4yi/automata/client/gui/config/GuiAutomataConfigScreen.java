package com.x4yi.automata.client.gui.config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.x4yi.x4ui.api.client.gui.IGraphics;
import com.x4yi.x4ui.client.gui.base.GuiBaseScreen;
import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiComponent;
import com.x4yi.x4ui.client.gui.component.GuiDropdown;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiMarkdown;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;
import com.x4yi.x4ui.client.gui.component.GuiTextInput;
import com.x4yi.x4ui.client.gui.component.GuiToggle;
import com.x4yi.x4ui.client.gui.component.layout.FlexAlign;
import com.x4yi.x4ui.client.gui.component.layout.FlexDirection;
import com.x4yi.x4ui.client.gui.component.slider.GuiSliderDouble;
import com.x4yi.x4ui.client.gui.component.slider.GuiSliderInt;
import com.x4yi.x4ui.client.gui.utils.Insets;
import com.x4yi.x4ui.common.State;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.config.ConfigGuiType;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.client.gui.AutomataUiTheme;

@SideOnly(Side.CLIENT)
public class GuiAutomataConfigScreen extends GuiBaseScreen {

    private final List<ConfigEntryModel> entries;
    private final State<String> searchQuery = new State<>("");
    private ConfigEntryModel selectedEntry;

    private GuiPanel window;
    private GuiPanel bodyRow;
    private GuiScrollPanel masterScroll;
    private GuiPanel masterList;
    private GuiPanel detailPanel;

    public GuiAutomataConfigScreen(GuiScreen parentScreen) {
        super(parentScreen, "Automata Config");
        this.entries = ConfigEntryModel.loadFromAutomataConfig();
        this.selectedEntry = entries.isEmpty() ? null : entries.get(0);
        setDimBackground(true);
    }

    @Override
    protected void initComponents() {
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

        GuiPanel masterFrame = bodyRow.add(GuiPanel.column(4)
                .withPercentWidth(0.38F)
                .withHugContent(false)
                .withPadding(4)
                .withBackgroundColor(AutomataUiTheme.COLOR_SURFACE)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(4));

        masterScroll = masterFrame.add(AutomataUiTheme.createTabScroll());
        masterList = masterScroll.add(GuiPanel.column(3).withHugContent(true).withPercentWidth(1.0F));

        detailPanel = bodyRow.add(GuiPanel.column(6)
                .withHugContent(false)
                .withPercentWidth(-1.0F)
                .withAlignItems(FlexAlign.STRETCH)
                .withPadding(8)
                .withBackgroundColor(AutomataUiTheme.COLOR_CARD)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(4));
        detailPanel.withFlex(1.0F, 1.0F);

        window.add(buildFooterBar());

        rebuildMasterList();
        rebuildDetailPanel();
    }

    private GuiPanel buildHeaderBar() {
        GuiPanel header = GuiPanel.row(8)
                .withHugContent(false)
                .withPercentWidth(1.0F)
                .withPadding(new Insets(0, 6, 0, 6))
                .withBackgroundColor(AutomataUiTheme.COLOR_SURFACE)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(4)
                .withAlignItems(FlexAlign.CENTER);
        header.withSize(0, 24);

        header.add(GuiLabel.of(tr("gui.automata.config.title", "AUTOMATA CONFIG"), AutomataUiTheme.COLOR_TEXT));
        header.add(GuiPanel.spacer());

        header.add(GuiTextInput.of(searchQuery), input -> {
            input.withSize(120, 16).withPercentWidth(0.38F);
            input.setPlaceholder(tr("gui.automata.config.search", "Search..."));
            input.setOnTextChanged(q -> rebuildMasterList());
        });
        return header;
    }

    private GuiPanel buildFooterBar() {
        GuiPanel footer = GuiPanel.row(6)
                .withHugContent(false)
                .withPercentWidth(1.0F)
                .withAlignItems(FlexAlign.CENTER);
        footer.withSize(0, 22);

        footer.add(GuiButton.of(tr("gui.automata.config.reset_all", "Reset All"), () -> {
            for (ConfigEntryModel entry : entries) {
                entry.resetToDefault();
            }
            rebuildMasterList();
            rebuildDetailPanel();
        }), btn -> btn.withSize(70, 18));
        footer.add(GuiPanel.spacer());
        footer.add(GuiButton.of(tr("gui.automata.config.cancel", "Cancel"), this::closeScreen),
                btn -> btn.withSize(64, 18));
        footer.add(GuiButton.of(tr("gui.automata.config.save_done", "Save & Done"), this::saveAndClose)
                .withVariant(GuiButton.Variant.PRIMARY),
                btn -> btn.withSize(82, 18));
        return footer;
    }

    private void rebuildMasterList() {
        if (masterList == null) {
            return;
        }
        masterList.clearChildren();
        String query = searchQuery.get();
        int currentWinW = window != null && window.getWidth() > 0 ? window.getWidth() : AutomataUiTheme.MAX_WINDOW_W;
        int approxColPx = Math.max(80, Math.round(currentWinW * 0.38F) - 24);
        ConfigEntryModel firstVisible = null;
        boolean selectedVisible = false;

        for (ConfigEntryModel entry : entries) {
            if (!entry.matchesFilter(query)) {
                continue;
            }
            if (firstVisible == null) {
                firstVisible = entry;
            }
            if (entry == selectedEntry) {
                selectedVisible = true;
            }
            boolean isSelected = entry == selectedEntry;
            String prefix = isSelected ? "> " : (entry.isDefaultValue() ? "" : "* ");
            int textColor = isSelected
                    ? AutomataUiTheme.COLOR_ACCENT
                    : (entry.isDefaultValue() ? AutomataUiTheme.COLOR_TEXT : AutomataUiTheme.COLOR_AMBER);
            int borderColor = isSelected ? AutomataUiTheme.COLOR_ACCENT : AutomataUiTheme.COLOR_BORDER;
            int bgColor = isSelected ? AutomataUiTheme.COLOR_CARD : AutomataUiTheme.COLOR_TRACK;

            masterList.add(new MarqueeButton(approxColPx, 18, prefix + entry.getName(), textColor, bgColor, borderColor, () -> {
                selectedEntry = entry;
                rebuildMasterList();
                rebuildDetailPanel();
            }), btn -> btn.withPercentWidth(1.0F));
        }
        if (!selectedVisible && firstVisible != null) {
            selectedEntry = firstVisible;
        }
        masterList.requestLayout();
        masterScroll.requestLayout();
    }

    private void rebuildDetailPanel() {
        if (detailPanel == null) {
            return;
        }
        detailPanel.clearChildren();
        if (selectedEntry == null) {
            detailPanel.add(GuiLabel.of(tr("gui.automata.config.none_selected", "No option selected."), AutomataUiTheme.COLOR_MUTED));
            detailPanel.requestLayout();
            return;
        }

        int currentWinW = window != null && window.getWidth() > 0 ? window.getWidth() : AutomataUiTheme.MAX_WINDOW_W;
        int approxDetailPx = Math.max(120, Math.round(currentWinW * 0.62F) - 34);
        ConfigEntryModel entry = selectedEntry;

        GuiPanel titleRow = detailPanel.add(GuiPanel.row(6)
                .withHugContent(false)
                .withPercentWidth(1.0F)
                .withAlignItems(FlexAlign.CENTER));
        titleRow.withSize(0, 20);
        titleRow.add(new MarqueeLabel(0, 14, entry.getName(), AutomataUiTheme.COLOR_TEXT),
                lbl -> lbl.withFlex(1.0F, 1.0F).withMinWidth(40));
        titleRow.add(GuiButton.of(tr("gui.automata.config.reset", "Reset"), () -> {
            entry.resetToDefault();
            rebuildMasterList();
            rebuildDetailPanel();
        }), btn -> btn.withSize(44, 16));

        GuiLabel statusLabel = detailPanel.add(GuiLabel.of(
                entry.getCategory() + "  •  " + (entry.isDefaultValue()
                        ? tr("gui.automata.config.default", "Default")
                        : tr("gui.automata.config.modified", "Modified")),
                entry.isDefaultValue() ? AutomataUiTheme.COLOR_MUTED : AutomataUiTheme.COLOR_AMBER),
                lbl -> lbl.withPercentWidth(1.0F));

        GuiPanel controlBox = detailPanel.add((entry.isList() ? GuiPanel.column(4) : GuiPanel.row(8))
                .withHugContent(false)
                .withPercentWidth(1.0F)
                .withAlignItems(entry.isList() ? FlexAlign.STRETCH : FlexAlign.CENTER)
                .withPadding(6)
                .withBackgroundColor(AutomataUiTheme.COLOR_SURFACE)
                .withBorder(1, AutomataUiTheme.COLOR_BORDER)
                .withCornerRadius(4));
        controlBox.withSize(0, entry.isList() ? 104 : 30);

        if (entry.isList()) {
            addListEditor(controlBox, entry);
        } else {
            addScalarControl(controlBox, entry, statusLabel);
        }

        String comment = entry.getComment();
        if (!comment.isEmpty()) {
            GuiScrollPanel commentScroll = detailPanel.add(GuiScrollPanel.vertical(40, 0));
            commentScroll.withPercentWidth(-1.0F).withFlex(1.0F, 1.0F).withMinHeight(24);
            commentScroll.add(new GuiMarkdown(0, 0, approxDetailPx, comment),
                    md -> md.withPercentWidth(1.0F));
        }

        detailPanel.requestLayout();
    }

    private static String tr(String key, String fallback) {
        return net.minecraft.client.resources.I18n.hasKey(key)
                ? net.minecraft.client.resources.I18n.format(key)
                : fallback;
    }

    private void updateStatusLabel(GuiLabel statusLabel, ConfigEntryModel entry) {
        statusLabel.setText(entry.getCategory() + "  •  " + (entry.isDefaultValue()
                ? tr("gui.automata.config.default", "Default")
                : tr("gui.automata.config.modified", "Modified")));
        statusLabel.setColor(entry.isDefaultValue() ? AutomataUiTheme.COLOR_MUTED : AutomataUiTheme.COLOR_AMBER);
    }

    private void addScalarControl(GuiPanel row, ConfigEntryModel entry, GuiLabel statusLabel) {
        IConfigElement element = entry.getElement();
        ConfigGuiType type = entry.getType();
        if (type == ConfigGuiType.BOOLEAN) {
            State<Boolean> state = new State<>((Boolean) entry.getDraftValue());
            row.add(GuiLabel.of(tr("gui.automata.config.enabled", "Enabled:"), AutomataUiTheme.COLOR_MUTED));
            row.add(GuiPanel.spacer());
            GuiToggle toggle = row.add(new GuiToggle(0, 0, 52, 18, "", state));
            toggle.bindState(state, val -> {
                entry.setDraftValue(val);
                updateStatusLabel(statusLabel, entry);
            });
            return;
        }
        if (type == ConfigGuiType.INTEGER) {
            int min = element.getMinValue() != null ? Integer.parseInt(element.getMinValue().toString()) : 0;
            int max = element.getMaxValue() != null ? Integer.parseInt(element.getMaxValue().toString()) : 2000;
            State<Integer> state = new State<>((Integer) entry.getDraftValue());
            GuiLabel valLabel = GuiLabel.of(String.valueOf(state.get()), AutomataUiTheme.COLOR_ACCENT);
            GuiSliderInt slider = row.add(GuiSliderInt.of(state, min, max, 1),
                    s -> s.withPercentWidth(-1.0F).withSize(0, 16).withFlex(1.0F, 1.0F).withMinWidth(40));
            slider.bindState(state, val -> {
                if (val != null) {
                    entry.setDraftValue(val);
                    valLabel.setText(String.valueOf(val));
                    updateStatusLabel(statusLabel, entry);
                }
            });
            row.add(valLabel);
            return;
        }
        if (type == ConfigGuiType.DOUBLE) {
            double min = element.getMinValue() != null ? Double.parseDouble(element.getMinValue().toString()) : 0.0D;
            double max = element.getMaxValue() != null ? Double.parseDouble(element.getMaxValue().toString()) : 2.0D;
            State<Double> state = new State<>((Double) entry.getDraftValue());
            GuiLabel valLabel = GuiLabel.of(String.format(Locale.ROOT, "%.2f", state.get()), AutomataUiTheme.COLOR_ACCENT);
            GuiSliderDouble slider = row.add(GuiSliderDouble.of(state, min, max, 0.01D),
                    s -> s.withPercentWidth(-1.0F).withSize(0, 16).withFlex(1.0F, 1.0F).withMinWidth(40));
            slider.bindState(state, val -> {
                if (val != null) {
                    entry.setDraftValue(val);
                    valLabel.setText(String.format(Locale.ROOT, "%.2f", val));
                    updateStatusLabel(statusLabel, entry);
                }
            });
            row.add(valLabel);
            return;
        }
        String[] valid = element.getValidValues();
        if (valid != null && valid.length > 0) {
            State<String> state = new State<>(String.valueOf(entry.getDraftValue()));
            GuiDropdown<String> dropdown = row.add(GuiDropdown.of(state, Arrays.asList(valid)),
                    dd -> dd.withSize(140, 18).withPercentWidth(1.0F));
            dropdown.bindState(state, val -> {
                entry.setDraftValue(val);
                updateStatusLabel(statusLabel, entry);
            });
            return;
        }
        State<String> state = new State<>(String.valueOf(entry.getDraftValue()));
        row.add(GuiTextInput.of(state), input -> {
            input.withSize(140, 18).withPercentWidth(1.0F);
            input.setOnTextChanged(val -> {
                entry.setDraftValue(val);
                updateStatusLabel(statusLabel, entry);
            });
        });
    }

    @SuppressWarnings("unchecked")
    private void addListEditor(GuiPanel container, ConfigEntryModel entry) {
        List<String> list = (List<String>) entry.getDraftValue();
        State<String> newItemState = new State<>("");
        GuiPanel addRow = container.add(GuiPanel.row(4)
                .withHugContent(false)
                .withPercentWidth(1.0F)
                .withAlignItems(FlexAlign.CENTER));
        addRow.withSize(0, 18);

        addRow.add(GuiTextInput.of(newItemState), input -> input.withPercentWidth(-1.0F).withSize(0, 16).withFlex(1.0F, 1.0F).withMinWidth(40));
        addRow.add(GuiButton.of("+", () -> {
            String candidate = newItemState.get().trim();
            if (!candidate.isEmpty() && !list.contains(candidate)) {
                list.add(candidate);
                newItemState.set("");
                rebuildMasterList();
                rebuildDetailPanel();
            }
        }), btn -> btn.withSize(24, 16));

        GuiScrollPanel itemsScroll = container.add(GuiScrollPanel.vertical(68, 2).withContentPadding(2));
        itemsScroll.withPercentWidth(-1.0F).withFlex(1.0F, 1.0F).withMinHeight(40);
        GuiPanel itemsCol = itemsScroll.add(GuiPanel.column(2).withHugContent(true).withPercentWidth(1.0F));
        for (int i = 0; i < list.size(); i++) {
            int idx = i;
            GuiPanel itemRow = itemsCol.add(GuiPanel.row(4)
                    .withHugContent(false)
                    .withPercentWidth(1.0F)
                    .withAlignItems(FlexAlign.CENTER));
            itemRow.withSize(0, 16);
            itemRow.add(new MarqueeLabel(0, 14, "• " + list.get(i), AutomataUiTheme.COLOR_MUTED),
                    lbl -> lbl.withFlex(1.0F, 1.0F).withMinWidth(40));
            itemRow.add(GuiButton.of("x", () -> {
                list.remove(idx);
                rebuildMasterList();
                rebuildDetailPanel();
            }), btn -> btn.withSize(18, 14));
        }
    }

    private void saveAndClose() {
        for (ConfigEntryModel entry : entries) {
            entry.applyToElement();
        }
        boolean isWorldRunning = Minecraft.getMinecraft().world != null;
        MinecraftForge.EVENT_BUS.post(new ConfigChangedEvent.OnConfigChangedEvent(
                AutomataMod.MOD_ID, null, isWorldRunning, false));
        closeScreen();
    }

    static float computeMarqueeOffset(int textWidth, int availableWidth, float elapsedSeconds) {
        if (textWidth <= availableWidth || availableWidth <= 0) {
            return 0.0F;
        }
        float overflow = (float) (textWidth - availableWidth);
        float pause = 1.0F;
        float speedPxPerSec = 26.0F;
        float scrollTime = overflow / speedPxPerSec;
        float cycle = pause + scrollTime + pause + scrollTime;
        float t = elapsedSeconds % cycle;
        if (t < pause) {
            return 0.0F;
        }
        t -= pause;
        if (t < scrollTime) {
            return (t / scrollTime) * overflow;
        }
        t -= scrollTime;
        if (t < pause) {
            return overflow;
        }
        t -= pause;
        return overflow * (1.0F - (t / scrollTime));
    }

    private static void drawClippedMarqueeText(
            IGraphics gfx,
            String text,
            int boxX,
            int boxY,
            int boxW,
            int boxH,
            int textY,
            int color,
            float elapsedSeconds) {
        int textW = gfx.getStringWidth(text);
        int pad = 4;
        int availW = Math.max(8, boxW - pad * 2);
        float offset = computeMarqueeOffset(textW, availW, elapsedSeconds);

        gfx.pushClip(boxX + pad, boxY, Math.max(1, availW), Math.max(1, boxH));
        try {
            gfx.drawString(text, (boxX + pad) - offset, textY, color, true);
        } finally {
            gfx.popClip();
        }
    }

    private static final class MarqueeLabel extends GuiComponent {
        private final String text;
        private final int color;
        private float elapsed = 0.0F;

        MarqueeLabel(int width, int height, String text, int color) {
            super(0, 0, width, height);
            this.text = text;
            this.color = color;
        }

        @Override
        public void update(float deltaTime) {
            super.update(deltaTime);
            this.elapsed += Math.max(0.001F, Math.min(0.05F, deltaTime));
        }

        @Override
        protected void renderSelf(IGraphics gfx, int mouseX, int mouseY, float partialTicks) {
            int absX = getAbsoluteX();
            int absY = getAbsoluteY();
            drawClippedMarqueeText(gfx, text, absX, absY, width, height, absY + Math.max(0, (height - 8) / 2), color, elapsed);
        }
    }

    private static final class MarqueeButton extends GuiComponent {
        private final String text;
        private final int textColor;
        private final int bgColor;
        private final int borderColor;
        private final Runnable onClick;
        private float elapsed = 0.0F;

        MarqueeButton(int width, int height, String text, int textColor, int bgColor, int borderColor, Runnable onClick) {
            super(0, 0, width, height);
            this.text = text;
            this.textColor = textColor;
            this.bgColor = bgColor;
            this.borderColor = borderColor;
            this.onClick = onClick;
        }

        @Override
        public void update(float deltaTime) {
            super.update(deltaTime);
            this.elapsed += Math.max(0.001F, Math.min(0.05F, deltaTime));
        }

        @Override
        protected void renderSelf(IGraphics gfx, int mouseX, int mouseY, float partialTicks) {
            int absX = getAbsoluteX();
            int absY = getAbsoluteY();
            boolean hovered = isMouseOver(mouseX, mouseY);
            int bg = hovered ? AutomataUiTheme.COLOR_CARD : bgColor;
            int border = hovered ? AutomataUiTheme.COLOR_ACCENT : borderColor;
            gfx.drawRoundedRect(absX, absY, width, height, 3, bg);
            gfx.drawRoundedBorder(absX, absY, width, height, 3, 1, border);
            drawClippedMarqueeText(gfx, text, absX, absY, width, height, absY + Math.max(0, (height - 8) / 2), textColor, elapsed);
        }

        @Override
        public boolean onMouseClick(int mouseX, int mouseY, int button) {
            if (button == 0 && isMouseOver(mouseX, mouseY)) {
                if (onClick != null) {
                    onClick.run();
                }
                return true;
            }
            return false;
        }
    }
}
