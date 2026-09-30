package com.x4yi.automata.ai;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextComponentString;

import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.entity.EntityAutomaton;

public final class TaskCompletionService {

    private TaskCompletionService() {}

    public static void completeTask(AutomatonAIContext ctx, String taskDisplayName) {
        AutomatonTaskConfig cfg = ctx.getTaskConfig();
        if (cfg.getCompletionBehavior() == TaskCompletionBehavior.RETURN_AND_STAY
                && LogisticsService.tryDepositSurplus(ctx)) {
            return;
        }
        EntityAutomaton bot = ctx.getAutomaton();
        ctx.stopNavigation();
        ctx.resetMining();
        bot.getChunkLoader().setExtraWorkChunk(null);
        cfg.setJobId(AutomatonBuiltInJobs.IDLE);

        if (bot.getOwnerUuid() != null) {
            EntityPlayer owner = ctx.getWorld().getPlayerEntityByUUID(bot.getOwnerUuid());
            if (owner != null) {
                owner.sendStatusMessage(
                        new TextComponentString("[Automata #" + bot.getEntityId() + "] " + taskDisplayName + " completed"),
                        true);
            }
        }
    }
}

