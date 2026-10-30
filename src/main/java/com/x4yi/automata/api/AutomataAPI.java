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
import com.x4yi.automata.ai.jobs.RoamJob;
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

    public static AutomatonAIContext requireInternalContext(@Nullable IAutomatonContext ctx) {
        if (ctx instanceof AutomatonAIContext internal) {
            return internal;
        }
        throw new IllegalArgumentException(
                "Expected internal AutomatonAIContext, got " + (ctx == null ? "null" : ctx.getClass().getName()));
    }

    public static void engageTarget(IAutomatonContext ctx, EntityLivingBase target, boolean urgent) {
        CombatJobSupport.engageTarget(requireInternalContext(ctx), target, urgent);
    }

    public static void escortOwnerOrAnchor(
            IAutomatonContext ctx,
            @Nullable EntityPlayer owner,
            @Nullable BlockPos anchor,
            boolean urgent) {
        CombatJobSupport.escortOwnerOrAnchor(requireInternalContext(ctx), owner, anchor, urgent);
    }

    public static boolean isValidCombatCandidate(IAutomatonContext ctx, EntityLivingBase candidate) {
        return CombatJobSupport.isValidCombatCandidate(requireInternalContext(ctx), candidate);
    }

    public static synchronized void ensureBuiltInsRegistered() {
        if (initialized) {
            return;
        }
        registerBuiltIn(
                AutomatonBuiltInJobs.IDLE,
                MovementMood.CHILL,
                false,
                false,
                0,
                null,
                IdleJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.EXCAVATION,
                MovementMood.CHILL,
                true,
                false,
                0,
                EquipmentSelector::isExcavationTool,
                ExcavationJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.MINE,
                MovementMood.CHILL,
                true,
                false,
                0,
                EquipmentSelector::isPickaxe,
                MineJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.LUMBERJACK,
                MovementMood.CHILL,
                true,
                false,
                0,
                EquipmentSelector::isAxe,
                LumberjackJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.FARMER,
                MovementMood.CHILL,
                true,
                false,
                0,
                EquipmentSelector::isHoe,
                FarmerJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.DEFEND,
                MovementMood.TRYHARD,
                false,
                true,
                2,
                stack -> stack.getItem() instanceof ItemSword,
                DefendJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.CRAZY_DEFEND,
                MovementMood.TRYHARD,
                false,
                true,
                2,
                stack -> stack.getItem() instanceof ItemSword,
                CrazyDefendJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.COURIER,
                MovementMood.CHILL,
                false,
                false,
                0,
                null,
                CourierJob::new);
        registerBuiltIn(
                AutomatonBuiltInJobs.ROAM,
                MovementMood.CHILL,
                false,
                true,
                2,
                stack -> stack.getItem() instanceof ItemSword,
                RoamJob::new);
        initialized = true;
    }

    private static void registerBuiltIn(
            ResourceLocation id,
            MovementMood mood,
            boolean logisticsEligible,
            boolean combat,
            int decisionIntervalTicks,
            @Nullable java.util.function.Predicate<ItemStack> toolMatcher,
            java.util.function.Supplier<com.x4yi.automata.ai.IAutomatonJob> factory) {
        JOB_REGISTRY.register(JobDescriptor.builder(id, factory)
                .movementMood(mood)
                .logisticsEligible(logisticsEligible)
                .combat(combat)
                .decisionIntervalTicks(decisionIntervalTicks)
                .toolMatcher(toolMatcher)
                .build());
    }
}
