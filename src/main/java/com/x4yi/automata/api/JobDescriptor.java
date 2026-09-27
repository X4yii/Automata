package com.x4yi.automata.api;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.ai.IAutomatonJob;
import com.x4yi.automata.entity.EntityAutomaton;
import com.x4yi.automata.movement.HumanMovementController.MovementMood;

/**
 * Immutable metadata and factory descriptor for a registered automaton job.
 */
public final class JobDescriptor {

    private final ResourceLocation id;
    private final String titleKey;
    private final String descriptionKey;
    private final MovementMood movementMood;
    private final boolean logisticsEligible;
    private final Supplier<IAutomatonJob> factory;
    @Nullable
    private final Predicate<ItemStack> toolMatcher;
    @Nullable
    private final Predicate<EntityAutomaton> needsToolPredicate;

    private JobDescriptor(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.titleKey = Objects.requireNonNull(builder.titleKey, "titleKey");
        this.descriptionKey = Objects.requireNonNull(builder.descriptionKey, "descriptionKey");
        this.movementMood = Objects.requireNonNull(builder.movementMood, "movementMood");
        this.logisticsEligible = builder.logisticsEligible;
        this.factory = Objects.requireNonNull(builder.factory, "factory");
        this.toolMatcher = builder.toolMatcher;
        this.needsToolPredicate = builder.needsToolPredicate;
    }

    public ResourceLocation getId() {
        return id;
    }

    public String getTitleKey() {
        return titleKey;
    }

    public String getDescriptionKey() {
        return descriptionKey;
    }

    public MovementMood getMovementMood() {
        return movementMood;
    }

    public boolean isLogisticsEligible() {
        return logisticsEligible;
    }

    public boolean hasToolMatcher() {
        return toolMatcher != null;
    }

    public boolean matchesTool(ItemStack stack) {
        return toolMatcher != null && !stack.isEmpty() && toolMatcher.test(stack);
    }

    public boolean needsTool(EntityAutomaton bot) {
        if (needsToolPredicate != null) {
            return needsToolPredicate.test(bot);
        }
        return toolMatcher != null;
    }

    public IAutomatonJob createJob() {
        return Objects.requireNonNull(factory.get(), "Job factory returned null for " + id);
    }

    public static Builder builder(ResourceLocation id, Supplier<IAutomatonJob> factory) {
        return new Builder(id, factory);
    }

    public static final class Builder {

        private final ResourceLocation id;
        private final Supplier<IAutomatonJob> factory;
        private String titleKey;
        private String descriptionKey;
        private MovementMood movementMood = MovementMood.CHILL;
        private boolean logisticsEligible = true;
        @Nullable
        private Predicate<ItemStack> toolMatcher;
        @Nullable
        private Predicate<EntityAutomaton> needsToolPredicate;

        private Builder(ResourceLocation id, Supplier<IAutomatonJob> factory) {
            this.id = Objects.requireNonNull(id, "id");
            this.factory = Objects.requireNonNull(factory, "factory");
            this.titleKey = "automata.job." + id.getNamespace() + "." + id.getPath() + ".name";
            this.descriptionKey = "automata.job." + id.getNamespace() + "." + id.getPath() + ".desc";
        }

        public Builder titleKey(String titleKey) {
            this.titleKey = titleKey;
            return this;
        }

        public Builder descriptionKey(String descriptionKey) {
            this.descriptionKey = descriptionKey;
            return this;
        }

        public Builder movementMood(MovementMood movementMood) {
            this.movementMood = movementMood;
            return this;
        }

        public Builder logisticsEligible(boolean logisticsEligible) {
            this.logisticsEligible = logisticsEligible;
            return this;
        }

        public Builder toolMatcher(@Nullable Predicate<ItemStack> toolMatcher) {
            this.toolMatcher = toolMatcher;
            return this;
        }

        public Builder needsToolPredicate(@Nullable Predicate<EntityAutomaton> needsToolPredicate) {
            this.needsToolPredicate = needsToolPredicate;
            return this;
        }

        public JobDescriptor build() {
            return new JobDescriptor(this);
        }
    }
}
