
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
        dependencies = "required-after:x4ui@[r1.0b6];after:waila;after:hwyla;after:jei",
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
                        UINetwork.sendToast(player, "Automata", "Work zone A -> B saved", 2.0F);
                        player.openGui(AutomataMod.instance, AutomatonGuiHandler.GUI_AUTOMATON, player.world, entityId, 0, 0);
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
        PathfindingExecutor.shutdown();
        SquadRegistry.clearAll();
    }
}
