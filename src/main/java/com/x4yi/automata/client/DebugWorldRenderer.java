package com.x4yi.automata.client;

import java.util.Map;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

import org.lwjgl.opengl.GL11;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.client.debug.VoxelOutline;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.coordination.SquadBlackboard;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.item.ItemAutomaDevStaff;
import com.x4yi.automata.navigation.PackedPos;
import com.x4yi.automata.navigation.PathNodeAction;
import com.x4yi.automata.navigation.PathResult;
import com.x4yi.x4ui.common.network.UINetwork;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID, value = Side.CLIENT)
public final class DebugWorldRenderer {

    private static final double MAX_DEBUG_DISTANCE_SQ = 4096.0D;
    private static final int MAX_DEBUG_BREAK_BLOCKS = 64;
    private static final float GOLDEN_RATIO_CONJUGATE = 0.61803398875F;
    private static final long REMOTE_SYNC_INTERVAL_MS = 250L;

    private static final long[] BREAK_TARGET_SCRATCH = new long[MAX_DEBUG_BREAK_BLOCKS];
    private static final float[] COLOR_SCRATCH = new float[3];

    private static final Int2ObjectOpenHashMap<RemoteBotDebugSnapshot> REMOTE_BOT_SNAPSHOTS =
            new Int2ObjectOpenHashMap<>();
    private static long[] remoteReservationPositions = new long[0];
    private static int[] remoteReservationOwners = new int[0];
    private static long nextSyncAtMs = 0L;
    private static boolean syncInFlight = false;

    private DebugWorldRenderer() {}

    @SubscribeEvent
    public static void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        clearRemoteCache();
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() != null && event.getWorld().isRemote) {
            clearRemoteCache();
        }
    }

    private static void clearRemoteCache() {
        REMOTE_BOT_SNAPSHOTS.clear();
        remoteReservationPositions = new long[0];
        remoteReservationOwners = new int[0];
        nextSyncAtMs = 0L;
        syncInFlight = false;
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!AutomataConfig.debugMode) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if (player == null || mc.world == null) {
            return;
        }
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !(held.getItem() instanceof ItemAutomaDevStaff)) {
            return;
        }
        IntegratedServer server = mc.getIntegratedServer();
        WorldServer serverWorld = server != null ? server.getWorld(player.dimension) : null;
        if (serverWorld == null) {
            maybeRequestRemoteSync();
        }

        double camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * event.getPartialTicks();
        double camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * event.getPartialTicks();
        double camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * event.getPartialTicks();

        GlStateManager.pushMatrix();
        GlStateManager.translate(-camX, -camY, -camZ);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);

        // Pass 1: depth-tested full alpha for crisp foreground geometry
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        renderDebugScene(mc, player, serverWorld, event.getPartialTicks(), 1.0F);

        // Pass 2: occluded silhouettes at 25% alpha through terrain
        GlStateManager.disableDepth();
        renderDebugScene(mc, player, serverWorld, event.getPartialTicks(), 0.25F);

        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static void renderDebugScene(
            Minecraft mc,
            EntityPlayer player,
            @javax.annotation.Nullable WorldServer serverWorld,
            float partialTicks,
            float alphaScale) {
        SquadBlackboard sharedBlackboard = null;

        for (Entity entity : mc.world.loadedEntityList) {
            if (!(entity instanceof EntityAutomaton clientBot) || clientBot.isDead) {
                continue;
            }
            if (player.getDistanceSq(clientBot) > MAX_DEBUG_DISTANCE_SQ) {
                continue;
            }

            computeBotColor(clientBot.getEntityId(), COLOR_SCRATCH);
            float r = COLOR_SCRATCH[0];
            float g = COLOR_SCRATCH[1];
            float b = COLOR_SCRATCH[2];

            renderBotOutline(clientBot, partialTicks, r, g, b, alphaScale);

            PathResult path = null;
            int breakCount = 0;

            if (serverWorld != null) {
                Entity serverEntity = serverWorld.getEntityByID(clientBot.getEntityId());
                if (!(serverEntity instanceof EntityAutomaton bot) || bot.isDead) {
                    continue;
                }
                if (sharedBlackboard == null) {
                    sharedBlackboard = bot.getBrain().getContext().getBlackboard();
                }
                path = bot.getMovementController().getActivePath();
                IAutomatonJob job = bot.getBrain().getActiveJob();
                if (job != null) {
                    breakCount = job.copyPlannedBreakTargets(BREAK_TARGET_SCRATCH, MAX_DEBUG_BREAK_BLOCKS);
                }
            } else {
                RemoteBotDebugSnapshot snapshot = REMOTE_BOT_SNAPSHOTS.get(clientBot.getEntityId());
                if (snapshot != null) {
                    path = snapshot.path;
                    breakCount = Math.min(snapshot.breakTargets.length, MAX_DEBUG_BREAK_BLOCKS);
                    if (breakCount > 0) {
                        System.arraycopy(snapshot.breakTargets, 0, BREAK_TARGET_SCRATCH, 0, breakCount);
                    }
                }
            }

            long headTarget = breakCount > 0 ? BREAK_TARGET_SCRATCH[0] : Long.MIN_VALUE;
            if (path != null && !path.isEmpty()) {
                renderPath(clientBot, partialTicks, path, headTarget, r, g, b, alphaScale);
            }
            if (breakCount > 0) {
                renderPlannedBreakBlocks(BREAK_TARGET_SCRATCH, breakCount, r, g, b, alphaScale);
            }
        }

        if (serverWorld != null) {
            if (sharedBlackboard != null) {
                renderReservations(player, sharedBlackboard, alphaScale);
            }
        } else {
            renderRemoteReservations(player, alphaScale);
        }
    }

    private static void maybeRequestRemoteSync() {
        long now = System.currentTimeMillis();
        if (syncInFlight || now < nextSyncAtMs || !UINetwork.isServerSupported()) {
            return;
        }
        syncInFlight = true;
        nextSyncAtMs = now + REMOTE_SYNC_INTERVAL_MS;
        UINetwork.request("automata:debug_sync", null, result -> {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                syncInFlight = false;
                if (result != null && result.isOk() && result.getData() != null) {
                    applyRemoteDebugPayload(result.getData());
                }
            });
        });
    }

    private static void applyRemoteDebugPayload(NBTTagCompound root) {
        REMOTE_BOT_SNAPSHOTS.clear();
        NBTTagList bots = root.getTagList("bots", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < bots.tagCount(); i++) {
            NBTTagCompound botTag = bots.getCompoundTagAt(i);
            int entityId = botTag.getInteger("id");
            PathResult path = null;
            if (botTag.hasKey("steps", Constants.NBT.TAG_BYTE_ARRAY)
                    && botTag.hasKey("actions", Constants.NBT.TAG_BYTE_ARRAY)) {
                long[] steps = unpackLongs(botTag.getByteArray("steps"));
                byte[] actions = botTag.getByteArray("actions");
                if (steps.length > 0 && steps.length == actions.length) {
                    path = new PathResult(steps, actions, true, 0, 0L);
                }
            }
            long[] breaks = botTag.hasKey("breaks", Constants.NBT.TAG_BYTE_ARRAY)
                    ? unpackLongs(botTag.getByteArray("breaks"))
                    : new long[0];
            REMOTE_BOT_SNAPSHOTS.put(entityId, new RemoteBotDebugSnapshot(path, breaks));
        }

        if (root.hasKey("resPos", Constants.NBT.TAG_BYTE_ARRAY)
                && root.hasKey("resOwner", Constants.NBT.TAG_INT_ARRAY)) {
            long[] pos = unpackLongs(root.getByteArray("resPos"));
            int[] owners = root.getIntArray("resOwner");
            if (pos.length == owners.length) {
                remoteReservationPositions = pos;
                remoteReservationOwners = owners;
            } else {
                remoteReservationPositions = new long[0];
                remoteReservationOwners = new int[0];
            }
        } else {
            remoteReservationPositions = new long[0];
            remoteReservationOwners = new int[0];
        }
    }

    private static void renderRemoteReservations(EntityPlayer player, float alphaScale) {
        if (remoteReservationPositions.length == 0) {
            return;
        }
        Int2ObjectOpenHashMap<LongOpenHashSet> byOwner = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < remoteReservationPositions.length; i++) {
            long packed = remoteReservationPositions[i];
            int x = PackedPos.unpackX(packed);
            int y = PackedPos.unpackY(packed);
            int z = PackedPos.unpackZ(packed);
            if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > MAX_DEBUG_DISTANCE_SQ) {
                continue;
            }
            int owner = remoteReservationOwners[i];
            LongOpenHashSet set = byOwner.get(owner);
            if (set == null) {
                set = new LongOpenHashSet();
                byOwner.put(owner, set);
            }
            set.add(packed);
        }
        renderGroupedReservationVolumes(byOwner, alphaScale);
    }

    private static long[] unpackLongs(byte[] raw) {
        int count = raw.length / 8;
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            int base = i * 8;
            out[i] = ((long) (raw[base] & 0xFF) << 56)
                    | ((long) (raw[base + 1] & 0xFF) << 48)
                    | ((long) (raw[base + 2] & 0xFF) << 40)
                    | ((long) (raw[base + 3] & 0xFF) << 32)
                    | ((long) (raw[base + 4] & 0xFF) << 24)
                    | ((long) (raw[base + 5] & 0xFF) << 16)
                    | ((long) (raw[base + 6] & 0xFF) << 8)
                    | (long) (raw[base + 7] & 0xFF);
        }
        return out;
    }

    private static final class RemoteBotDebugSnapshot {
        final PathResult path;
        final long[] breakTargets;

        RemoteBotDebugSnapshot(PathResult path, long[] breakTargets) {
            this.path = path;
            this.breakTargets = breakTargets;
        }
    }

    /**
     * Generates a deterministic, vivid RGB color per entity ID using golden-ratio hue spacing.
     */
    static void computeBotColor(int entityId, float[] outRgb) {
        // Offset so low IDs start in bright warm yellow/gold and spread evenly across all bots.
        float hue = (0.14F + Math.abs(entityId) * GOLDEN_RATIO_CONJUGATE) % 1.0F;
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.90F, 1.0F);
        outRgb[0] = ((rgb >> 16) & 0xFF) / 255.0F;
        outRgb[1] = ((rgb >> 8) & 0xFF) / 255.0F;
        outRgb[2] = (rgb & 0xFF) / 255.0F;
    }

    private static void renderBotOutline(EntityAutomaton bot, float partialTicks, float r, float g, float b, float alphaScale) {
        double ix = bot.lastTickPosX + (bot.posX - bot.lastTickPosX) * partialTicks - bot.posX;
        double iy = bot.lastTickPosY + (bot.posY - bot.lastTickPosY) * partialTicks - bot.posY;
        double iz = bot.lastTickPosZ + (bot.posZ - bot.lastTickPosZ) * partialTicks - bot.posZ;
        AxisAlignedBB box = bot.getEntityBoundingBox().offset(ix, iy, iz).grow(0.05D);

        GlStateManager.glLineWidth(2.4F);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        emitBoxLines(buf, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, r, g, b, 0.95F * alphaScale);
        tess.draw();
    }

    private static void renderPath(
            EntityAutomaton bot,
            float partialTicks,
            PathResult path,
            long headTarget,
            float r,
            float g,
            float b,
            float alphaScale) {
        double botX = bot.lastTickPosX + (bot.posX - bot.lastTickPosX) * partialTicks;
        double botY = bot.lastTickPosY + (bot.posY - bot.lastTickPosY) * partialTicks + 0.15D;
        double botZ = bot.lastTickPosZ + (bot.posZ - bot.lastTickPosZ) * partialTicks;

        GlStateManager.glLineWidth(2.2F);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        buf.pos(botX, botY, botZ).color(r, g, b, 0.55F * alphaScale).endVertex();

        for (int i = 0; i < path.length(); i++) {
            long step = path.getStep(i);
            double x = PackedPos.unpackX(step) + 0.5D;
            double y = PackedPos.unpackY(step) + 0.22D;
            double z = PackedPos.unpackZ(step) + 0.5D;
            byte action = path.getAction(i);
            float sr = r;
            float sg = g;
            float sb = b;
            if (action == PathNodeAction.PILLAR_UP || action == PathNodeAction.BRIDGE) {
                sr = 1.0F;
                sg = 0.85F;
                sb = 0.20F;
            } else if (action == PathNodeAction.BREAK_THROUGH) {
                sr = 1.0F;
                sg = 0.30F;
                sb = 0.25F;
            } else if (action == PathNodeAction.SPRINT_JUMP) {
                sr = 0.30F;
                sg = 0.95F;
                sb = 1.0F;
            } else if (action == PathNodeAction.FLY || action == PathNodeAction.GLIDE_DOWN) {
                sr = 0.75F;
                sg = 0.45F;
                sb = 1.0F;
            }
            buf.pos(x, y, z).color(sr, sg, sb, 0.92F * alphaScale).endVertex();
        }
        if (headTarget != Long.MIN_VALUE) {
            buf.pos(
                    PackedPos.unpackX(headTarget) + 0.5D,
                    PackedPos.unpackY(headTarget) + 0.5D,
                    PackedPos.unpackZ(headTarget) + 0.5D)
                    .color(r, g, b, 0.65F * alphaScale)
                    .endVertex();
        }
        tess.draw();
    }

    private static void renderPlannedBreakBlocks(
            long[] packedTargets,
            int count,
            float r,
            float g,
            float b,
            float alphaScale) {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        // Highlight active target (index 0) with a slightly thicker outline.
        long head = packedTargets[0];
        double hx = PackedPos.unpackX(head);
        double hy = PackedPos.unpackY(head);
        double hz = PackedPos.unpackZ(head);
        GlStateManager.glLineWidth(2.6F);
        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        emitBoxLines(buf, hx - 0.01D, hy - 0.01D, hz - 0.01D, hx + 1.01D, hy + 1.01D, hz + 1.01D, r, g, b, 0.98F * alphaScale);
        tess.draw();

        if (count <= 1) {
            return;
        }
        LongOpenHashSet cells = new LongOpenHashSet(count);
        for (int i = 0; i < count; i++) {
            cells.add(packedTargets[i]);
        }
        GlStateManager.glLineWidth(1.6F);
        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        float edgeAlpha = 0.72F * alphaScale;
        VoxelOutline.collectExteriorEdges(cells, (x1, y1, z1, x2, y2, z2) -> {
            buf.pos(x1, y1, z1).color(r, g, b, edgeAlpha).endVertex();
            buf.pos(x2, y2, z2).color(r, g, b, edgeAlpha).endVertex();
        });
        tess.draw();
    }

    private static void renderReservations(EntityPlayer player, SquadBlackboard blackboard, float alphaScale) {
        Map<Long, SquadBlackboard.BlockReservation> snapshot = blackboard.getActiveReservationsSnapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        Int2ObjectOpenHashMap<LongOpenHashSet> byOwner = new Int2ObjectOpenHashMap<>();
        for (Map.Entry<Long, SquadBlackboard.BlockReservation> entry : snapshot.entrySet()) {
            long packed = entry.getKey();
            int x = PackedPos.unpackX(packed);
            int y = PackedPos.unpackY(packed);
            int z = PackedPos.unpackZ(packed);
            if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > MAX_DEBUG_DISTANCE_SQ) {
                continue;
            }
            int owner = entry.getValue().ownerEntityId();
            LongOpenHashSet set = byOwner.get(owner);
            if (set == null) {
                set = new LongOpenHashSet();
                byOwner.put(owner, set);
            }
            set.add(packed);
        }
        renderGroupedReservationVolumes(byOwner, alphaScale);
    }

    private static void renderGroupedReservationVolumes(
            Int2ObjectOpenHashMap<LongOpenHashSet> byOwner,
            float alphaScale) {
        if (byOwner.isEmpty()) {
            return;
        }
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GlStateManager.glLineWidth(1.5F);
        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        float alpha = 0.55F * alphaScale;
        for (Int2ObjectMap.Entry<LongOpenHashSet> entry : byOwner.int2ObjectEntrySet()) {
            computeBotColor(entry.getIntKey(), COLOR_SCRATCH);
            float r = COLOR_SCRATCH[0];
            float g = COLOR_SCRATCH[1];
            float b = COLOR_SCRATCH[2];
            VoxelOutline.collectExteriorEdges(entry.getValue(), (x1, y1, z1, x2, y2, z2) -> {
                buf.pos(x1, y1, z1).color(r, g, b, alpha).endVertex();
                buf.pos(x2, y2, z2).color(r, g, b, alpha).endVertex();
            });
        }
        tess.draw();
    }

    private static void emitBoxLines(
            BufferBuilder buf,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            float r,
            float g,
            float b,
            float a) {
        // Bottom face
        buf.pos(minX, minY, minZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, minY, minZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, minY, minZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, minY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, minY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(minX, minY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(minX, minY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(minX, minY, minZ).color(r, g, b, a).endVertex();
        // Top face
        buf.pos(minX, maxY, minZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, maxY, minZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, maxY, minZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, maxY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, maxY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(minX, maxY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(minX, maxY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(minX, maxY, minZ).color(r, g, b, a).endVertex();
        // Vertical edges
        buf.pos(minX, minY, minZ).color(r, g, b, a).endVertex();
        buf.pos(minX, maxY, minZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, minY, minZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, maxY, minZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, minY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(maxX, maxY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(minX, minY, maxZ).color(r, g, b, a).endVertex();
        buf.pos(minX, maxY, maxZ).color(r, g, b, a).endVertex();
    }
}
