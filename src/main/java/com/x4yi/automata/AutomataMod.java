
package com.x4yi.automata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.x4yi.x4ui.common.network.ActionGuard;
import com.x4yi.x4ui.common.network.RpcResult;
import com.x4yi.x4ui.common.network.UINetwork;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;

import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.ai.jobs.MineJob;
import com.x4yi.automata.command.AutomatonTeleportHandler;
import com.x4yi.automata.command.CommandAutomata;
import com.x4yi.automata.coordination.SquadRegistry;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.gui.AutomatonGuiHandler;
import com.x4yi.automata.gui.ContainerAutomaton;
import com.x4yi.automata.item.ItemAutomaStaff;
import com.x4yi.automata.navigation.PathfindingExecutor;
import com.x4yi.automata.world.AutomatonCampGenerator;
import com.x4yi.automata.world.AutomatonChunkLoader;

@Mod(
        modid = AutomataMod.MOD_ID,
        name = AutomataMod.MOD_NAME,
        version = AutomataMod.VERSION,
        dependencies = "required-after:x4ui@[r1.0b7];after:waila;after:hwyla;after:jei",
        guiFactory = "com.x4yi.automata.client.gui.config.AutomataGuiFactory"
)
public class AutomataMod {

    public static final String MOD_ID = "@{MOD_ID}";
    public static final String MOD_NAME = "@{MOD_NAME}";
    public static final String VERSION = "@{MOD_VERSION}";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    @Mod.Instance(MOD_ID)
    public static AutomataMod instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Initializing {} v{}", MOD_NAME, VERSION);
        com.x4yi.automata.ai.jobs.ModdedOreCatalog.init(event.getModConfigurationDirectory());
        AutomataAPI.ensureBuiltInsRegistered();
        ForgeChunkManager.setForcedChunkLoadingCallback(this, new AutomatonChunkLoader.Callback());
        GameRegistry.registerWorldGenerator(new AutomatonCampGenerator(), 10);
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new AutomatonGuiHandler());
        UINetwork.onRpc(
                "automata:get_snapshot",
                ActionGuard.require((player) -> player.openContainer instanceof ContainerAutomaton),
                (player, params) -> {
                    int entityId = params.getInteger("entityId");
                    Entity entity = player.world.getEntityByID(entityId);
                    if (entity instanceof EntityAutomaton bot
                            && !bot.isDead
                            && bot.world == player.world
                            && (bot.isOwnedBy(player) || player.canUseCommand(2, ""))) {
                        return RpcResult.ok(ContainerAutomaton.buildSnapshot(bot));
                    }
                    return RpcResult.error("FORBIDDEN", "Cannot access automaton");
                });
        UINetwork.onRpc(
                "automata:debug_sync",
                ActionGuard.require((player) -> {
                    if (player == null) {
                        return false;
                    }
                    ItemStack held = player.getHeldItemMainhand();
                    return !held.isEmpty() && held.getItem() instanceof com.x4yi.automata.item.ItemAutomaDevStaff;
                }),
                (player, params) -> RpcResult.ok(buildDebugSyncPayload(player)));
        UINetwork.registerActionHandler(
                "automata:open_remote_gui",
                ActionGuard.require((player) -> player != null),
                (player, actionId, data) -> {
                    if (data == null || !data.hasKey("entityId")) {
                        return;
                    }
                    int entityId = data.getInteger("entityId");
                    Entity entity = player.world.getEntityByID(entityId);
                    if (entity instanceof EntityAutomaton bot
                            && !bot.isDead
                            && player.getDistanceSq(bot) <= 121.0D
                            && (bot.isOwnedBy(player) || player.canUseCommand(2, ""))) {
                        player.openGui(AutomataMod.instance, AutomatonGuiHandler.GUI_AUTOMATON, player.world, entityId, 0, 0);
                    }
                });
        UINetwork.registerActionHandler(
                "automata:set_staff_mode",
                ActionGuard.require((player) -> player != null),
                (player, actionId, data) -> {
                    if (data == null || !data.hasKey("staffMode")) {
                        return;
                    }
                    ItemStack held = player.getHeldItemMainhand();
                    if (!held.isEmpty() && held.getItem() instanceof ItemAutomaStaff) {
                        ItemAutomaStaff.setActiveMode(held, com.x4yi.automata.item.AutomaStaffMode.fromName(data.getString("staffMode")));
                    }
                });
        UINetwork.registerActionHandler(
                "automata:staff_left_click_entity",
                ActionGuard.require((player) -> player != null),
                (player, actionId, data) -> {
                    if (data == null || !data.hasKey("entityId")) {
                        return;
                    }
                    ItemStack held = player.getHeldItemMainhand();
                    if (held.isEmpty() || !(held.getItem() instanceof ItemAutomaStaff)) {
                        return;
                    }
                    Entity entity = player.world.getEntityByID(data.getInteger("entityId"));
                    if (entity instanceof EntityAutomaton bot && !bot.isDead && player.getDistanceSq(bot) <= 121.0D) {
                        ItemAutomaStaff.handleRemoteLeftClickEntity(
                                player,
                                held,
                                bot,
                                data.getBoolean("ctrl"),
                                data.getBoolean("sneak"),
                                com.x4yi.automata.item.AutomaStaffMode.fromName(data.getString("staffMode")));
                    }
                });
        UINetwork.registerActionHandler(
                "automata:staff_left_click_block",
                ActionGuard.require((player) -> player != null),
                (player, actionId, data) -> {
                    if (data == null || !data.hasKey("pos") || !(player.world instanceof WorldServer worldServer)) {
                        return;
                    }
                    ItemStack held = player.getHeldItemMainhand();
                    if (held.isEmpty() || !(held.getItem() instanceof ItemAutomaStaff)) {
                        return;
                    }
                    BlockPos pos = BlockPos.fromLong(data.getLong("pos"));
                    if (player.getDistanceSqToCenter(pos) <= 121.0D) {
                        ItemAutomaStaff.handleRemoteLeftClickBlock(
                                player,
                                held,
                                worldServer,
                                pos,
                                data.getBoolean("sneak"),
                                com.x4yi.automata.item.AutomaStaffMode.fromName(data.getString("staffMode")));
                    }
                });
        UINetwork.registerActionHandler(
                "automata:set_zone_ab",
                ActionGuard.require((player) -> player != null),
                (player, actionId, data) -> {
                    if (data == null || !data.hasKey("entityId") || !data.hasKey("pos1") || !data.hasKey("pos2")) {
                        return;
                    }
                    int entityId = data.getInteger("entityId");
                    Entity entity = player.world.getEntityByID(entityId);
                    if (entity instanceof EntityAutomaton bot
                            && !bot.isDead
                            && player.getDistanceSq(bot) <= 4096.0D
                            && (bot.isOwnedBy(player) || player.canUseCommand(2, ""))) {
                        if (MineJob.rejectAreaIfMiner(player, bot)) {
                            return;
                        }
                        BlockPos p1 = BlockPos.fromLong(data.getLong("pos1"));
                        BlockPos p2 = BlockPos.fromLong(data.getLong("pos2"));
                        bot.getTaskConfig().setPos1(p1);
                        bot.getTaskConfig().setPos2(p2);
                        UINetwork.sendToast(
                                player,
                                "Automata",
                                net.minecraft.util.text.translation.I18n.translateToLocal("message.automata.toast.zone_saved"),
                                2.0F);
                        player.openGui(AutomataMod.instance, AutomatonGuiHandler.GUI_AUTOMATON, player.world, entityId, 0, 0);
                    }
                });
        UINetwork.registerActionHandler(
                "automata:dev_staff_entity",
                ActionGuard.require((player) -> player != null),
                (player, actionId, data) -> {
                    if (data == null || !data.hasKey("entityId")) {
                        return;
                    }
                    ItemStack held = player.getHeldItemMainhand();
                    if (held.isEmpty() || !(held.getItem() instanceof com.x4yi.automata.item.ItemAutomaDevStaff)) {
                        return;
                    }
                    Entity entity = player.world.getEntityByID(data.getInteger("entityId"));
                    if (entity instanceof net.minecraft.entity.EntityLivingBase living && !living.isDead) {
                        com.x4yi.automata.item.ItemAutomaDevStaff.handleDevActionOnEntity(
                                player,
                                held,
                                living,
                                com.x4yi.automata.item.DevStaffMode.fromName(data.getString("devMode")));
                    }
                });
        UINetwork.registerActionHandler(
                "automata:dev_staff_point",
                ActionGuard.require((player) -> player != null),
                (player, actionId, data) -> {
                    if (data == null || !data.hasKey("pos") || !(player.world instanceof WorldServer worldServer)) {
                        return;
                    }
                    ItemStack held = player.getHeldItemMainhand();
                    if (held.isEmpty() || !(held.getItem() instanceof com.x4yi.automata.item.ItemAutomaDevStaff)) {
                        return;
                    }
                    BlockPos pos = BlockPos.fromLong(data.getLong("pos"));
                    if (player.getDistanceSqToCenter(pos) <= 576.0D) {
                        com.x4yi.automata.item.ItemAutomaDevStaff.handleDevActionOnPoint(
                                player,
                                held,
                                worldServer,
                                pos,
                                com.x4yi.automata.item.DevStaffMode.fromName(data.getString("devMode")));
                    }
                });
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        AutomataAPI.fireJobRegisterEvent();
        com.x4yi.automata.compat.SrpCompat.syncConfig();
    }

    @Mod.EventHandler
    public void postInit(net.minecraftforge.fml.common.event.FMLPostInitializationEvent event) {
        com.x4yi.automata.compat.SrpCompat.syncConfig();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandAutomata());
        AutomatonTeleportHandler.installTabSupport(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        com.x4yi.automata.bench.InGameBenchmarkProfiler.stopAndWriteReport();
        com.x4yi.automata.ai.WorldEntitySpatialIndex.clearAll();
        com.x4yi.automata.compat.ClaimProtectionCompat.clearAlerts();
        PathfindingExecutor.shutdown();
        SquadRegistry.clearAll();
    }

    private static net.minecraft.nbt.NBTTagCompound buildDebugSyncPayload(net.minecraft.entity.player.EntityPlayerMP player) {
        final double maxDistSq = 4096.0D;
        final int maxBots = 64;
        final int maxPathSteps = 256;
        final int maxBreakBlocks = 64;
        final int maxReservations = 256;

        net.minecraft.nbt.NBTTagCompound root = new net.minecraft.nbt.NBTTagCompound();
        net.minecraft.nbt.NBTTagList botsList = new net.minecraft.nbt.NBTTagList();
        com.x4yi.automata.coordination.SquadBlackboard sharedBlackboard = null;
        long[] breakScratch = new long[maxBreakBlocks];
        int botCount = 0;

        for (Entity entity : player.world.loadedEntityList) {
            if (!(entity instanceof EntityAutomaton bot) || bot.isDead) {
                continue;
            }
            if (player.getDistanceSq(bot) > maxDistSq) {
                continue;
            }
            if (sharedBlackboard == null) {
                sharedBlackboard = bot.getBrain().getContext().getBlackboard();
            }
            if (botCount >= maxBots) {
                continue;
            }

            net.minecraft.nbt.NBTTagCompound botTag = new net.minecraft.nbt.NBTTagCompound();
            botTag.setInteger("id", bot.getEntityId());

            com.x4yi.automata.navigation.PathResult path = bot.getMovementController().getActivePath();
            if (path != null && !path.isEmpty()) {
                int len = Math.min(path.length(), maxPathSteps);
                long[] steps = new long[len];
                byte[] actions = new byte[len];
                for (int i = 0; i < len; i++) {
                    steps[i] = path.getStep(i);
                    actions[i] = path.getAction(i);
                }
                botTag.setByteArray("steps", packLongs(steps, len));
                botTag.setByteArray("actions", actions);
            }

            com.x4yi.automata.ai.IAutomatonJob job = bot.getBrain().getActiveJob();
            if (job != null) {
                int breakCount = job.copyPlannedBreakTargets(breakScratch, maxBreakBlocks);
                if (breakCount > 0) {
                    botTag.setByteArray("breaks", packLongs(breakScratch, breakCount));
                }
            }

            botsList.appendTag(botTag);
            botCount++;
        }
        root.setTag("bots", botsList);

        if (sharedBlackboard != null) {
            java.util.Map<Long, com.x4yi.automata.coordination.SquadBlackboard.BlockReservation> snapshot =
                    sharedBlackboard.getActiveReservationsSnapshot();
            if (!snapshot.isEmpty()) {
                long[] resPos = new long[Math.min(snapshot.size(), maxReservations)];
                int[] resOwner = new int[resPos.length];
                int count = 0;
                for (java.util.Map.Entry<Long, com.x4yi.automata.coordination.SquadBlackboard.BlockReservation> entry : snapshot.entrySet()) {
                    if (count >= maxReservations) {
                        break;
                    }
                    long packed = entry.getKey();
                    int x = com.x4yi.automata.navigation.PackedPos.unpackX(packed);
                    int y = com.x4yi.automata.navigation.PackedPos.unpackY(packed);
                    int z = com.x4yi.automata.navigation.PackedPos.unpackZ(packed);
                    if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > maxDistSq) {
                        continue;
                    }
                    resPos[count] = packed;
                    resOwner[count] = entry.getValue().ownerEntityId();
                    count++;
                }
                if (count > 0) {
                    root.setByteArray("resPos", packLongs(resPos, count));
                    root.setIntArray("resOwner", java.util.Arrays.copyOf(resOwner, count));
                }
            }
        }
        return root;
    }

    private static byte[] packLongs(long[] values, int count) {
        byte[] out = new byte[count * 8];
        for (int i = 0; i < count; i++) {
            long v = values[i];
            int base = i * 8;
            out[base] = (byte) (v >>> 56);
            out[base + 1] = (byte) (v >>> 48);
            out[base + 2] = (byte) (v >>> 40);
            out[base + 3] = (byte) (v >>> 32);
            out[base + 4] = (byte) (v >>> 24);
            out[base + 5] = (byte) (v >>> 16);
            out[base + 6] = (byte) (v >>> 8);
            out[base + 7] = (byte) v;
        }
        return out;
    }
}
