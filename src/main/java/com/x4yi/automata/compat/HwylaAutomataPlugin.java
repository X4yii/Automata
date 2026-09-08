package com.x4yi.automata.compat;

import com.x4yi.automata.api.AutomataAPI;
import com.x4yi.automata.api.JobDescriptor;
import com.x4yi.automata.entity.EntityAutomaton;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaEntityAccessor;
import mcp.mobius.waila.api.IWailaEntityProvider;
import mcp.mobius.waila.api.IWailaPlugin;
import mcp.mobius.waila.api.IWailaRegistrar;
import mcp.mobius.waila.api.WailaPlugin;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Optional HWYLA/Waila integration displaying automaton vitals, job status, and squad channel.
 */
@WailaPlugin
public class HwylaAutomataPlugin implements IWailaPlugin, IWailaEntityProvider {

    private static final String NBT_HUNGER = "AutomataWailaHunger";
    private static final String NBT_JOB = "AutomataWailaJob";
    private static final String NBT_SQUAD = "AutomataWailaSquad";
    private static final String NBT_STATUS = "AutomataWailaStatus";

    @Override
    public void register(IWailaRegistrar registrar) {
        registrar.registerBodyProvider(this, EntityAutomaton.class);
        registrar.registerNBTProvider(this, EntityAutomaton.class);
    }

    @Nonnull
    @Override
    public List<String> getWailaBody(Entity entity, List<String> currenttip, IWailaEntityAccessor accessor, IWailaConfigHandler config) {
        if (!(entity instanceof EntityAutomaton)) {
            return currenttip;
        }
        EntityAutomaton bot = (EntityAutomaton) entity;
        NBTTagCompound nbt = accessor.getNBTData();
        float hunger = nbt.hasKey(NBT_HUNGER) ? nbt.getFloat(NBT_HUNGER) : bot.getHunger();
        String job = nbt.hasKey(NBT_JOB) ? nbt.getString(NBT_JOB) : formatJobTitle(bot);
        String squad = nbt.hasKey(NBT_SQUAD) ? nbt.getString(NBT_SQUAD) : bot.getTaskConfig().getSquadChannel();
        String status = nbt.hasKey(NBT_STATUS) ? nbt.getString(NBT_STATUS) : resolveStatus(bot);

        currenttip.add(TextFormatting.RED + "HP: " + TextFormatting.WHITE
                + String.format("%.1f / %.1f", bot.getHealth(), bot.getMaxHealth())
                + TextFormatting.GOLD + "  Food: " + TextFormatting.WHITE + Math.round(hunger) + " / 20");
        currenttip.add(TextFormatting.AQUA + "Job: " + TextFormatting.WHITE + job
                + TextFormatting.GRAY + " (" + status + ")");
        if (!squad.isEmpty()) {
            currenttip.add(TextFormatting.GREEN + "Squad: " + TextFormatting.WHITE + squad);
        }
        return currenttip;
    }

    @Nonnull
    @Override
    public NBTTagCompound getNBTData(EntityPlayerMP player, Entity ent, NBTTagCompound tag, World world) {
        if (ent instanceof EntityAutomaton) {
            EntityAutomaton bot = (EntityAutomaton) ent;
            tag.setFloat(NBT_HUNGER, bot.getHunger());
            tag.setString(NBT_JOB, formatJobTitle(bot));
            tag.setString(NBT_SQUAD, bot.getTaskConfig().getSquadChannel());
            tag.setString(NBT_STATUS, resolveStatus(bot));
        }
        return tag;
    }

    private static String formatJobTitle(EntityAutomaton bot) {
        JobDescriptor descriptor = AutomataAPI.getJob(bot.getTaskConfig().getJobId());
        return descriptor != null ? descriptor.getTitleKey() : bot.getTaskConfig().getJobId().toString();
    }

    private static String resolveStatus(EntityAutomaton bot) {
        if (bot.isDowned()) {
            return "Downed";
        }
        if (bot.getBrain().getContext().getMiningController().isMining()) {
            return "Mining";
        }
        if (bot.getMovementController().hasActivePath()) {
            return "Moving";
        }
        return "Active";
    }
}
