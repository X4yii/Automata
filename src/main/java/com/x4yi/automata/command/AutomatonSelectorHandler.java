package com.x4yi.automata.command;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import net.minecraft.command.CommandException;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.entity.EntityAutomaton;

/**
 * Expands {@code @at} (and {@code @at[...]}) across all server commands.
 * When multiple automata match in single-entity commands (like /effect or /kill),
 * fans out execution per matching automaton UUID so {@code matchOneEntity} never fails.
 */
@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class AutomatonSelectorHandler {

    public static final String AUTOMATON_ENTITY_TYPE = AutomataMod.MOD_ID + ":automaton";
    private static final Pattern AT_SELECTOR_PATTERN = Pattern.compile("^@at(?:\\[([^ ]*)\\])?$");

    private AutomatonSelectorHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        String[] args = event.getParameters();
        if (args == null || args.length == 0) {
            return;
        }
        int atIndex = findFirstAtSelectorIndex(args);
        if (atIndex < 0) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        ICommandSender sender = event.getSender();
        if (server != null && sender != null) {
            String expanded = expandSelectorToken(args[atIndex]);
            List<EntityAutomaton> matches = resolveMatchingAutomata(server, sender, expanded);
            if (matches.size() == 1) {
                String[] rewritten = args.clone();
                rewritten[atIndex] = matches.get(0).getUniqueID().toString();
                String[] remaining = rewriteArgs(rewritten);
                event.setParameters(remaining != null ? remaining : rewritten);
                return;
            }
            if (matches.size() > 1) {
                event.setCanceled(true);
                if (!event.getCommand().checkPermission(server, sender)) {
                    return;
                }
                for (EntityAutomaton bot : matches) {
                    String[] perBotArgs = args.clone();
                    perBotArgs[atIndex] = bot.getUniqueID().toString();
                    String[] remaining = rewriteArgs(perBotArgs);
                    try {
                        event.getCommand().execute(server, sender, remaining != null ? remaining : perBotArgs);
                    } catch (CommandException ex) {
                        TextComponentString msg = new TextComponentString(ex.getMessage() != null ? ex.getMessage() : "Command error");
                        msg.getStyle().setColor(TextFormatting.RED);
                        sender.sendMessage(msg);
                    }
                }
                return;
            }
        }
        String[] rewritten = rewriteArgs(args);
        if (rewritten != null) {
            event.setParameters(rewritten);
        }
    }

    private static int findFirstAtSelectorIndex(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (expandSelectorToken(args[i]) != null) {
                return i;
            }
        }
        return -1;
    }

    private static List<EntityAutomaton> resolveMatchingAutomata(
            MinecraftServer server,
            ICommandSender sender,
            @Nullable String expandedSelector) {
        List<EntityAutomaton> result = new ArrayList<>();
        if (expandedSelector != null) {
            try {
                List<EntityAutomaton> selected = EntitySelector.matchEntities(sender, expandedSelector, EntityAutomaton.class);
                for (EntityAutomaton bot : selected) {
                    if (bot != null && bot.isEntityAlive()) {
                        result.add(bot);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (result.isEmpty()) {
            for (WorldServer world : server.worlds) {
                if (world == null) {
                    continue;
                }
                for (EntityAutomaton bot : world.getEntities(EntityAutomaton.class, e -> e != null && e.isEntityAlive())) {
                    result.add(bot);
                }
            }
        }
        return result;
    }

    @Nullable
    public static String[] rewriteArgs(String[] args) {
        String[] copy = null;
        for (int i = 0; i < args.length; i++) {
            String expanded = expandSelectorToken(args[i]);
            if (expanded != null) {
                if (copy == null) {
                    copy = args.clone();
                }
                copy[i] = expanded;
            }
        }
        return copy;
    }

    @Nullable
    public static String expandSelectorToken(@Nullable String token) {
        if (token == null || token.length() < 3 || token.charAt(0) != '@') {
            return null;
        }
        Matcher matcher = AT_SELECTOR_PATTERN.matcher(token);
        if (!matcher.matches()) {
            return null;
        }
        String innerArgs = matcher.group(1);
        if (innerArgs == null || innerArgs.trim().isEmpty()) {
            return "@e[type=" + AUTOMATON_ENTITY_TYPE + "]";
        }
        return "@e[type=" + AUTOMATON_ENTITY_TYPE + "," + innerArgs.trim() + "]";
    }
}
