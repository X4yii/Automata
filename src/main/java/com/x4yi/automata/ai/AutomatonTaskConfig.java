
package com.x4yi.automata.ai;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.config.AutomataConfig;

public class AutomatonTaskConfig {

    private ResourceLocation jobId = AutomatonBuiltInJobs.IDLE;

    @Nullable
    private BlockPos pos1;
    @Nullable
    private BlockPos pos2;

    @Nullable
    private BlockPos sourceChest;
    @Nullable
    private BlockPos depositChest;
    @Nullable
    private BlockPos toolChest;
    @Nullable
    private BlockPos foodChest;

    private String squadChannel = "";
    private double combatRadius = 16.0D;
    private boolean whitelistMode = false;
    private final Set<String> entityFilterIds = new HashSet<>();

    private boolean allowParkour = true;
    private boolean allowBreakThrough = true;
    private boolean squadCooperation = true;
    private boolean fillDepressions = true;
    private boolean allowScaffolding = true;
    private boolean canPickUpLoot = true;
    private boolean searchLoot = true;
    private boolean ignoreAnimals = AutomataConfig.ignoreAnimals;
    private boolean ignoreCalmNeutrals = AutomataConfig.ignoreCalmNeutrals;
    private TaskCompletionBehavior completionBehavior = TaskCompletionBehavior.FOLLOW_OWNER;

    public AutomatonTaskConfig() {
        resetEntityFilterToDefaults();
    }

    public void resetEntityFilterToDefaults() {
        entityFilterIds.clear();
        if (AutomataConfig.defaultEntityBlacklist != null) {
            for (String id : AutomataConfig.defaultEntityBlacklist) {
                if (id != null && !id.trim().isEmpty()) {
                    entityFilterIds.add(id.trim());
                }
            }
        }
    }

    public boolean isIgnoreAnimals() {
        return ignoreAnimals;
    }

    public void setIgnoreAnimals(boolean ignoreAnimals) {
        this.ignoreAnimals = ignoreAnimals;
    }

    public boolean isIgnoreCalmNeutrals() {
        return ignoreCalmNeutrals;
    }

    public void setIgnoreCalmNeutrals(boolean ignoreCalmNeutrals) {
        this.ignoreCalmNeutrals = ignoreCalmNeutrals;
    }

    public ResourceLocation getJobId() {
        return jobId;
    }

    public void setJobId(ResourceLocation jobId) {
        this.jobId = jobId == null ? AutomatonBuiltInJobs.IDLE : jobId;
    }

    public AutomatonJobType getJobType() {
        if ("automata".equals(jobId.getNamespace())) {
            return AutomatonJobType.fromId(jobId.getPath());
        }
        return AutomatonJobType.IDLE;
    }

    public void setJobType(AutomatonJobType jobType) {
        AutomatonJobType safe = jobType == null ? AutomatonJobType.IDLE : jobType;
        this.jobId = new ResourceLocation("automata", safe.getId());
    }

    @Nullable
    public BlockPos getPos1() {
        return pos1;
    }

    public void setPos1(@Nullable BlockPos pos1) {
        this.pos1 = pos1 == null ? null : pos1.toImmutable();
    }

    @Nullable
    public BlockPos getPos2() {
        return pos2;
    }

    public void setPos2(@Nullable BlockPos pos2) {
        this.pos2 = pos2 == null ? null : pos2.toImmutable();
    }

    @Nullable
    public BlockPos getSourceChest() {
        return sourceChest;
    }

    public void setSourceChest(@Nullable BlockPos sourceChest) {
        this.sourceChest = sourceChest == null ? null : sourceChest.toImmutable();
    }

    @Nullable
    public BlockPos getDepositChest() {
        return depositChest;
    }

    public void setDepositChest(@Nullable BlockPos depositChest) {
        this.depositChest = depositChest == null ? null : depositChest.toImmutable();
    }

    @Nullable
    public BlockPos getToolChest() {
        return toolChest;
    }

    public void setToolChest(@Nullable BlockPos toolChest) {
        this.toolChest = toolChest == null ? null : toolChest.toImmutable();
    }

    @Nullable
    public BlockPos getFoodChest() {
        return foodChest;
    }

    public void setFoodChest(@Nullable BlockPos foodChest) {
        this.foodChest = foodChest == null ? null : foodChest.toImmutable();
    }

    public String getSquadChannel() {
        return squadChannel;
    }

    public void setSquadChannel(@Nullable String squadChannel) {
        this.squadChannel = squadChannel == null ? "" : squadChannel.trim();
    }

    public double getCombatRadius() {
        return combatRadius;
    }

    public void setCombatRadius(double combatRadius) {
        this.combatRadius = Math.max(4.0D, Math.min(128.0D, combatRadius));
    }

    public boolean isWhitelistMode() {
        return whitelistMode;
    }

    public void setWhitelistMode(boolean whitelistMode) {
        this.whitelistMode = whitelistMode;
    }

    public Set<String> getEntityFilterIds() {
        return entityFilterIds;
    }

    public boolean isAllowParkour() {
        return allowParkour;
    }

    public void setAllowParkour(boolean allowParkour) {
        this.allowParkour = allowParkour;
    }

    public boolean isAllowBreakThrough() {
        return allowBreakThrough;
    }

    public void setAllowBreakThrough(boolean allowBreakThrough) {
        this.allowBreakThrough = allowBreakThrough;
    }

    public boolean isSquadCooperation() {
        return squadCooperation;
    }

    public void setSquadCooperation(boolean squadCooperation) {
        this.squadCooperation = squadCooperation;
    }

    public boolean isFillDepressions() {
        return fillDepressions;
    }

    public void setFillDepressions(boolean fillDepressions) {
        this.fillDepressions = fillDepressions;
    }

    public boolean isAllowScaffolding() {
        return allowScaffolding;
    }

    public void setAllowScaffolding(boolean allowScaffolding) {
        this.allowScaffolding = allowScaffolding;
    }

    public boolean isCanPickUpLoot() {
        return canPickUpLoot;
    }

    public void setCanPickUpLoot(boolean canPickUpLoot) {
        this.canPickUpLoot = canPickUpLoot;
    }

    public boolean isSearchLoot() {
        return searchLoot;
    }

    public void setSearchLoot(boolean searchLoot) {
        this.searchLoot = searchLoot;
    }

    public TaskCompletionBehavior getCompletionBehavior() {
        return completionBehavior;
    }

    public void setCompletionBehavior(@Nullable TaskCompletionBehavior completionBehavior) {
        this.completionBehavior = completionBehavior == null ? TaskCompletionBehavior.FOLLOW_OWNER : completionBehavior;
    }

    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("JobType", jobId.toString());
        writePos(tag, "Pos1", pos1);
        writePos(tag, "Pos2", pos2);
        writePos(tag, "SourceChest", sourceChest);
        writePos(tag, "DepositChest", depositChest);
        writePos(tag, "ToolChest", toolChest);
        writePos(tag, "FoodChest", foodChest);
        tag.setString("SquadChannel", squadChannel);
        tag.setDouble("CombatRadius", combatRadius);
        tag.setBoolean("WhitelistMode", whitelistMode);
        tag.setBoolean("AllowParkour", allowParkour);
        tag.setBoolean("AllowBreakThrough", allowBreakThrough);
        tag.setBoolean("SquadCooperation", squadCooperation);
        tag.setBoolean("FillDepressions", fillDepressions);
        tag.setBoolean("AllowScaffolding", allowScaffolding);
        tag.setBoolean("CanPickUpLoot", canPickUpLoot);
        tag.setBoolean("SearchLoot", searchLoot);
        tag.setBoolean("IgnoreAnimals", ignoreAnimals);
        tag.setBoolean("IgnoreCalmNeutrals", ignoreCalmNeutrals);
        tag.setString("CompletionBehavior", completionBehavior.getId());

        NBTTagList filterList = new NBTTagList();
        for (String id : entityFilterIds) {
            filterList.appendTag(new NBTTagString(id));
        }
        tag.setTag("EntityFilter", filterList);
        return tag;
    }

    public void deserializeNBT(NBTTagCompound tag) {
        if (tag.hasKey("JobType")) {
            String rawJob = tag.getString("JobType");
            if (rawJob.contains(":")) {
                setJobId(new ResourceLocation(rawJob));
            } else {
                setJobType(AutomatonJobType.fromId(rawJob));
            }
        }
        pos1 = readPos(tag, "Pos1");
        pos2 = readPos(tag, "Pos2");
        sourceChest = readPos(tag, "SourceChest");
        depositChest = readPos(tag, "DepositChest");
        toolChest = readPos(tag, "ToolChest");
        foodChest = readPos(tag, "FoodChest");
        if (tag.hasKey("SquadChannel")) {
            setSquadChannel(tag.getString("SquadChannel"));
        }
        if (tag.hasKey("CombatRadius")) {
            setCombatRadius(tag.getDouble("CombatRadius"));
        }
        whitelistMode = tag.getBoolean("WhitelistMode");
        if (tag.hasKey("AllowParkour")) {
            allowParkour = tag.getBoolean("AllowParkour");
        }
        if (tag.hasKey("AllowBreakThrough")) {
            allowBreakThrough = tag.getBoolean("AllowBreakThrough");
        }
        if (tag.hasKey("SquadCooperation")) {
            squadCooperation = tag.getBoolean("SquadCooperation");
        }
        if (tag.hasKey("FillDepressions")) {
            fillDepressions = tag.getBoolean("FillDepressions");
        }
        if (tag.hasKey("AllowScaffolding")) {
            allowScaffolding = tag.getBoolean("AllowScaffolding");
        }
        if (tag.hasKey("CanPickUpLoot")) {
            canPickUpLoot = tag.getBoolean("CanPickUpLoot");
        }
        if (tag.hasKey("SearchLoot")) {
            searchLoot = tag.getBoolean("SearchLoot");
        }
        if (tag.hasKey("IgnoreAnimals")) {
            ignoreAnimals = tag.getBoolean("IgnoreAnimals");
        }
        if (tag.hasKey("IgnoreCalmNeutrals")) {
            ignoreCalmNeutrals = tag.getBoolean("IgnoreCalmNeutrals");
        }
        if (tag.hasKey("CompletionBehavior")) {
            setCompletionBehavior(TaskCompletionBehavior.fromId(tag.getString("CompletionBehavior")));
        }
        if (tag.hasKey("EntityFilter", Constants.NBT.TAG_LIST)) {
            entityFilterIds.clear();
            NBTTagList list = tag.getTagList("EntityFilter", Constants.NBT.TAG_STRING);
            for (int i = 0; i < list.tagCount(); i++) {
                entityFilterIds.add(list.getStringTagAt(i));
            }
        } else {
            resetEntityFilterToDefaults();
        }
    }

    private static void writePos(NBTTagCompound tag, String key, @Nullable BlockPos pos) {
        if (pos != null) {
            tag.setLong(key, pos.toLong());
        }
    }

    @Nullable
    private static BlockPos readPos(NBTTagCompound tag, String key) {
        return tag.hasKey(key, Constants.NBT.TAG_LONG) ? BlockPos.fromLong(tag.getLong(key)) : null;
    }
}

