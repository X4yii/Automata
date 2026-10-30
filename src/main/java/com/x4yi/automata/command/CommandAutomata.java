
package com.x4yi.automata.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

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
            sender.sendMessage(new TextComponentTranslation("commands.automata.reload.success"));
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
}

