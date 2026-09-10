
package com.x4yi.automata.entity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.common.base.Optional;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAIWanderAvoidWater;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.ai.EntityMoveHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.ai.AutomatonBrain;
import com.x4yi.automata.api.AutomatonBuiltInJobs;
import com.x4yi.automata.ai.AutomatonTaskConfig;
import com.x4yi.automata.ai.BudgetedTickScheduler;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.gui.AutomatonGuiHandler;
import com.x4yi.automata.inventory.AutomatonInventory;
import com.x4yi.automata.item.ItemAutomaStaff;
import com.x4yi.automata.movement.HumanMovementController;
import com.x4yi.automata.world.AutomatonChunkLoader;
import com.x4yi.automata.world.FakePlayerService;

public class EntityAutomaton extends EntityCreature {

    public static final float MAX_HUNGER = 20.0F;

    public static final byte NEEDED_TOOL_NONE = 0;
    public static final byte NEEDED_TOOL_PICKAXE = 1;
    public static final byte NEEDED_TOOL_AXE = 2;
    public static final byte NEEDED_TOOL_SHOVEL = 3;
    public static final byte NEEDED_TOOL_HOE = 4;
    public static final byte NEEDED_TOOL_SWORD = 5;

    private static final float STANDING_WIDTH = 0.6F;
    private static final float STANDING_HEIGHT = 1.8F;
    private static final float DOWNED_WIDTH = 1.2F;
    private static final float DOWNED_HEIGHT = 0.5F;

    private static final DataParameter<Optional<UUID>> OWNER_UUID =
            EntityDataManager.createKey(EntityAutomaton.class, DataSerializers.OPTIONAL_UNIQUE_ID);
    private static final DataParameter<Boolean> DOWNED =
            EntityDataManager.createKey(EntityAutomaton.class, DataSerializers.BOOLEAN);
    private static final DataParameter<String> SKIN_NAME =
            EntityDataManager.createKey(EntityAutomaton.class, DataSerializers.STRING);
    private static final DataParameter<Boolean> SLIM_MODEL =
            EntityDataManager.createKey(EntityAutomaton.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Float> HUNGER =
            EntityDataManager.createKey(EntityAutomaton.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> SATURATION =
            EntityDataManager.createKey(EntityAutomaton.class, DataSerializers.FLOAT);
    private static final DataParameter<Byte> NEEDED_TOOL =
            EntityDataManager.createKey(EntityAutomaton.class, DataSerializers.BYTE);

    private final AutomatonInventory inventory = new AutomatonInventory();
    private final AutomatonChunkLoader chunkLoader = new AutomatonChunkLoader(this);
    private final HumanMovementController movementController = new HumanMovementController(this);
    private final AutomatonTaskConfig taskConfig = new AutomatonTaskConfig();
    private final AutomatonBrain brain = new AutomatonBrain(this);

    private boolean forceTerminating;
    private int hungerTickCounter;
    private int ticksSinceLastAttack = 20;
    private int eatingTicksRemaining;
    private ItemStack eatingItemStack = ItemStack.EMPTY;
    private ItemStack savedMainhandBeforeEat = ItemStack.EMPTY;

    public EntityAutomaton(World worldIn) {
        super(worldIn);
        updatePoseHitbox(false);
        setCanPickUpLoot(true);
        this.moveHelper = new EntityMoveHelper(this) {
            @Override
            public void onUpdateMoveHelper() {
                if (action == Action.WAIT && (movementController.hasActivePath() || movementController.isDirectControlActive())) {
                    return;
                }
                super.onUpdateMoveHelper();
            }
        };
    }

    @Override
    public float getEyeHeight() {
        if (isDowned()) {
            return 0.35F;
        }
        return isSneaking() ? 1.54F : 1.62F;
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        dataManager.register(OWNER_UUID, Optional.absent());
        dataManager.register(DOWNED, false);
        dataManager.register(SKIN_NAME, "");
        dataManager.register(SLIM_MODEL, rand.nextBoolean());
        dataManager.register(HUNGER, MAX_HUNGER);
        dataManager.register(SATURATION, 5.0F);
        dataManager.register(NEEDED_TOOL, NEEDED_TOOL_NONE);
    }

    @Override
    public void notifyDataManagerChange(DataParameter<?> key) {
        super.notifyDataManagerChange(key);
        if (DOWNED.equals(key)) {
            updatePoseHitbox(isDowned());
        }
    }

    public void updatePoseHitbox(boolean downed) {
        if (downed) {
            setSize(DOWNED_WIDTH, DOWNED_HEIGHT);
        } else {
            setSize(STANDING_WIDTH, STANDING_HEIGHT);
        }
    }

    public byte getNeededTool() {
        return dataManager.get(NEEDED_TOOL);
    }

    public void setNeededTool(byte toolType) {
        dataManager.set(NEEDED_TOOL, toolType);
    }

    public void clearNeededTool() {
        if (getNeededTool() != NEEDED_TOOL_NONE) {
            dataManager.set(NEEDED_TOOL, NEEDED_TOOL_NONE);
        }
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(20.0D);
        getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.10D);
        getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(32.0D);
        getAttributeMap().registerAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(2.0D);
    }

    @Override
    protected void initEntityAI() {
        tasks.addTask(0, new EntityAISwimming(this) {
            @Override
            public boolean shouldExecute() {
                if (brain != null && brain.getContext().getMiningController().isMining() && getAir() >= 60) {
                    return false;
                }
                return super.shouldExecute();
            }

            @Override
            public boolean shouldContinueExecuting() {
                if (brain != null && brain.getContext().getMiningController().isMining() && getAir() >= 60) {
                    return false;
                }
                return super.shouldContinueExecuting();
            }
        });
        tasks.addTask(5, new EntityAIWanderAvoidWater(this, 0.8D) {
            @Override
            public boolean shouldExecute() {
                return getOwnerUuid() == null && super.shouldExecute();
            }
        });
        tasks.addTask(6, new EntityAIWatchClosest(this, EntityPlayer.class, 8.0F) {
            @Override
            public boolean shouldExecute() {
                return canIdleLook() && super.shouldExecute();
            }

            @Override
            public boolean shouldContinueExecuting() {
                return canIdleLook() && super.shouldContinueExecuting();
            }
        });
        tasks.addTask(7, new EntityAILookIdle(this) {
            @Override
            public boolean shouldExecute() {
                return canIdleLook() && super.shouldExecute();
            }

            @Override
            public boolean shouldContinueExecuting() {
                return canIdleLook() && super.shouldContinueExecuting();
            }
        });
    }

    private boolean canIdleLook() {
        return !isDowned()
                && AutomatonBuiltInJobs.IDLE.equals(taskConfig.getJobId())
                && !getMovementController().hasActivePath()
                && !brain.getContext().getMiningController().isMining();
    }

    @Nullable
    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
        dataManager.set(SLIM_MODEL, rand.nextBoolean());
        return super.onInitialSpawn(difficulty, livingdata);
    }

    @Override
    protected boolean canDespawn() {
        return false;
    }

    @Override
    public boolean canBeLeashedTo(EntityPlayer player) {
        return false;
    }

    @Nullable
    public UUID getOwnerUuid() {
        return dataManager.get(OWNER_UUID).orNull();
    }

    public void setOwnerUuid(@Nullable UUID ownerUuid) {
        dataManager.set(OWNER_UUID, Optional.fromNullable(ownerUuid));
    }

    public boolean isOwnedBy(EntityPlayer player) {
        UUID owner = getOwnerUuid();
        return owner != null && owner.equals(player.getUniqueID());
    }

    public boolean isDowned() {
        return dataManager.get(DOWNED);
    }

    public String getSkinName() {
        return dataManager.get(SKIN_NAME);
    }

    public void setSkinName(@Nullable String skinName) {
        dataManager.set(SKIN_NAME, skinName == null ? "" : skinName.trim());
    }

    public static String normalizeAutomatonName(@Nullable String rawName) {
        if (rawName == null) {
            return "";
        }
        String trimmed = rawName.trim();
        if (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.length() > 32) {
            return trimmed.substring(0, 32);
        }
        return trimmed;
    }

    public void setAutomatonName(@Nullable String rawName) {
        String normalized = normalizeAutomatonName(rawName);
        setCustomNameTag(normalized);
        setAlwaysRenderNameTag(!normalized.isEmpty());
    }

    public boolean isSlimModel() {
        return dataManager.get(SLIM_MODEL);
    }

    public void setSlimModel(boolean slim) {
        dataManager.set(SLIM_MODEL, slim);
    }

    public float getHunger() {
        return dataManager.get(HUNGER);
    }

    public void setHunger(float hunger) {
        dataManager.set(HUNGER, Math.max(0.0F, Math.min(MAX_HUNGER, hunger)));
    }

    public float getSaturation() {
        return dataManager.get(SATURATION);
    }

    public void setSaturation(float saturation) {
        dataManager.set(SATURATION, Math.max(0.0F, Math.min(getHunger(), saturation)));
    }

    public AutomatonInventory getInventory() {
        return inventory;
    }

    public AutomatonChunkLoader getChunkLoader() {
        return chunkLoader;
    }

    public HumanMovementController getMovementController() {
        return movementController;
    }

    public AutomatonTaskConfig getTaskConfig() {
        return taskConfig;
    }

    public AutomatonBrain getBrain() {
        return brain;
    }

    public int getTicksSinceLastAttack() {
        return ticksSinceLastAttack;
    }

    public void resetAttackCooldown() {
        ticksSinceLastAttack = 0;
    }

    public boolean isAttackReady() {
        return ticksSinceLastAttack >= Math.ceil(getAttackCooldownPeriodTicks());
    }

    public double getAttackCooldownPeriodTicks() {
        double baseSpeed = 4.0D;
        ItemStack held = getHeldItemMainhand();
        if (!held.isEmpty()) {
            Collection<AttributeModifier> modifiers = held.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                    .get(SharedMonsterAttributes.ATTACK_SPEED.getName());
            double additive = 0.0D;
            double multiplyBase = 0.0D;
            double multiplyTotal = 1.0D;
            for (AttributeModifier modifier : modifiers) {
                if (modifier.getOperation() == 0) {
                    additive += modifier.getAmount();
                } else if (modifier.getOperation() == 1) {
                    multiplyBase += modifier.getAmount();
                } else if (modifier.getOperation() == 2) {
                    multiplyTotal *= 1.0D + modifier.getAmount();
                }
            }
            baseSpeed = (baseSpeed + additive) * (1.0D + multiplyBase) * multiplyTotal;
        }
        return computeAttackCooldownTicks(baseSpeed);
    }

    public static double computeAttackCooldownTicks(double attackSpeed) {
        double clamped = Math.max(0.1D, attackSpeed);
        return 20.0D / clamped;
    }

    public static int computeEatDurationTicks(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        int duration = stack.getMaxItemUseDuration();
        return duration > 0 ? duration : 32;
    }

    public boolean isEating() {
        return eatingTicksRemaining > 0 && !eatingItemStack.isEmpty();
    }

    public float getHealthRatio() {
        return getHealth() / Math.max(1.0F, getMaxHealth());
    }

    @Override
    protected void onItemUseFinish() {
        if (eatingTicksRemaining > 0) {
            return;
        }
        super.onItemUseFinish();
    }

    public static int countActiveBotsForPlayer(World world, UUID playerUuid) {
        int count = 0;
        for (EntityAutomaton bot : world.getEntities(EntityAutomaton.class, e -> !e.isDead)) {
            if (playerUuid.equals(bot.getOwnerUuid())) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected void updateAITasks() {
        super.updateAITasks();
        if (!world.isRemote && !isDowned()) {
            brain.tickServer();
        }
    }

    @Override
    public void onLivingUpdate() {
        updateArmSwingProgress();
        if (isDowned()) {
            motionX = 0.0D;
            motionZ = 0.0D;
            setSprinting(false);
            getNavigator().clearPath();
            setAttackTarget(null);
        }
        super.onLivingUpdate();

        if (!world.isRemote) {
            if (ticksSinceLastAttack < 200) {
                ticksSinceLastAttack++;
            }
            if (!isDowned()) {
                if (isEntityAlive() && BudgetedTickScheduler.shouldRun(world.getTotalWorldTime(), getEntityId(), 4)) {
                    pickupNearbyItems();
                }
                if (world instanceof WorldServer worldServer) {
                    FakePlayerService.tickEquippedItemEffects(worldServer, this);
                }
                tickHungerAndHealing();
                if (getOwnerUuid() != null) {
                    chunkLoader.update();
                }
            } else {
                cancelEating();
                brain.getContext().stopNavigation();
                brain.getContext().resetMining();
                if (getOwnerUuid() != null) {
                    brain.getContext().getBlackboard().reportDowned(getEntityId(), getPosition(), world.getTotalWorldTime());
                }
                chunkLoader.releaseAll();
            }
        }
    }

    private void tickHungerAndHealing() {
        hungerTickCounter++;

        int drainInterval = isSprinting() ? 40 : 200;
        if (hungerTickCounter % drainInterval == 0) {
            float sat = getSaturation();
            if (sat > 0.0F) {
                setSaturation(sat - 0.5F);
            } else if (getHunger() > 0.0F) {
                setHunger(getHunger() - 0.5F);
            }
        }

        if (getHunger() <= 6.0F && isSprinting()) {
            setSprinting(false);
        }

        if (isEating() && world instanceof WorldServer worldServer) {
            tickActiveEating(worldServer);
        } else {
            boolean shouldEat = getHunger() <= 14.0F || (getHealth() < getMaxHealth() && getHunger() < 18.0F);
            if (shouldEat && hungerTickCounter % 20 == 0 && world instanceof WorldServer) {
                ItemStack consumed = inventory.consumeOneFood();
                if (!consumed.isEmpty()) {
                    startEating(consumed);
                }
            }
        }

        if (getHealth() < getMaxHealth()) {
            boolean fastSaturationHeal = getHunger() >= MAX_HUNGER && getSaturation() > 0.0F;
            int healInterval = fastSaturationHeal ? 10 : 80;
            if ((fastSaturationHeal || getHunger() >= 18.0F) && hungerTickCounter % healInterval == 0) {
                heal(1.0F);
                if (getSaturation() > 0.0F) {
                    setSaturation(getSaturation() - 1.0F);
                } else {
                    setHunger(getHunger() - 1.0F);
                }
            }
        }
    }

    private void startEating(ItemStack foodStack) {
        savedMainhandBeforeEat = getHeldItemMainhand().copy();
        eatingItemStack = foodStack;
        eatingTicksRemaining = computeEatDurationTicks(foodStack);
        setHeldItem(EnumHand.MAIN_HAND, foodStack.copy());
        setActiveHand(EnumHand.MAIN_HAND);
    }

    private void tickActiveEating(WorldServer worldServer) {
        if (!isHandActive()) {
            setActiveHand(EnumHand.MAIN_HAND);
        }
        if (eatingTicksRemaining % 4 == 0) {
            playSound(SoundEvents.ENTITY_GENERIC_EAT, 0.5F, 0.9F + rand.nextFloat() * 0.2F);
        }
        eatingTicksRemaining--;
        if (eatingTicksRemaining > 0) {
            return;
        }
        resetActiveHand();
        ItemStack leftover = FakePlayerService.consumeFoodLikePlayer(worldServer, this, eatingItemStack);
        setHeldItem(EnumHand.MAIN_HAND, savedMainhandBeforeEat);
        savedMainhandBeforeEat = ItemStack.EMPTY;
        eatingItemStack = ItemStack.EMPTY;
        if (!leftover.isEmpty()) {
            ItemStack rem = ItemHandlerHelper.insertItemStacked(inventory.getCombinedInventory(), leftover, false);
            if (!rem.isEmpty()) {
                entityDropItem(rem, 0.0F);
            }
        }
        playSound(SoundEvents.ENTITY_PLAYER_BURP, 0.5F, 0.9F + rand.nextFloat() * 0.1F);
    }

    private void cancelEating() {
        if (!isEating()) {
            return;
        }
        resetActiveHand();
        setHeldItem(EnumHand.MAIN_HAND, savedMainhandBeforeEat);
        ItemStack rem = ItemHandlerHelper.insertItemStacked(inventory.getCombinedInventory(), eatingItemStack, false);
        if (!rem.isEmpty()) {
            entityDropItem(rem, 0.0F);
        }
        savedMainhandBeforeEat = ItemStack.EMPTY;
        eatingItemStack = ItemStack.EMPTY;
        eatingTicksRemaining = 0;
    }

    @Override
    protected boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (hand != EnumHand.MAIN_HAND) {
            return false;
        }
        ItemStack held = player.getHeldItem(hand);
        if (held.getItem() instanceof ItemAutomaStaff) {
            return false;
        }
        if (world.isRemote) {
            return true;
        }

        if (isDowned()) {
            if (held.getItem() instanceof ItemFood food) {
                if (!player.capabilities.isCreativeMode) {
                    held.shrink(1);
                }
                setHunger(getHunger() + food.getHealAmount(held));
                revive(0.5F);
                return true;
            }
            if (held.isEmpty()) {
                revive(0.3F);
                return true;
            }
            return false;
        }

        if (getOwnerUuid() == null && held.getItem() instanceof ItemFood food) {
            int limit = AutomataConfig.maxBotsPerPlayer;
            if (limit > 0 && countActiveBotsForPlayer(world, player.getUniqueID()) >= limit) {
                player.sendStatusMessage(new TextComponentTranslation("message.automata.limit_reached", limit), true);
                return false;
            }
            if (!player.capabilities.isCreativeMode) {
                held.shrink(1);
            }
            setOwnerUuid(player.getUniqueID());
            setHunger(getHunger() + food.getHealAmount(held));
            heal(food.getHealAmount(held));
            world.setEntityState(this, (byte) 7);
            return true;
        }

        if ((isOwnedBy(player) || player.canUseCommand(2, "")) && player instanceof EntityPlayerMP) {
            if (getOwnerUuid() == null) {
                setOwnerUuid(player.getUniqueID());
            }
            player.openGui(AutomataMod.instance, AutomatonGuiHandler.GUI_AUTOMATON, world, getEntityId(), 0, 0);
            return true;
        }

        return super.processInteract(player, hand);
    }

    @Override
    public void handleStatusUpdate(byte id) {
        if (id == 7) {
            for (int i = 0; i < 7; i++) {
                double dx = rand.nextGaussian() * 0.02D;
                double dy = rand.nextGaussian() * 0.02D;
                double dz = rand.nextGaussian() * 0.02D;
                world.spawnParticle(
                        EnumParticleTypes.HEART,
                        posX + rand.nextFloat() * width * 2.0F - width,
                        posY + 0.5D + rand.nextFloat() * height,
                        posZ + rand.nextFloat() * width * 2.0F - width,
                        dx, dy, dz);
            }
            return;
        }
        super.handleStatusUpdate(id);
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (forceTerminating) {
            return super.attackEntityFrom(source, amount);
        }
        if (source.getTrueSource() instanceof net.minecraftforge.common.util.FakePlayer fake
                && FakePlayerService.isAutomataFakePlayer(fake)) {
            return false;
        }
        if (isDowned()) {
            return false;
        }
        if (!world.isRemote && getHealth() - amount <= 0.0F) {
            enterDownedState();
            return true;
        }
        return super.attackEntityFrom(source, amount);
    }

    public void enterDownedState() {
        dataManager.set(DOWNED, true);
        updatePoseHitbox(true);
        clearNeededTool();
        setHealth(1.0F);
        extinguish();
        setSprinting(false);
        getNavigator().clearPath();
        setAttackTarget(null);
        setRevengeTarget(null);
        chunkLoader.releaseAll();
        if (!world.isRemote) {
            List<net.minecraft.entity.EntityLiving> attackers = world.getEntitiesWithinAABB(
                    net.minecraft.entity.EntityLiving.class,
                    getEntityBoundingBox().grow(32.0D));
            for (net.minecraft.entity.EntityLiving mob : attackers) {
                if (mob.getAttackTarget() == this) {
                    mob.setAttackTarget(null);
                }
                if (mob.getRevengeTarget() == this) {
                    mob.setRevengeTarget(null);
                }
            }
        }
    }

    public void revive(float healthFraction) {
        if (!isDowned()) {
            return;
        }
        dataManager.set(DOWNED, false);
        updatePoseHitbox(false);
        float targetHealth = Math.max(2.0F, getMaxHealth() * Math.max(0.1F, Math.min(1.0F, healthFraction)));
        setHealth(targetHealth);
        if (!world.isRemote) {
            brain.getContext().getBlackboard().clearDowned(getEntityId());
        }
    }

    public void forceTerminate(DamageSource source) {
        forceTerminating = true;
        try {
            dataManager.set(DOWNED, false);
            attackEntityFrom(source, Float.MAX_VALUE);
            if (!isDead) {
                setHealth(0.0F);
                onDeath(source);
            }
        } finally {
            forceTerminating = false;
        }
    }

    @Override
    public void onDeath(DamageSource cause) {
        if (!forceTerminating) {
            if (!world.isRemote) {
                enterDownedState();
            }
            return;
        }
        if (!world.isRemote) {
            chunkLoader.releaseAll();
            dropInventoryContents();
        }
        super.onDeath(cause);
    }

    private void dropInventoryContents() {
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            ItemStack equipped = getItemStackFromSlot(slot);
            if (!equipped.isEmpty()) {
                entityDropItem(equipped.copy(), 0.0F);
                setItemStackToSlot(slot, ItemStack.EMPTY);
            }
        }
        IItemHandlerModifiable main = inventory.getMainInventory();
        for (int i = 0; i < main.getSlots(); i++) {
            ItemStack stack = main.getStackInSlot(i);
            if (!stack.isEmpty()) {
                entityDropItem(stack, 0.0F);
                main.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
        ItemStack backpack = inventory.getEquippedBackpack();
        if (!backpack.isEmpty()) {
            entityDropItem(backpack, 0.0F);
            inventory.getBackpackSlot().setStackInSlot(0, ItemStack.EMPTY);
        }
    }

    @Override
    public void setDead() {
        cleanupServerState();
        super.setDead();
    }

    @Override
    public void onRemovedFromWorld() {
        cleanupServerState();
        super.onRemovedFromWorld();
    }

    private void cleanupServerState() {
        if (!world.isRemote) {
            brain.getContext().cancelPendingPath();
            brain.getContext().resetMining();
            brain.getContext().getBlackboard().clearDowned(getEntityId());
            brain.getContext().getBlackboard().clearReviveInProgress(getEntityId());
            com.x4yi.automata.ai.jobs.CombatJobSupport.clearState(getEntityId());
            chunkLoader.releaseAll();
        }
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(inventory.getCombinedInventory());
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        UUID owner = getOwnerUuid();
        if (owner != null) {
            compound.setUniqueId("OwnerUUID", owner);
        }
        compound.setBoolean("Downed", isDowned());
        compound.setString("SkinName", getSkinName());
        compound.setBoolean("SlimModel", isSlimModel());
        compound.setFloat("Hunger", getHunger());
        compound.setFloat("Saturation", getSaturation());
        compound.setTag("AutomatonInv", inventory.serializeNBT());
        compound.setTag("TaskConfig", taskConfig.serializeNBT());
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        if (compound.hasUniqueId("OwnerUUID")) {
            setOwnerUuid(compound.getUniqueId("OwnerUUID"));
        } else {
            setOwnerUuid(null);
        }
        dataManager.set(DOWNED, compound.getBoolean("Downed"));
        updatePoseHitbox(isDowned());
        setSkinName(compound.getString("SkinName"));
        if (compound.hasKey("SlimModel")) {
            setSlimModel(compound.getBoolean("SlimModel"));
        }
        if (compound.hasKey("Hunger")) {
            setHunger(compound.getFloat("Hunger"));
        }
        if (compound.hasKey("Saturation")) {
            setSaturation(compound.getFloat("Saturation"));
        }
        if (compound.hasKey("AutomatonInv")) {
            inventory.deserializeNBT(compound.getCompoundTag("AutomatonInv"));
        }
        if (compound.hasKey("TaskConfig")) {
            taskConfig.deserializeNBT(compound.getCompoundTag("TaskConfig"));
        }
        getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.10D);
    }

    @Override
    protected void updateEquipmentIfNeeded(EntityItem itemEntity) {
        tryPickupItem(itemEntity);
    }

    private void pickupNearbyItems() {
        List<EntityItem> nearbyItems = world.getEntitiesWithinAABB(
                EntityItem.class,
                getEntityBoundingBox().grow(1.25D, 0.5D, 1.25D));
        for (EntityItem itemEntity : nearbyItems) {
            if (!itemEntity.isDead && !itemEntity.cannotPickup()) {
                tryPickupItem(itemEntity);
            }
        }
    }

    public boolean tryPickupItem(EntityItem itemEntity) {
        if (itemEntity == null || itemEntity.isDead || world.isRemote || !taskConfig.isCanPickUpLoot()) {
            return false;
        }
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return false;
        }
        int originalCount = stack.getCount();
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory.getCombinedInventory(), stack.copy(), false);
        int pickedCount = originalCount - remainder.getCount();
        if (pickedCount <= 0) {
            return false;
        }
        if (remainder.isEmpty()) {
            itemEntity.setDead();
        } else {
            itemEntity.setItem(remainder);
        }
        onItemPickup(itemEntity, pickedCount);
        playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, ((rand.nextFloat() - rand.nextFloat()) * 0.7F + 1.0F) * 2.0F);
        return true;
    }
}

