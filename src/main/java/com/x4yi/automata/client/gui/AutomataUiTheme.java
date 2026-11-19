package com.x4yi.automata.client.gui;

import com.x4yi.x4ui.api.client.gui.IGraphics;
import com.x4yi.x4ui.client.gui.component.GuiComponent;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;

import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class AutomataUiTheme {

    public static final int MAX_WINDOW_W = 480;
    public static final int MAX_WINDOW_H = 300;
    public static final int MIN_WINDOW_W = 300;
    public static final int MIN_WINDOW_H = 200;

    public static final int COLOR_BG = 0xF41E1E1E;
    public static final int COLOR_SURFACE = 0xFF252526;
    public static final int COLOR_CARD = 0xFF2B2B2D;
    public static final int COLOR_BORDER = 0xFF3F3F46;
    public static final int COLOR_TRACK = 0xFF18181B;
    public static final int COLOR_ACCENT = 0xFF60A5FA;
    public static final int COLOR_GREEN = 0xFF4ADE80;
    public static final int COLOR_AMBER = 0xFFFBBF24;
    public static final int COLOR_RED = 0xFFF87171;
    public static final int COLOR_TEXT = 0xFFF4F4F5;
    public static final int COLOR_MUTED = 0xFFA1A1AA;

    private AutomataUiTheme() {
    }

    public static GuiPanel makeCard(String title, int gap) {
        GuiPanel card = GuiPanel.card(6, gap)
                .withBackgroundColor(COLOR_CARD)
                .withCornerRadius(4)
                .withBorder(1, COLOR_BORDER)
                .withHugContent(true)
                .withPercentWidth(1.0F);
        card.add(GuiLabel.of(title, COLOR_TEXT), heading -> heading.withPercentWidth(1.0F));
        return card;
    }

    public static GuiScrollPanel createTabScroll() {
        GuiScrollPanel scroll = GuiScrollPanel.vertical(200, 6);
        scroll.withPercentSize(1.0F, 1.0F);
        scroll.withContentPadding(6);
        return scroll;
    }

    public static String formatPos(BlockPos pos) {
        if (pos == null) {
            return "Unset";
        }
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static final class StatBarComponent extends GuiComponent {

        private final int barColor;
        private float ratio = 1.0F;
        private float animatedRatio = 1.0F;
        private String label;

        public StatBarComponent(int width, int height, String label, int barColor) {
            super(0, 0, width, height);
            this.label = label;
            this.barColor = barColor;
        }

        public void setValue(float ratio, String label) {
            this.ratio = Math.max(0.0F, Math.min(1.0F, ratio));
            this.label = label;
        }

        @Override
        public void update(float deltaTime) {
            super.update(deltaTime);
            float speed = Math.min(1.0F, 10.0F * Math.max(0.016F, deltaTime));
            animatedRatio += (ratio - animatedRatio) * speed;
        }

        @Override
        protected void renderSelf(IGraphics gfx, int mouseX, int mouseY, float partialTicks) {
            int absX = getAbsoluteX();
            int absY = getAbsoluteY();
            gfx.drawRoundedRect(absX, absY, width, height, 3, COLOR_TRACK);
            int fillW = Math.round((width - 2) * animatedRatio);
            if (fillW > 0) {
                gfx.drawRoundedRect(absX + 1, absY + 1, fillW, height - 2, 2, barColor);
            }
            gfx.drawStringCentered(label, absX + width / 2.0F, absY + (height - gfx.getFontHeight()) / 2.0F + 1, COLOR_TEXT, false);
        }
    }
}
