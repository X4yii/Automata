package com.x4yi.automata.client.gui.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.x4yi.x4ui.api.client.gui.IGraphics;
import com.x4yi.x4ui.client.gui.base.GuiBaseScreen;
import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiComponent;
import com.x4yi.x4ui.client.gui.component.GuiDropdown;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;
import com.x4yi.x4ui.client.gui.component.GuiTextInput;
import com.x4yi.x4ui.client.gui.component.GuiToggle;
import com.x4yi.x4ui.client.gui.component.layout.FlexAlign;
import com.x4yi.x4ui.client.gui.component.layout.FlexDirection;
import com.x4yi.x4ui.client.gui.component.slider.GuiSlider;
import com.x4yi.x4ui.client.gui.component.slider.GuiSliderDouble;
import com.x4yi.x4ui.client.gui.utils.Insets;
import com.x4yi.x4ui.common.State;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.config.ConfigGuiType;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.client.gui.AutomataUiTheme;

/**
 * Responsive Master-Detail X4UI configuration screen backed natively by Forge {@link IConfigElement}.
 */
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
    private int winW;
    private int winH;

    public GuiAutomataConfigScreen(GuiScreen parentScreen) {
        super(parentScreen, "Automata Config");
        this.entries = ConfigEntryModel.loadFromAutomataConfig();
        this.selectedEntry = entries.isEmpty() ? null : entries.get(0);
        setDimBackground(true);
    }

    private void computeWindowDimensions() {
        this.winW = Math.min(AutomataUiTheme.MAX_WINDOW_W, Math.max(AutomataUiTheme.MIN_WINDOW_W, (int) (this.width * 0.90F)));
        this.winH = Math.min(AutomataUiTheme.MAX_WINDOW_H, Math.max(AutomataUiTheme.MIN_WINDOW_H, (int) (this.height * 0.90F)));
    }

    @Override
    protected void initComponents() {
        computeWindowDimensions();
        int left = (this.width - winW) / 2;
        int top = (this.height - winH) / 2;

        window = new GuiPanel(left, top, winW, winH);
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
        bodyRow.withPercentWidth(1.0F);
        bodyRow.setHeight(Math.max(110, winH - 74));
        bodyRow.setAlignItems(FlexAlign.STRETCH);

        // Left: Master list (38% relative width)
        GuiPanel masterFrame = GuiPanel.column(4);
        masterFrame.withPercentSize(0.38F, 1.0F);
        masterFrame.setHugContent(false);
        masterFrame.setPadding(new Insets(4));
        masterFrame.setBackgroundColor(AutomataUiTheme.COLOR_SURFACE);
        masterFrame.setBorderColor(AutomataUiTheme.COLOR_BORDER);
        masterFrame.setBorderWidth(1);
        masterFrame.setCornerRadius(4);

        masterScroll = AutomataUiTheme.createTabScroll();
        masterList = GuiPanel.column(3).withHugContent(true);
        masterList.withPercentWidth(1.0F);
        masterScroll.addChild(masterList);
        masterFrame.addChild(masterScroll);
        bodyRow.addChild(masterFrame);

        // Right: Detail inspector (62% relative width)
        detailPanel = GuiPanel.column(6);
        detailPanel.withPercentSize(0.62F, 1.0F);
        detailPanel.setHugContent(false);
        detailPanel.setPadding(new Insets(8));
        detailPanel.setBackgroundColor(AutomataUiTheme.COLOR_CARD);
        detailPanel.setBorderColor(AutomataUiTheme.COLOR_BORDER);
        detailPanel.setBorderWidth(1);
        detailPanel.setCornerRadius(4);
        bodyRow.addChild(detailPanel);

        window.addChild(bodyRow);
        window.addChild(buildFooterBar());

        addComponent(window);

        rebuildMasterList();
        rebuildDetailPanel();
    }

    @Override
    protected void onScreenResize(int newWidth, int newHeight) {
        computeWindowDimensions();
        if (window != null) {
            window.setX((newWidth - winW) / 2);
            window.setY((newHeight - winH) / 2);
            window.setWidth(winW);
            window.setHeight(winH);
            if (bodyRow != null) {
                bodyRow.setHeight(Math.max(110, winH - 74));
            }
            window.requestLayout();
            rebuildMasterList();
            rebuildDetailPanel();
        }
    }

    private GuiPanel buildHeaderBar() {
        GuiPanel header = GuiPanel.row(8);
        header.setHugContent(false);
        header.withPercentWidth(1.0F);
        header.setHeight(24);
        header.setPadding(new Insets(0, 6, 0, 6));
        header.setBackgroundColor(AutomataUiTheme.COLOR_SURFACE);
        header.setBorderColor(AutomataUiTheme.COLOR_BORDER);
        header.setBorderWidth(1);
        header.setCornerRadius(4);
        header.setAlignItems(FlexAlign.CENTER);

        header.addChild(new GuiLabel(0, 0, tr("gui.automata.config.title", "AUTOMATA CONFIG"), AutomataUiTheme.COLOR_TEXT));
        header.addChild(GuiPanel.spacer());

        GuiTextInput searchInput = new GuiTextInput(0, 0, 120, 16, searchQuery);
        searchInput.withPercentWidth(0.38F);
        searchInput.setOnTextChanged(q -> rebuildMasterList());
        header.addChild(searchInput);
        return header;
    }

    private GuiPanel buildFooterBar() {
        GuiPanel footer = GuiPanel.row(6);
        footer.setHugContent(false);
        footer.withPercentWidth(1.0F);
        footer.setHeight(22);
        footer.setAlignItems(FlexAlign.CENTER);

        footer.addChild(new GuiButton(0, 0, 70, 18, tr("gui.automata.config.reset_all", "Reset All"), () -> {
            for (ConfigEntryModel entry : entries) {
                entry.resetToDefault();
            }
            rebuildMasterList();
            rebuildDetailPanel();
        }));
        footer.addChild(GuiPanel.spacer());
        footer.addChild(new GuiButton(0, 0, 64, 18, tr("gui.automata.config.cancel", "Cancel"), this::closeScreen));
        footer.addChild(new GuiButton(0, 0, 82, 18, tr("gui.automata.config.save_done", "Save & Done"), this::saveAndClose));
        return footer;
    }

    private void rebuildMasterList() {
        if (masterList == null) {
            return;
        }
        masterList.clearChildren();
        String query = searchQuery.get();
        int approxColPx = Math.max(80, Math.round(winW * 0.38F) - 24);
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

            MarqueeButton rowBtn = new MarqueeButton(approxColPx, 18, prefix + entry.getName(), textColor, bgColor, borderColor, () -> {
                selectedEntry = entry;
                rebuildMasterList();
                rebuildDetailPanel();
            });
            rowBtn.withPercentWidth(1.0F);
            masterList.addChild(rowBtn);
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
            detailPanel.addChild(new GuiLabel(0, 0, tr("gui.automata.config.none_selected", "No option selected."), AutomataUiTheme.COLOR_MUTED));
            detailPanel.requestLayout();
            return;
        }

        int approxDetailPx = Math.max(120, Math.round(winW * 0.62F) - 34);
        ConfigEntryModel entry = selectedEntry;

        // Title + Reset row
        GuiPanel titleRow = GuiPanel.row(6);
        titleRow.setHugContent(false);
        titleRow.withPercentWidth(1.0F);
        titleRow.setHeight(20);
        titleRow.setAlignItems(FlexAlign.CENTER);
        MarqueeLabel titleLabel = new MarqueeLabel(Math.max(60, approxDetailPx - 52), 14, entry.getName(), AutomataUiTheme.COLOR_TEXT);
        titleLabel.withPercentWidth(0.76F);
        titleRow.addChild(titleLabel);
        titleRow.addChild(GuiPanel.spacer());
        titleRow.addChild(new GuiButton(0, 0, 44, 16, tr("gui.automata.config.reset", "Reset"), () -> {
            entry.resetToDefault();
            rebuildMasterList();
            rebuildDetailPanel();
        }));
        detailPanel.addChild(titleRow);

        // Status badge
        GuiLabel statusLabel = new GuiLabel(
                0, 0,
                entry.getCategory() + "  •  " + (entry.isDefaultValue()
                        ? tr("gui.automata.config.default", "Default")
                        : tr("gui.automata.config.modified", "Modified")),
                entry.isDefaultValue() ? AutomataUiTheme.COLOR_MUTED : AutomataUiTheme.COLOR_AMBER);
        statusLabel.withPercentWidth(1.0F);
        detailPanel.addChild(statusLabel);

        // Editor control box
        GuiPanel controlBox = entry.isList() ? GuiPanel.column(4) : GuiPanel.row(8);
        controlBox.setHugContent(false);
        controlBox.withPercentWidth(1.0F);
        controlBox.setHeight(entry.isList() ? 104 : 30);
        controlBox.setAlignItems(entry.isList() ? FlexAlign.START : FlexAlign.CENTER);
        controlBox.setPadding(new Insets(6));
        controlBox.setBackgroundColor(AutomataUiTheme.COLOR_SURFACE);
        controlBox.setBorderColor(AutomataUiTheme.COLOR_BORDER);
        controlBox.setBorderWidth(1);
        controlBox.setCornerRadius(4);

        if (entry.isList()) {
            addListEditor(controlBox, entry, approxDetailPx - 14);
        } else {
            addScalarControl(controlBox, entry, statusLabel);
        }
        detailPanel.addChild(controlBox);

        // Wrapped full comment
        String comment = entry.getComment();
        if (!comment.isEmpty() && this.mc != null && this.mc.fontRenderer != null) {
            List<String> lines = this.mc.fontRenderer.listFormattedStringToWidth(comment, approxDetailPx);
            int maxLines = Math.min(lines.size(), 6);
            for (int i = 0; i < maxLines; i++) {
                GuiLabel line = new GuiLabel(0, 0, lines.get(i), AutomataUiTheme.COLOR_MUTED);
                line.withPercentWidth(1.0F);
                detailPanel.addChild(line);
            }
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
            state.addListener(val -> {
                entry.setDraftValue(val);
                updateStatusLabel(statusLabel, entry);
            });
            row.addChild(new GuiLabel(0, 0, tr("gui.automata.config.enabled", "Enabled:"), AutomataUiTheme.COLOR_MUTED));
            row.addChild(GuiPanel.spacer());
            row.addChild(new GuiToggle(0, 0, 52, 18, "", state));
            return;
        }
        if (type == ConfigGuiType.INTEGER) {
            int min = element.getMinValue() != null ? Integer.parseInt(element.getMinValue().toString()) : 0;
            int max = element.getMaxValue() != null ? Integer.parseInt(element.getMaxValue().toString()) : 2000;
            State<Integer> state = new State<>((Integer) entry.getDraftValue());
            GuiLabel valLabel = new GuiLabel(0, 0, String.valueOf(state.get()), AutomataUiTheme.COLOR_ACCENT);
            state.addListener(val -> {
                entry.setDraftValue(val);
                valLabel.setText(String.valueOf(val));
                updateStatusLabel(statusLabel, entry);
            });
            GuiIntSlider slider = new GuiIntSlider(0, 0, 120, 16, state, min, max);
            slider.withPercentWidth(0.75F);
            row.addChild(slider);
            row.addChild(GuiPanel.spacer());
            row.addChild(valLabel);
            return;
        }
        if (type == ConfigGuiType.DOUBLE) {
            double min = element.getMinValue() != null ? Double.parseDouble(element.getMinValue().toString()) : 0.0D;
            double max = element.getMaxValue() != null ? Double.parseDouble(element.getMaxValue().toString()) : 2.0D;
            State<Double> state = new State<>((Double) entry.getDraftValue());
            GuiLabel valLabel = new GuiLabel(0, 0, String.format(Locale.ROOT, "%.2f", state.get()), AutomataUiTheme.COLOR_ACCENT);
            state.addListener(val -> {
                entry.setDraftValue(val);
                valLabel.setText(String.format(Locale.ROOT, "%.2f", val));
                updateStatusLabel(statusLabel, entry);
            });
            GuiSliderDouble slider = new GuiSliderDouble(0, 0, 120, 16, state, min, max, 0.01D);
            slider.withPercentWidth(0.75F);
            row.addChild(slider);
            row.addChild(GuiPanel.spacer());
            row.addChild(valLabel);
            return;
        }
        String[] valid = element.getValidValues();
        if (valid != null && valid.length > 0) {
            State<String> state = new State<>(String.valueOf(entry.getDraftValue()));
            state.addListener(val -> {
                entry.setDraftValue(val);
                updateStatusLabel(statusLabel, entry);
            });
            GuiDropdown<String> dropdown = new GuiDropdown<>(0, 0, 140, 18, state, Arrays.asList(valid));
            dropdown.withPercentWidth(1.0F);
            row.addChild(dropdown);
            return;
        }
        State<String> state = new State<>(String.valueOf(entry.getDraftValue()));
        GuiTextInput input = new GuiTextInput(0, 0, 140, 18, state);
        input.withPercentWidth(1.0F);
        input.setOnTextChanged(val -> {
            entry.setDraftValue(val);
            updateStatusLabel(statusLabel, entry);
        });
        row.addChild(input);
    }

    @SuppressWarnings("unchecked")
    private void addListEditor(GuiPanel container, ConfigEntryModel entry, int approxPx) {
        List<String> list = (List<String>) entry.getDraftValue();
        State<String> newItemState = new State<>("");
        GuiPanel addRow = GuiPanel.row(4);
        addRow.setHugContent(false);
        addRow.withPercentWidth(1.0F);
        addRow.setHeight(18);
        addRow.setAlignItems(FlexAlign.CENTER);

        GuiTextInput addInput = new GuiTextInput(0, 0, 100, 16, newItemState);
        addInput.withPercentWidth(0.82F);
        addRow.addChild(addInput);
        addRow.addChild(GuiPanel.spacer());
        addRow.addChild(new GuiButton(0, 0, 24, 16, "+", () -> {
            String candidate = newItemState.get().trim();
            if (!candidate.isEmpty() && !list.contains(candidate)) {
                list.add(candidate);
                newItemState.set("");
                rebuildMasterList();
                rebuildDetailPanel();
            }
        }));
        container.addChild(addRow);

        GuiScrollPanel itemsScroll = AutomataUiTheme.createTabScroll();
        itemsScroll.withPercentWidth(1.0F);
        itemsScroll.setHeight(68);
        GuiPanel itemsCol = GuiPanel.column(2).withHugContent(true);
        itemsCol.withPercentWidth(1.0F);
        for (int i = 0; i < list.size(); i++) {
            int idx = i;
            GuiPanel itemRow = GuiPanel.row(4);
            itemRow.setHugContent(false);
            itemRow.withPercentWidth(1.0F);
            itemRow.setHeight(16);
            itemRow.setAlignItems(FlexAlign.CENTER);
            itemRow.addChild(new GuiLabel(0, 0, trimToWidth("• " + list.get(i), approxPx - 36), AutomataUiTheme.COLOR_MUTED));
            itemRow.addChild(GuiPanel.spacer());
            itemRow.addChild(new GuiButton(0, 0, 18, 14, "x", () -> {
                list.remove(idx);
                rebuildMasterList();
                rebuildDetailPanel();
            }));
            itemsCol.addChild(itemRow);
        }
        itemsScroll.addChild(itemsCol);
        container.addChild(itemsScroll);
    }

    private String trimToWidth(String text, int maxPx) {
        if (text == null || maxPx <= 8) {
            return "";
        }
        if (this.mc != null && this.mc.fontRenderer != null) {
            if (this.mc.fontRenderer.getStringWidth(text) <= maxPx) {
                return text;
            }
            return this.mc.fontRenderer.trimStringToWidth(text, Math.max(8, maxPx - 8)) + "..";
        }
        return text;
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

    private static final class GuiIntSlider extends GuiSlider<Integer> {

        GuiIntSlider(int x, int y, int width, int height, State<Integer> state, int min, int max) {
            super(x, y, width, height, state, min, max, 1);
        }

        @Override
        protected double getPercentage(Integer value) {
            if (max <= min) {
                return 0.0D;
            }
            return MathHelper.clamp((double) (value - min) / (double) (max - min), 0.0D, 1.0D);
        }

        @Override
        protected Integer getValueFromPercentage(double pct) {
            return MathHelper.clamp((int) Math.round(min + pct * (max - min)), min, max);
        }
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

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);
        int scale = sr.getScaleFactor();
        boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
                (boxX + pad) * scale,
                mc.displayHeight - (boxY + boxH) * scale,
                Math.max(1, availW * scale),
                Math.max(1, boxH * scale));
        gfx.drawString(text, (boxX + pad) - offset, textY, color, true);
        if (!wasScissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
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
