package com.x4yi.automata.api;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;

import com.x4yi.automata.ai.AutomatonAIContext;
import com.x4yi.automata.ai.EquipmentSelector;
import com.x4yi.automata.ai.jobs.CombatJobSupport;
import com.x4yi.automata.ai.jobs.CourierJob;
import com.x4yi.automata.ai.jobs.CrazyDefendJob;
import com.x4yi.automata.ai.jobs.DefendJob;
import com.x4yi.automata.ai.jobs.ExcavationJob;
import com.x4yi.automata.ai.jobs.FarmerJob;
import com.x4yi.automata.ai.jobs.IdleJob;
import com.x4yi.automata.ai.jobs.LumberjackJob;
import com.x4yi.automata.ai.jobs.MineJob;
import com.x4yi.automata.movement.HumanMovementController.MovementMood;

/**
 * Public entry point for addons and built-in automata content.
 */
public final class AutomataAPI {

    private static final AutomatonJobRegistry JOB_REGISTRY = new AutomatonJobRegistry();
    private static volatile boolean initialized;
    private static volatile boolean registerEventFired;

    private AutomataAPI() {
    }

    public static AutomatonJobRegistry getJobRegistry() {
        ensureBuiltInsRegistered();
        return JOB_REGISTRY;
    }

    @Nullable
    public static JobDescriptor getJob(ResourceLocation id) {
        return getJobRegistry().get(id);
    }

    public static List<JobDescriptor> getAllJobs() {
        return getJobRegistry().getAll();
    }

    public static synchronized void initialize() {
        ensureBuiltInsRegistered();
        fireJobRegisterEvent();
    }

    public static synchronized void fireJobRegisterEvent() {
        ensureBuiltInsRegistered();
        if (registerEventFired) {
            return;
        }
        registerEventFired = true;
        MinecraftForge.EVENT_BUS.post(new AutomataJobRegisterEvent(JOB_REGISTRY));
    }

    public static void engageTarget(IAutomatonContext ctx, EntityLivingBase target, boolean urgent) {
        if (ctx instanceof AutomatonAIContext internal) {
            CombatJobSupport.engageTarget(internal, target, urgent);
        }
    }

    public static void escortOwnerOrAnchor(
            IAutomatonContext ctx,
            @Nullable EntityPlayer owner,
            @Nullable BlockPos anchor,
            boolean urgent) {
        if (ctx instanceof AutomatonAIContext internal) {
            CombatJobSupport.escortOwnerOrAnchor(internal, owner, anchor, urgent);
        }
    }

    public static boolean isValidCombatCandidate(IAutomatonContext ctx, EntityLivingBase candidate) {
        return ctx instanceof AutomatonAIContext internal
                && CombatJobSupport.isValidCombatCandidate(internal, candidate);
    }

    public static synchronized void ensureBuiltInsRegistered() {
        if (initialized) {
            return;
        }
        registerBuiltIn(
                AutomatonBuiltInJobs.IDLE,
                "Idle / Follow",
                "Stays near owner or holds position",
                MovementMood.CHILL,
                false,
                null,
                IdleJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.EXCAVATION,
                "Excavation",
                "Mines marked box top-to-bottom",
                MovementMood.CHILL,
                true,
                stack -> stack.getItem() instanceof ItemTool || !stack.getItem().getToolClasses(stack).isEmpty(),
                ExcavationJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.MINE,
                "Mine",
                "Mines exposed nearby ores only",
                MovementMood.CHILL,
                true,
                stack -> stack.getItem() instanceof ItemTool || !stack.getItem().getToolClasses(stack).isEmpty(),
                MineJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.LUMBERJACK,
                "Lumberjack",
                "Chops trees and replants saplings",
                MovementMood.CHILL,
                true,
                EquipmentSelector::isAxe,
                LumberjackJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.FARMER,
                "Farmer",
                "Tills, plants, harvests crops",
                MovementMood.CHILL,
                true,
                EquipmentSelector::isHoe,
                FarmerJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.DEFEND,
                "Defend",
                "Guards owner/post with bow or melee",
                MovementMood.TRYHARD,
                false,
                stack -> stack.getItem() instanceof ItemSword,
                DefendJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.CRAZY_DEFEND,
                "Crazy Defend",
                "Aggressive pursuit & focus fire",
                MovementMood.TRYHARD,
                false,
                stack -> stack.getItem() instanceof ItemSword,
                CrazyDefendJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.COURIER,
                "Courier",
                "Moves items from Input to Output chests",
                MovementMood.CHILL,
                false,
                null,
                CourierJob::new);
        initialized = true;
    }

    private static void registerBuiltIn(
            ResourceLocation id,
            String title,
            String description,
            MovementMood mood,
            boolean logisticsEligible,
            @Nullable java.util.function.Predicate<ItemStack> toolMatcher,
            java.util.function.Supplier<com.x4yi.automata.ai.IAutomatonJob> factory) {
        JOB_REGISTRY.register(JobDescriptor.builder(id, factory)
                .titleKey(title)
                .descriptionKey(description)
                .movementMood(mood)
                .logisticsEligible(logisticsEligible)
                .toolMatcher(toolMatcher)
                .build());
    }
}
