
package com.x4yi.automata.command;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import com.x4yi.automata.bench.InGameBenchmarkProfiler;
import com.x4yi.automata.entity.EntityAutomaton;

public class CommandAutomata extends CommandBase {

    @Override
    public String getName() {
        return "automata";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.automata.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            com.x4yi.automata.ai.jobs.ModdedOreCatalog.reload();
            com.x4yi.automata.config.IntegrationOverrides.reload();
            sender.sendMessage(new TextComponentTranslation("commands.automata.reload.success"));
            return;
        }
        if (args.length >= 1 && "integration-report".equalsIgnoreCase(args[0])) {
            executeIntegrationReport(server, sender);
            return;
        }
        if (args.length >= 2 && "diag".equalsIgnoreCase(args[0])) {
            executeDiag(sender, args[1]);
            return;
        }
        if (args.length >= 1 && "bench".equalsIgnoreCase(args[0])) {
            executeBench(server, sender, args);
            return;
        }
        if (args.length < 1 || !"spawn".equalsIgnoreCase(args[0])) {
            throw new WrongUsageException(getUsage(sender));
        }
        World world = sender.getEntityWorld();
        BlockPos pos = sender.getPosition();
        EntityAutomaton bot = new EntityAutomaton(world);
        bot.setPosition(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        bot.onInitialSpawn(world.getDifficultyForLocation(pos), null);
        if (sender instanceof EntityPlayer player) {
            bot.setOwnerUuid(player.getUniqueID());
        }
        if (args.length >= 2) {
            bot.setSkinName(args[1]);
        }
        world.spawnEntity(bot);
        sender.sendMessage(new TextComponentTranslation("commands.automata.spawn.success", bot.getEntityId()));
    }

    private void executeDiag(ICommandSender sender, String targetArg) throws CommandException {
        int entityId = parseInt(targetArg, 0);
        net.minecraft.entity.Entity entity = sender.getEntityWorld().getEntityByID(entityId);
        if (!(entity instanceof EntityAutomaton bot)) {
            sender.sendMessage(new TextComponentString("[Automata] Automaton #" + entityId + " not found"));
            return;
        }
        List<String> lines = bot.getBrain().getContext().getDecisionTrace().snapshotRecent(15);
        sender.sendMessage(new TextComponentString(
                "[Automata #" + entityId + "] Job=" + bot.getTaskConfig().getJobId()
                        + " (" + lines.size() + " trace entries)"));
        for (String line : lines) {
            sender.sendMessage(new TextComponentString("  " + line));
        }
    }

    private void executeIntegrationReport(MinecraftServer server, ICommandSender sender) {
        File dataDir = server != null ? server.getDataDirectory() : new File(".");
        Path out = (dataDir != null ? dataDir.toPath() : Paths.get("."))
                .resolve("config")
                .resolve("automata_integration_report.txt");
        try {
            Path written = com.x4yi.automata.compat.IntegrationReportGenerator.writeReport(out);
            sender.sendMessage(new TextComponentString("[Automata] Integration report written: " + written));
        } catch (Exception e) {
            sender.sendMessage(new TextComponentString("[Automata] Integration report failed: " + e.getMessage()));
        }
    }

    private void executeBench(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 2 && "status".equalsIgnoreCase(args[1])) {
            if (InGameBenchmarkProfiler.isActive()) {
                if (InGameBenchmarkProfiler.isManualMode()) {
                    sender.sendMessage(new TextComponentTranslation(
                            "commands.automata.bench.status.running_manual",
                            String.format(java.util.Locale.ROOT, "%.1f", InGameBenchmarkProfiler.getElapsedSeconds())));
                } else {
                    sender.sendMessage(new TextComponentTranslation(
                            "commands.automata.bench.status.running",
                            String.format(java.util.Locale.ROOT, "%.1f", InGameBenchmarkProfiler.getElapsedSeconds()),
                            String.format(java.util.Locale.ROOT, "%.1f", InGameBenchmarkProfiler.getTargetSeconds())));
                }
            } else {
                sender.sendMessage(new TextComponentTranslation("commands.automata.bench.status.idle"));
            }
            return;
        }
        if (args.length >= 2 && ("stop".equalsIgnoreCase(args[1]) || "off".equalsIgnoreCase(args[1]))) {
            Path written = InGameBenchmarkProfiler.stopAndWriteReport();
            if (written == null) {
                sender.sendMessage(new TextComponentTranslation("commands.automata.bench.status.idle"));
            } else {
                sender.sendMessage(new TextComponentTranslation("commands.automata.bench.stopped", written.toString()));
            }
            return;
        }

        boolean manual = args.length >= 2 && ("start".equalsIgnoreCase(args[1]) || "manual".equalsIgnoreCase(args[1]));
        int seconds = manual ? 0 : InGameBenchmarkProfiler.DEFAULT_SECONDS;
        if (!manual && args.length >= 2) {
            seconds = parseInt(args[1], 1, InGameBenchmarkProfiler.MAX_SECONDS);
        }
        File dataDir = server != null ? server.getDataDirectory() : new File(".");
        Path benchDir = dataDir != null
                ? dataDir.toPath().resolve("automata").resolve("bench")
                : Paths.get("automata", "bench");

        boolean started = InGameBenchmarkProfiler.startSeconds(seconds, benchDir, summary -> {
            if (server != null) {
                server.addScheduledTask(() -> sender.sendMessage(new TextComponentString("[Automata] " + summary)));
            } else {
                sender.sendMessage(new TextComponentString("[Automata] " + summary));
            }
        });
        if (!started) {
            sender.sendMessage(new TextComponentTranslation(
                    "commands.automata.bench.already_running",
                    String.format(java.util.Locale.ROOT, "%.1f", InGameBenchmarkProfiler.getElapsedSeconds())));
            return;
        }
        if (manual) {
            sender.sendMessage(new TextComponentTranslation("commands.automata.bench.started_manual"));
        } else {
            sender.sendMessage(new TextComponentTranslation("commands.automata.bench.started", seconds));
        }
    }

    @Override
    public List<String> getTabCompletions(
            MinecraftServer server,
            ICommandSender sender,
            String[] args,
            @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "spawn", "reload", "bench");
        }
        if (args.length == 2 && "bench".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "start", "stop", "status", "5", "10", "30", "60");
        }
        return Collections.emptyList();
    }
}

