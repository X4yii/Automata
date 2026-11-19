package com.x4yi.automata.client.gui;

import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.layout.FlexAlign;
import com.x4yi.x4ui.impl.client.gui.GLGraphics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.item.AutomaStaffMode;
import com.x4yi.automata.item.DevStaffMode;
import com.x4yi.automata.item.IStaffMode;
import com.x4yi.automata.item.ItemAutomaDevStaff;
import com.x4yi.automata.item.ItemAutomaStaff;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID, value = Side.CLIENT)
public final class AutomaStaffHudOverlay {

    private static final int HUD_WIDTH = 122;
    private static final int HUD_HEIGHT = 54;
    private static final int HUD_MARGIN = 10;
    private static final int SIDE_PILL_WIDTH = 88;
    private static final int ACTIVE_PILL_WIDTH = 118;
    private static final int SIDE_PILL_HEIGHT = 14;
    private static final int ACTIVE_PILL_HEIGHT = 18;
    private static final int SLOT_STEP_Y = 17;

    private static final int[] RENDER_ORDER = {-1, 1, 0};
    private static final GuiPanel[] PILLS = new GuiPanel[RENDER_ORDER.length];
    private static final GuiLabel[] PILL_LABELS = new GuiLabel[RENDER_ORDER.length];
    private static boolean initialized = false;

    private static AutomaStaffMode activeMode = AutomaStaffMode.SELECT_ZONE;
    private static DevStaffMode activeDevMode = DevStaffMode.DUPLICATE_BOT;
    private static float scrollOffset = 0.0F;
    private static long lastFrameNanos = 0L;

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        for (int i = 0; i < RENDER_ORDER.length; i++) {
            GuiPanel pill = GuiPanel.row(4)
                    .withHugContent(false)
                    .withPercentWidth(-1.0F)
                    .withAlignItems(FlexAlign.CENTER)
                    .withCornerRadius(3)
                    .withPadding(3);
            GuiLabel label = pill.add(GuiLabel.of("", AutomataUiTheme.COLOR_TEXT));
            PILLS[i] = pill;
            PILL_LABELS[i] = label;
        }
        initialized = true;
    }

    private AutomaStaffHudOverlay() {}

    public static AutomaStaffMode getActiveMode() {
        return activeMode;
    }

    public static void setActiveMode(AutomaStaffMode mode) {
        if (mode != null) {
            activeMode = mode;
        }
    }

    public static DevStaffMode getActiveDevMode() {
        return activeDevMode;
    }

    public static void cycleMode(int delta) {
        int step = delta >= 0 ? 1 : -1;
        activeMode = activeMode.cycle(step);
        scrollOffset = MathHelper.clamp(scrollOffset + step, -1.25F, 1.25F);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        tag.setString("staffMode", activeMode.name());
        com.x4yi.x4ui.common.network.UINetwork.sendActionToServer("automata:set_staff_mode", tag);
    }

    public static void cycleDevMode(int delta) {
        int step = delta >= 0 ? 1 : -1;
        activeDevMode = activeDevMode.cycle(step);
        scrollOffset = MathHelper.clamp(scrollOffset + step, -1.25F, 1.25F);
    }

    @SubscribeEvent
    public static void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.gameSettings.hideGUI) {
            return;
        }
        ItemStack held = mc.player.getHeldItemMainhand();
        boolean isStaff = !held.isEmpty() && held.getItem() instanceof ItemAutomaStaff;
        boolean isDevStaff = !held.isEmpty() && held.getItem() instanceof ItemAutomaDevStaff;
        if (!isStaff && !isDevStaff) {
            lastFrameNanos = 0L;
            return;
        }
        IStaffMode currentMode = isDevStaff ? activeDevMode : activeMode;

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0.016F : MathHelper.clamp((now - lastFrameNanos) / 1.0E9F, 0.001F, 0.05F);
        lastFrameNanos = now;
        if (Math.abs(scrollOffset) > 0.002F) {
            scrollOffset *= (float) Math.exp(-7.5F * dt);
        } else {
            scrollOffset = 0.0F;
        }

        ScaledResolution sr = event.getResolution();
        int baseX = sr.getScaledWidth() - HUD_WIDTH - HUD_MARGIN;
        int baseY = sr.getScaledHeight() - HUD_HEIGHT - HUD_MARGIN;
        int centerY = baseY + (HUD_HEIGHT - ACTIVE_PILL_HEIGHT) / 2;

        ensureInitialized();
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GLGraphics gfx = GLGraphics.INSTANCE;
        gfx.reset();

        for (int idx = 0; idx < RENDER_ORDER.length; idx++) {
            int rel = RENDER_ORDER[idx];
            IStaffMode mode = currentMode.cycle(rel);
            float visualSlot = rel + scrollOffset;
            float linearDist = Math.min(1.0F, Math.abs(visualSlot));
            float smoothDist = linearDist * linearDist * (3.0F - 2.0F * linearDist);
            float focusAmount = 1.0F - smoothDist;

            int pillW = Math.round(SIDE_PILL_WIDTH + (ACTIVE_PILL_WIDTH - SIDE_PILL_WIDTH) * focusAmount);
            int pillH = Math.round(SIDE_PILL_HEIGHT + (ACTIVE_PILL_HEIGHT - SIDE_PILL_HEIGHT) * focusAmount);
            int pillX = baseX + (HUD_WIDTH - pillW);
            int pillY = Math.round(centerY + visualSlot * SLOT_STEP_Y + (ACTIVE_PILL_HEIGHT - pillH) * 0.5F);

            int bgColor = lerpColor(AutomataUiTheme.COLOR_SURFACE, AutomataUiTheme.COLOR_CARD, focusAmount);
            int borderColor = lerpColor(AutomataUiTheme.COLOR_BORDER, AutomataUiTheme.COLOR_ACCENT, focusAmount);
            int textColor = lerpColor(AutomataUiTheme.COLOR_MUTED, AutomataUiTheme.COLOR_TEXT, focusAmount);

            GuiPanel pill = PILLS[idx];
            GuiLabel label = PILL_LABELS[idx];

            pill.withBackgroundColor(bgColor)
                    .withBorder(1, borderColor)
                    .withPosition(pillX, pillY)
                    .withSize(pillW, pillH);

            String labelText = net.minecraft.client.resources.I18n.hasKey(mode.getTranslationKey())
                    ? net.minecraft.client.resources.I18n.format(mode.getTranslationKey())
                    : mode.getDisplayName();
            String text = (focusAmount > 0.65F ? "> " : "") + labelText;
            label.setText(text);
            label.setColor(textColor);
            pill.update(dt);
            pill.render(gfx, -1, -1, event.getPartialTicks());
        }

        GlStateManager.popMatrix();
    }

    static int lerpColor(int c0, int c1, float t) {
        float k = MathHelper.clamp(t, 0.0F, 1.0F);
        int a0 = (c0 >>> 24) & 0xFF;
        int r0 = (c0 >>> 16) & 0xFF;
        int g0 = (c0 >>> 8) & 0xFF;
        int b0 = c0 & 0xFF;
        int a1 = (c1 >>> 24) & 0xFF;
        int r1 = (c1 >>> 16) & 0xFF;
        int g1 = (c1 >>> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int a = Math.round(a0 + (a1 - a0) * k);
        int r = Math.round(r0 + (r1 - r0) * k);
        int g = Math.round(g0 + (g1 - g0) * k);
        int b = Math.round(b0 + (b1 - b0) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
