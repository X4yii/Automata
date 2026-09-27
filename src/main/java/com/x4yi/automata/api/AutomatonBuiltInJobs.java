package com.x4yi.automata.api;

import net.minecraft.util.ResourceLocation;

/**
 * Canonical job identifiers for built-in automata jobs.
 */
public final class AutomatonBuiltInJobs {

    public static final ResourceLocation IDLE = new ResourceLocation("automata", "idle");
    public static final ResourceLocation EXCAVATION = new ResourceLocation("automata", "excavation");
    public static final ResourceLocation MINE = new ResourceLocation("automata", "mine");
    public static final ResourceLocation LUMBERJACK = new ResourceLocation("automata", "lumberjack");
    public static final ResourceLocation FARMER = new ResourceLocation("automata", "farmer");
    public static final ResourceLocation DEFEND = new ResourceLocation("automata", "defend");
    public static final ResourceLocation CRAZY_DEFEND = new ResourceLocation("automata", "crazy_defend");
    public static final ResourceLocation COURIER = new ResourceLocation("automata", "courier");

    private AutomatonBuiltInJobs() {
    }
}

