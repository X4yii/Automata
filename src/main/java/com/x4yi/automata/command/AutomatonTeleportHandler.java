package com.x4yi.automata.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.entity.EntityAutomaton;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class AutomatonTeleportHandler {

    private AutomatonTeleportHandler() {}

    public static void installTabSupport(FMLServerStartingEvent event) {
        ICommandManager manager = event.getServer().getCommandManager();
        Map<String, ICommand> commands = manager.getCommands();
        List<String> keys = new ArrayList<>(commands.keySet());
        for (String name : keys) {
            wrapIfPresent(event, commands, name);
        }
    }

    private static void wrapIfPresent(FMLServerStartingEvent event, Map<String, ICommand> commands, String name) {
        ICommand existing = commands.get(name);
        if (existing != null && !(existing instanceof TeleportTabWrapper)) {
            event.registerServerCommand(new TeleportTabWrapper(existing));
        }
    }

    public static List<String> filterDotBotSuggestions(List<String> botNames, String[] args) {
        if (args.length < 1 || botNames.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> suggestions = new ArrayList<>(botNames.size() + 1);
        suggestions.add("@at");
        for (String name : botNames) {
            if (name != null && !name.isEmpty()) {
                suggestions.add("." + name);
            }
        }
        return CommandBase.getListOfStringsMatchingLastWord(args, suggestions);
    }

    private static List<String> collectNamedBotTokens(MinecraftServer server) {
        Set<String> names = new LinkedHashSet<>();
        for (WorldServer world : server.worlds) {
            if (world == null) {
                continue;
            }
            for (Entity entity : world.loadedEntityList) {
                if (entity instanceof EntityAutomaton bot && bot.isEntityAlive()) {
                    String customName = bot.getCustomNameTag();
                    if (!customName.isEmpty()) {
                        names.add(customName);
                    }
                }
            }
        }
        return new ArrayList<>(names);
    }

    public static boolean isTeleportCommand(ICommand command) {
        String name = command.getName();
        return "tp".equalsIgnoreCase(name) || "teleport".equalsIgnoreCase(name);
    }

    public static boolean hasDotBotToken(String[] args) {
        for (String arg : args) {
            if (parseDotBotName(arg) != null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static String parseDotBotName(@Nullable String token) {
        if (token == null || token.length() < 2 || token.charAt(0) != '.') {
            return null;
        }
        String trimmed = token.substring(1).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onCommand(CommandEvent event) {
        String[] args = event.getParameters();
        if (!hasDotBotToken(args)) {
            return;
        }

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }

        ICommandSender sender = event.getSender();
        if (!isTeleportCommand(event.getCommand())) {
            try {
                String[] rewritten = rewriteDotBotArgsToUuid(server, sender, args);
                if (rewritten != null) {
                    event.setParameters(rewritten);
                }
            } catch (CommandException ex) {
                event.setCanceled(true);
                sendError(sender, ex.getMessage());
            }
            return;
        }

        event.setCanceled(true);

        if (!event.getCommand().checkPermission(server, sender)) {
            sendError(sender, "You do not have permission to use /tp.");
            return;
        }

        try {
            executeDotTeleport(server, sender, args);
        } catch (CommandException ex) {
            sendError(sender, ex.getMessage());
        }
    }

    @Nullable
    static String[] rewriteDotBotArgsToUuid(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        String[] copy = null;
        for (int i = 0; i < args.length; i++) {
            String botName = parseDotBotName(args[i]);
            if (botName != null) {
                Entity bot = resolveDotOrEntity(server, sender, args[i]);
                if (copy == null) {
                    copy = args.clone();
                }
                copy[i] = bot.getUniqueID().toString();
            }
        }
        return copy;
    }

    private static void executeDotTeleport(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length == 1) {
            Entity source = CommandBase.getCommandSenderAsPlayer(sender);
            Entity dest = resolveDotOrEntity(server, sender, args[0]);
            teleportToEntity(source, dest);
            sender.sendMessage(new TextComponentString(
                    "Teleported " + source.getName() + " to " + dest.getName()));
            return;
        }

        if (args.length == 2) {
            List<Entity> sources = resolveDotOrEntities(server, sender, args[0]);
            Entity dest = resolveDotOrEntity(server, sender, args[1]);
            for (Entity source : sources) {
                teleportToEntity(source, dest);
            }
            String label = sources.size() == 1 ? sources.get(0).getName() : sources.size() + " entities";
            sender.sendMessage(new TextComponentString(
                    "Teleported " + label + " to " + dest.getName()));
            return;
        }

        if (args.length >= 4) {
            List<Entity> targets = resolveDotOrEntities(server, sender, args[0]);
            for (Entity target : targets) {
                CommandBase.CoordinateArg xArg = CommandBase.parseCoordinate(target.posX, args[1], true);
                CommandBase.CoordinateArg yArg = CommandBase.parseCoordinate(target.posY, args[2], -4096, 4096, false);
                CommandBase.CoordinateArg zArg = CommandBase.parseCoordinate(target.posZ, args[3], true);
                float yaw = args.length >= 5
                        ? (float) CommandBase.parseDouble(args[4])
                        : target.rotationYaw;
                float pitch = args.length >= 6
                        ? MathHelper.clamp((float) CommandBase.parseDouble(args[5]), -90.0F, 90.0F)
                        : target.rotationPitch;

                teleportToCoordinates(target, xArg.getResult(), yArg.getResult(), zArg.getResult(), yaw, pitch);
            }
            Entity first = targets.get(0);
            String label = targets.size() == 1 ? first.getName() : targets.size() + " entities";
            sender.sendMessage(new TextComponentString(
                    String.format("Teleported %s to %.2f, %.2f, %.2f",
                            label, first.posX, first.posY, first.posZ)));
            return;
        }

        throw new CommandException("Usage: /tp .<name> | /tp <target> .<name> | /tp .<name> <x> <y> <z>");
    }

    private static List<Entity> resolveDotOrEntities(MinecraftServer server, ICommandSender sender, String token)
            throws CommandException {
        String dotName = parseDotBotName(token);
        if (dotName != null) {
            return Collections.singletonList(findAutomatonByDotName(server, sender, dotName));
        }
        return CommandBase.getEntityList(server, sender, token);
    }

    private static Entity resolveDotOrEntity(MinecraftServer server, ICommandSender sender, String token)
            throws CommandException {
        String dotName = parseDotBotName(token);
        if (dotName != null) {
            return findAutomatonByDotName(server, sender, dotName);
        }
        return CommandBase.getEntity(server, sender, token);
    }

    public static EntityAutomaton findAutomatonByDotName(
            MinecraftServer server,
            ICommandSender sender,
            String name) throws PlayerNotFoundException {
        List<EntityAutomaton> matches = new ArrayList<>();
        for (WorldServer world : server.worlds) {
            if (world == null) {
                continue;
            }
            for (Entity entity : world.loadedEntityList) {
                if (entity instanceof EntityAutomaton bot
                        && bot.isEntityAlive()
                        && name.equalsIgnoreCase(bot.getCustomNameTag())) {
                    matches.add(bot);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new PlayerNotFoundException("No automaton found with name ." + name);
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        Entity senderEntity = sender.getCommandSenderEntity();
        UUID senderId = senderEntity != null ? senderEntity.getUniqueID() : null;
        int senderDim = sender.getEntityWorld().provider.getDimension();
        for (EntityAutomaton bot : matches) {
            if (senderId != null
                    && senderId.equals(bot.getOwnerUuid())
                    && bot.world.provider.getDimension() == senderDim) {
                return bot;
            }
        }
        for (EntityAutomaton bot : matches) {
            if (bot.world.provider.getDimension() == senderDim) {
                return bot;
            }
        }
        return matches.get(0);
    }

    private static void teleportToEntity(Entity source, Entity dest) {
        if (source instanceof EntityAutomaton bot) {
            bot.getBrain().getContext().stopNavigation();
        }
        if (source.world != dest.world) {
            source.changeDimension(dest.world.provider.getDimension());
        }
        if (source instanceof EntityPlayerMP player) {
            player.dismountRidingEntity();
            player.connection.setPlayerLocation(dest.posX, dest.posY, dest.posZ, dest.rotationYaw, dest.rotationPitch);
        } else {
            source.dismountRidingEntity();
            source.setLocationAndAngles(dest.posX, dest.posY, dest.posZ, dest.rotationYaw, dest.rotationPitch);
            source.setPositionAndUpdate(dest.posX, dest.posY, dest.posZ);
        }
    }

    private static void teleportToCoordinates(
            Entity target,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) {
        if (target instanceof EntityAutomaton bot) {
            bot.getBrain().getContext().stopNavigation();
        }
        target.dismountRidingEntity();
        if (target instanceof EntityPlayerMP player) {
            player.connection.setPlayerLocation(
                    x, y, z, yaw, pitch, EnumSet.noneOf(SPacketPlayerPosLook.EnumFlags.class));
        } else {
            target.setLocationAndAngles(x, y, z, yaw, pitch);
            target.setPositionAndUpdate(x, y, z);
        }
    }

    private static void sendError(ICommandSender sender, @Nullable String msg) {
        TextComponentString text = new TextComponentString(msg == null ? "Teleport error" : msg);
        text.getStyle().setColor(TextFormatting.RED);
        sender.sendMessage(text);
    }

    private static final class TeleportTabWrapper implements ICommand {

        private final ICommand delegate;

        TeleportTabWrapper(ICommand delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return delegate.getUsage(sender);
        }

        @Override
        public List<String> getAliases() {
            return delegate.getAliases();
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            delegate.execute(server, sender, args);
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            return delegate.checkPermission(server, sender);
        }

        @Override
        public List<String> getTabCompletions(
                MinecraftServer server,
                ICommandSender sender,
                String[] args,
                @Nullable BlockPos targetPos) {
            List<String> base = new ArrayList<>(delegate.getTabCompletions(server, sender, args, targetPos));
            if (args.length >= 1) {
                String last = args[args.length - 1];
                boolean wantsBotTab = last.startsWith(".")
                        || last.startsWith("@")
                        || delegate.isUsernameIndex(args, args.length - 1);
                if (wantsBotTab) {
                    List<String> botMatches = filterDotBotSuggestions(collectNamedBotTokens(server), args);
                    for (String match : botMatches) {
                        if (!base.contains(match)) {
                            base.add(match);
                        }
                    }
                }
            }
            return base;
        }

        @Override
        public boolean isUsernameIndex(String[] args, int index) {
            return delegate.isUsernameIndex(args, index);
        }

        @Override
        public int compareTo(ICommand other) {
            return delegate.compareTo(other);
        }
    }
}

