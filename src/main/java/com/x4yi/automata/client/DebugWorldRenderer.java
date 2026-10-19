
package com.x4yi.automata.client;

import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import org.lwjgl.opengl.GL11;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.coordination.SquadBlackboard;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.navigation.PackedPos;
import com.x4yi.automata.navigation.PathResult;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID, value = Side.CLIENT)
public final class DebugWorldRenderer {

    private DebugWorldRenderer() {}

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!AutomataConfig.debugMode) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        IntegratedServer server = mc.getIntegratedServer();
        if (player == null || server == null) {
            return;
        }
        WorldServer serverWorld = server.getWorld(player.dimension);
        if (serverWorld == null) {
            return;
        }

        double camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * event.getPartialTicks();
        double camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * event.getPartialTicks();
        double camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * event.getPartialTicks();

        GlStateManager.pushMatrix();
        GlStateManager.translate(-camX, -camY, -camZ);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableDepth();
        GlStateManager.glLineWidth(2.0F);

        for (Entity entity : serverWorld.loadedEntityList) {
            if (!(entity instanceof EntityAutomaton bot)) {
                continue;
            }
            PathResult path = bot.getMovementController().getActivePath();
            if (path != null && !path.isEmpty()) {
                renderPath(path);
            }
            renderReservations(bot.getBrain().getContext().getBlackboard());
        }

        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static void renderPath(PathResult path) {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < path.length(); i++) {
            long step = path.getStep(i);
            double x = PackedPos.unpackX(step) + 0.5D;
            double y = PackedPos.unpackY(step) + 0.2D;
            double z = PackedPos.unpackZ(step) + 0.5D;
            buf.pos(x, y, z).color(0.1F, 0.9F, 0.2F, 0.9F).endVertex();
        }
        tess.draw();
    }

    private static void renderReservations(SquadBlackboard blackboard) {
        for (Map.Entry<Long, SquadBlackboard.BlockReservation> entry : blackboard.getActiveReservationsSnapshot().entrySet()) {
            long packed = entry.getKey();
            int x = PackedPos.unpackX(packed);
            int y = PackedPos.unpackY(packed);
            int z = PackedPos.unpackZ(packed);
            AxisAlignedBB box = new AxisAlignedBB(x, y, z, x + 1, y + 1, z + 1);
            RenderGlobal.drawSelectionBoundingBox(box, 0.0F, 0.75F, 1.0F, 0.85F);
        }
    }
}

