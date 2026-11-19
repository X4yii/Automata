package com.x4yi.automata.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.x4yi.automata.AutomataMod;
import com.x4yi.automata.config.AutomataConfig;
import com.x4yi.automata.entity.EntityAutomaton;

@Mod.EventBusSubscriber(modid = AutomataMod.MOD_ID)
public final class SrpCompat {

    public static final String SRP_MOD_ID = "srparasites";
    public static final String AUTOMATON_ENTITY_ID = AutomataMod.MOD_ID + ":automaton";
    public static final String SRP_COTH_IMMUNITY_TAG = "srpcothimmunity";
    private static final String SRP_CONFIG_SYSTEMS_CLASS =
            "com.dhanantry.scapeandrunparasites.util.config.SRPConfigSystems";

    private SrpCompat() {}

    public static boolean isSrpLoaded() {
        return Loader.isModLoaded(SRP_MOD_ID);
    }

    public static void syncConfig() {
        if (!isSrpLoaded()) {
            return;
        }
        syncNativeCothImmuneList(AutomataConfig.cothImmune);
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server != null) {
                for (net.minecraft.world.WorldServer ws : server.worlds) {
                    if (ws == null) {
                        continue;
                    }
                    for (EntityAutomaton bot : ws.getEntities(EntityAutomaton.class, e -> e != null && e.isEntityAlive())) {
                        applyNativeImmunityTag(bot, AutomataConfig.cothImmune);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void applyNativeImmunityTag(EntityLivingBase entity, boolean immune) {
        if (entity == null) {
            return;
        }
        entity.getEntityData().setInteger(SRP_COTH_IMMUNITY_TAG, immune ? 1 : 0);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote || !isSrpLoaded()) {
            return;
        }
        if (event.getEntity() instanceof EntityAutomaton bot) {
            applyNativeImmunityTag(bot, AutomataConfig.cothImmune);
        }
    }

    public static void enforceTickImmunity(EntityAutomaton bot) {
        if (bot == null || bot.world.isRemote || !isSrpLoaded()) {
            return;
        }
        boolean immune = AutomataConfig.cothImmune;
        applyNativeImmunityTag(bot, immune);
        if (!immune) {
            return;
        }
        List<Potion> toRemove = null;
        for (PotionEffect effect : bot.getActivePotionEffects()) {
            ResourceLocation key = effect.getPotion().getRegistryName();
            if (key != null
                    && SRP_MOD_ID.equals(key.getNamespace())
                    && key.getPath().toLowerCase(java.util.Locale.ROOT).contains("coth")) {
                if (toRemove == null) {
                    toRemove = new ArrayList<>();
                }
                toRemove.add(effect.getPotion());
            }
        }
        if (toRemove != null) {
            for (Potion p : toRemove) {
                bot.removePotionEffect(p);
            }
        }
    }

    static boolean syncNativeCothImmuneList(boolean immune) {
        try {
            Class<?> cfgClass = Class.forName(SRP_CONFIG_SYSTEMS_CLASS);
            Field listField = findCothImmuneListField(cfgClass);
            if (listField == null) {
                return false;
            }
            Object raw = listField.get(null);
            boolean inverted = readInvertedFlag(cfgClass);
            if (raw instanceof String[] current) {
                String[] updated = updateCothImmuneList(current, inverted, immune);
                if (updated != current) {
                    listField.set(null, updated);
                }
                AutomataMod.LOGGER.info("Synchronized {} with SRPConfigSystems.{} (immune={})",
                        AUTOMATON_ENTITY_ID, listField.getName(), immune);
                return true;
            }
            if (raw instanceof Collection<?> col) {
                @SuppressWarnings("unchecked")
                Collection<String> mutable = (Collection<String>) col;
                String[] updated = updateCothImmuneList(mutable.toArray(new String[0]), inverted, immune);
                mutable.clear();
                mutable.addAll(Arrays.asList(updated));
                AutomataMod.LOGGER.info("Synchronized {} with SRPConfigSystems.{} collection (immune={})",
                        AUTOMATON_ENTITY_ID, listField.getName(), immune);
                return true;
            }
            return false;
        } catch (Throwable t) {
            AutomataMod.LOGGER.debug("SRPConfigSystems COTH list sync skipped: {}", t.getMessage());
            return false;
        }
    }

    static String[] updateCothImmuneList(String[] current, boolean inverted, boolean immune) {
        boolean shouldBeInList = inverted ? !immune : immune;
        List<String> list = new ArrayList<>(Arrays.asList(current));
        boolean present = false;
        for (String entry : list) {
            if (AUTOMATON_ENTITY_ID.equalsIgnoreCase(entry)) {
                present = true;
                break;
            }
        }
        if (shouldBeInList && !present) {
            list.add(AUTOMATON_ENTITY_ID);
            return list.toArray(new String[0]);
        }
        if (!shouldBeInList && present) {
            list.removeIf(entry -> AUTOMATON_ENTITY_ID.equalsIgnoreCase(entry));
            return list.toArray(new String[0]);
        }
        return current;
    }

    private static Field findCothImmuneListField(Class<?> cfgClass) {
        String[] knownNames = {"cothImmuneMobList", "COTHImmuneMobList", "cothMobImmuneList", "cothImmuneList"};
        for (String name : knownNames) {
            try {
                Field f = cfgClass.getField(name);
                if (f.getType() == String[].class) {
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        for (Field f : cfgClass.getFields()) {
            if (f.getType() != String[].class) {
                continue;
            }
            String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("coth") && lower.contains("immun")) {
                return f;
            }
        }
        return null;
    }

    private static boolean readInvertedFlag(Class<?> cfgClass) {
        for (Field f : cfgClass.getFields()) {
            if (f.getType() != boolean.class) {
                continue;
            }
            String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("coth") && lower.contains("immun") && lower.contains("invert")) {
                try {
                    return f.getBoolean(null);
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return false;
    }

    private static volatile boolean reflectionInit;
    @Nullable
    private static Class<?> parasiteBaseClass;
    @Nullable
    private static Class<?> canFlyClass;
    @Nullable
    private static Class<?> canShootClass;
    @Nullable
    private static Class<?> canPullMobsClass;
    @Nullable
    private static Class<?> srpProjectileClass;
    @Nullable
    private static Class<?> waveClass;
    @Nullable
    private static Class<?> waveShockClass;
    @Nullable
    private static Class<?> customAttackClass;
    @Nullable
    private static Class<?> parasiticScentClass;
    @Nullable
    private static Class<?> iHaveReachClass;
    @Nullable
    private static Method iHaveReachGetReachMethod;
    @Nullable
    private static Class<?> srpWeaponMeleeBaseClass;
    @Nullable
    private static Class<?> srpWeaponRangeBaseClass;
    @Nullable
    private static Class<?> srpWeaponArmorBaseClass;
    @Nullable
    private static Class<?> srpBookOfVengeanceClass;
    @Nullable
    private static Class<?> srpMeleeScytheClass;
    @Nullable
    private static Class<?> srpMeleeCleaverClass;
    @Nullable
    private static Class<?> srpMeleeLanceClass;
    @Nullable
    private static Class<?> srpMeleeMaulClass;
    @Nullable
    private static Class<?> srpMeleeAxeClass;
    @Nullable
    private static Class<?> srpMeleeSwordClass;
    @Nullable
    private static Class<?> srpCanSummonClass;
    @Nullable
    private static Class<?> srpCanSpawnClass;
    @Nullable
    private static Class<?> srpCanMeltClass;
    @Nullable
    private static Class<?> srpBeckonClass;
    @Nullable
    private static Class<?> srpDispatcherClass;
    @Nullable
    private static Class<?> srpStationaryClass;
    @Nullable
    private static Class<?> srpAdaptedClass;
    @Nullable
    private static Class<?> srpPureClass;
    @Nullable
    private static Class<?> srpPreeminentClass;
    @Nullable
    private static Class<?> srpAncientClass;
    @Nullable
    private static Class<?> srpCosmicalClass;
    private static double vengeanceBookRange = 32.0D;
    @Nullable
    private static Object srpEventBusInstance;
    @Nullable
    private static Method playerArmorMethod;
    @Nullable
    private static Potion vengeanceSmokePotion;
    @Nullable
    private static Potion vengeanceBleedPotion;
    @Nullable
    private static Potion vengeanceDebarPotion;
    @Nullable
    private static SoundEvent vengeancePaperSound;
    @Nullable
    private static SoundEvent vengeanceImpactSound;
    @Nullable
    private static SoundEvent vengeanceChainImpactSound;
    @Nullable
    private static SoundEvent vengeanceRockSound;
    @Nullable
    private static Method getSelfeStateMethod;

    private static void ensureReflectionInit() {
        if (reflectionInit) {
            return;
        }
        synchronized (SrpCompat.class) {
            if (reflectionInit) {
                return;
            }
            parasiteBaseClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityParasiteBase");
            canFlyClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityCanFly");
            canShootClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityCanShoot");
            canPullMobsClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityCanPullMobs");
            srpProjectileClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.projectile.EntitySRPProjectile");
            waveClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.monster.EntityWave");
            waveShockClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.monster.EntityWaveShock");
            customAttackClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityCutomAttack");
            parasiticScentClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.EntityParasiticScent");
            iHaveReachClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.IHaveReach");
            if (iHaveReachClass != null) {
                try {
                    iHaveReachGetReachMethod = iHaveReachClass.getMethod("getReach");
                } catch (NoSuchMethodException ignored) {
                }
            }
            srpWeaponMeleeBaseClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.WeaponToolMeleeBase");
            srpWeaponRangeBaseClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.WeaponToolRangeBase");
            srpWeaponArmorBaseClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.WeaponToolArmorBase");
            srpBookOfVengeanceClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.ItemBookOfVengeance");
            srpMeleeScytheClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.WeaponMeleeScythe");
            srpMeleeCleaverClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.WeaponMeleeCleaver");
            srpMeleeLanceClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.WeaponMeleeLance");
            srpMeleeMaulClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.WeaponMeleeMaul");
            srpMeleeAxeClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.WeaponMeleeAxe");
            srpMeleeSwordClass = tryLoadClass("com.dhanantry.scapeandrunparasites.item.tool.WeaponMeleeSword");
            srpCanSummonClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityCanSummon");
            srpCanSpawnClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityCanSpawn");
            srpCanMeltClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityCanMelt");
            srpBeckonClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityPBeckon");
            srpDispatcherClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityPDispatcher");
            srpStationaryClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityPStationary");
            srpAdaptedClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityPAdapted");
            srpPureClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityPPure");
            srpPreeminentClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityPPreeminent");
            srpAncientClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityPAncient");
            srpCosmicalClass = tryLoadClass("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityPCosmical");
            if (srpBookOfVengeanceClass != null) {
                try {
                    Field rangeField = srpBookOfVengeanceClass.getDeclaredField("RANGE");
                    rangeField.setAccessible(true);
                    Object val = rangeField.get(null);
                    if (val instanceof Number num && num.doubleValue() > 0.0D) {
                        vengeanceBookRange = num.doubleValue();
                    }
                } catch (Throwable ignored) {
                }
            }
            Class<?> eventBusClass = tryLoadClass("com.dhanantry.scapeandrunparasites.util.handlers.SRPEventHandlerBus");
            if (eventBusClass != null) {
                try {
                    srpEventBusInstance = eventBusClass.getDeclaredConstructor().newInstance();
                    Method m = eventBusClass.getDeclaredMethod(
                            "playerArmor", EntityPlayer.class, float.class, DamageSource.class);
                    m.setAccessible(true);
                    playerArmorMethod = m;
                } catch (Throwable t) {
                    AutomataMod.LOGGER.debug("SRP playerArmor reflection init skipped: {}", t.getMessage());
                }
            }
            Class<?> srpPotionsClass = tryLoadClass("com.dhanantry.scapeandrunparasites.init.SRPPotions");
            if (srpPotionsClass != null) {
                vengeanceSmokePotion = readStaticField(srpPotionsClass, "DOD_SMOKE_TRAIL_E", Potion.class);
                vengeanceBleedPotion = readStaticField(srpPotionsClass, "BLEED_E", Potion.class);
                vengeanceDebarPotion = readStaticField(srpPotionsClass, "DEBAR_E", Potion.class);
            }
            Class<?> srpSoundsClass = tryLoadClass("com.dhanantry.scapeandrunparasites.init.SRPSounds");
            if (srpSoundsClass != null) {
                vengeancePaperSound = readStaticField(srpSoundsClass, "VENGEANCE_PAPER", SoundEvent.class);
                vengeanceImpactSound = readStaticField(srpSoundsClass, "VENGEANCE_IMPACT", SoundEvent.class);
                vengeanceChainImpactSound = readStaticField(srpSoundsClass, "VENGEANCE_CHAIN_IMPACT", SoundEvent.class);
                vengeanceRockSound = readStaticField(srpSoundsClass, "VENGEANCE_ROCK", SoundEvent.class);
            }
            if (parasiteBaseClass != null) {
                try {
                    getSelfeStateMethod = parasiteBaseClass.getMethod("getSelfeState");
                } catch (NoSuchMethodException ignored) {
                }
            }
            reflectionInit = true;
        }
    }

    @Nullable
    private static <T> T readStaticField(Class<?> owner, String fieldName, Class<T> type) {
        try {
            Field f = owner.getField(fieldName);
            Object val = f.get(null);
            return type.isInstance(val) ? type.cast(val) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Class<?> tryLoadClass(String name) {
        try {
            return Class.forName(name, false, SrpCompat.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isSrpEntity(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        if (parasiteBaseClass != null && parasiteBaseClass.isInstance(entity)) {
            return true;
        }
        ResourceLocation key = EntityList.getKey(entity);
        return key != null && SRP_MOD_ID.equals(key.getNamespace());
    }

    public static boolean isSrpFlying(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        return canFlyClass != null && canFlyClass.isInstance(entity);
    }

    public static boolean isSrpShooter(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        return canShootClass != null && canShootClass.isInstance(entity);
    }

    public static boolean isSrpPuller(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        return canPullMobsClass != null && canPullMobsClass.isInstance(entity);
    }

    public static boolean isSrpProjectile(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        return srpProjectileClass != null && srpProjectileClass.isInstance(entity);
    }

    public static int getSrpSwellState(@Nullable Entity entity) {
        if (entity == null) {
            return 0;
        }
        ensureReflectionInit();
        if (getSelfeStateMethod != null && parasiteBaseClass != null && parasiteBaseClass.isInstance(entity)) {
            try {
                Object val = getSelfeStateMethod.invoke(entity);
                if (val instanceof Integer state) {
                    return state;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    public static boolean isSrpGroundWave(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        if ((waveClass != null && waveClass.isInstance(entity))
                || (waveShockClass != null && waveShockClass.isInstance(entity))) {
            return true;
        }
        ResourceLocation key = EntityList.getKey(entity);
        if (key != null && SRP_MOD_ID.equals(key.getNamespace())) {
            String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
            return "wave".equals(path) || "waveshock".equals(path);
        }
        return false;
    }

    public static boolean isSrpCustomAoeAttacker(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        return customAttackClass != null && customAttackClass.isInstance(entity);
    }

    private static final ConcurrentHashMap<Class<?>, Optional<Method>> ATTACK_TIMER_METHODS = new ConcurrentHashMap<>();

    public static float getSrpAttackTimer(@Nullable Entity entity) {
        if (!isSrpCustomAoeAttacker(entity)) {
            return 0.0F;
        }
        Optional<Method> methodOpt = ATTACK_TIMER_METHODS.computeIfAbsent(entity.getClass(), cls -> {
            try {
                return Optional.of(cls.getMethod("getAttackTimer"));
            } catch (NoSuchMethodException ignored) {
                return Optional.empty();
            }
        });
        if (methodOpt.isPresent()) {
            try {
                Object val = methodOpt.get().invoke(entity);
                if (val instanceof Float f) {
                    return f;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0.0F;
    }

    public static boolean isSrpNonTargetableHazard(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        if (isSrpGroundWave(entity)) {
            return true;
        }
        ensureReflectionInit();
        if (parasiticScentClass != null && parasiticScentClass.isInstance(entity)) {
            return true;
        }
        ResourceLocation key = EntityList.getKey(entity);
        if (key != null && SRP_MOD_ID.equals(key.getNamespace())) {
            String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
            return "scent".equals(path) || "remain".equals(path) || "source".equals(path) || "cloudtoxic".equals(path);
        }
        return false;
    }

    public static double getVengeanceBookRange() {
        ensureReflectionInit();
        return vengeanceBookRange;
    }

    public static boolean isSrpSummonerOrSpawner(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        return (srpCanSummonClass != null && srpCanSummonClass.isInstance(entity))
                || (srpCanSpawnClass != null && srpCanSpawnClass.isInstance(entity));
    }

    public static boolean isSrpMelter(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        return srpCanMeltClass != null && srpCanMeltClass.isInstance(entity);
    }

    public static boolean isSrpNexusStructure(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureReflectionInit();
        return (srpBeckonClass != null && srpBeckonClass.isInstance(entity))
                || (srpDispatcherClass != null && srpDispatcherClass.isInstance(entity))
                || (srpStationaryClass != null && srpStationaryClass.isInstance(entity));
    }

    public static int getSrpEvolutionTier(@Nullable Entity entity) {
        if (entity == null) {
            return 0;
        }
        ensureReflectionInit();
        if ((srpAncientClass != null && srpAncientClass.isInstance(entity))
                || (srpCosmicalClass != null && srpCosmicalClass.isInstance(entity))) {
            return 3;
        }
        if ((srpPureClass != null && srpPureClass.isInstance(entity))
                || (srpPreeminentClass != null && srpPreeminentClass.isInstance(entity))) {
            return 2;
        }
        if (srpAdaptedClass != null && srpAdaptedClass.isInstance(entity)) {
            return 1;
        }
        return 0;
    }

    public static boolean isSrpWeapon(net.minecraft.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (isBookOfVengeance(stack)) {
            return true;
        }
        ensureReflectionInit();
        net.minecraft.item.Item item = stack.getItem();
        if ((srpWeaponMeleeBaseClass != null && srpWeaponMeleeBaseClass.isInstance(item))
                || (srpWeaponRangeBaseClass != null && srpWeaponRangeBaseClass.isInstance(item))) {
            return true;
        }
        ResourceLocation key = item.getRegistryName();
        return key != null
                && SRP_MOD_ID.equals(key.getNamespace())
                && key.getPath().toLowerCase(java.util.Locale.ROOT).startsWith("weapon_");
    }

    public static boolean isBookOfVengeance(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflectionInit();
        if (srpBookOfVengeanceClass != null && srpBookOfVengeanceClass.isInstance(stack.getItem())) {
            return true;
        }
        ResourceLocation key = stack.getItem().getRegistryName();
        if (key == null || !SRP_MOD_ID.equals(key.getNamespace())) {
            return false;
        }
        String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
        return "book_of_vengeance".equals(path) || "the_book_of_vengeance".equals(path);
    }

    public enum SrpWeaponKind {
        NONE,
        SCYTHE,
        LANCE,
        CLEAVER,
        MAUL,
        AXE,
        SWORD,
        BOW,
        BOOK_OF_VENGEANCE
    }

    public static SrpWeaponKind resolveSrpWeaponKind(ItemStack stack) {
        if (stack.isEmpty()) {
            return SrpWeaponKind.NONE;
        }
        if (isBookOfVengeance(stack)) {
            return SrpWeaponKind.BOOK_OF_VENGEANCE;
        }
        ensureReflectionInit();
        net.minecraft.item.Item item = stack.getItem();
        if (srpMeleeScytheClass != null && srpMeleeScytheClass.isInstance(item)) {
            return SrpWeaponKind.SCYTHE;
        }
        if (srpMeleeLanceClass != null && srpMeleeLanceClass.isInstance(item)) {
            return SrpWeaponKind.LANCE;
        }
        if (srpMeleeCleaverClass != null && srpMeleeCleaverClass.isInstance(item)) {
            return SrpWeaponKind.CLEAVER;
        }
        if (srpMeleeMaulClass != null && srpMeleeMaulClass.isInstance(item)) {
            return SrpWeaponKind.MAUL;
        }
        if (srpMeleeAxeClass != null && srpMeleeAxeClass.isInstance(item)) {
            return SrpWeaponKind.AXE;
        }
        if (srpMeleeSwordClass != null && srpMeleeSwordClass.isInstance(item)) {
            return SrpWeaponKind.SWORD;
        }
        if (srpWeaponRangeBaseClass != null && srpWeaponRangeBaseClass.isInstance(item)) {
            return SrpWeaponKind.BOW;
        }
        ResourceLocation key = item.getRegistryName();
        if (key != null && SRP_MOD_ID.equals(key.getNamespace())) {
            return resolveSrpWeaponKindByPath(key.getPath());
        }
        return SrpWeaponKind.NONE;
    }

    public static SrpWeaponKind resolveSrpWeaponKindByPath(@Nullable String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return SrpWeaponKind.NONE;
        }
        String path = rawPath.toLowerCase(java.util.Locale.ROOT);
        if ("book_of_vengeance".equals(path) || "the_book_of_vengeance".equals(path)) {
            return SrpWeaponKind.BOOK_OF_VENGEANCE;
        }
        if (!path.startsWith("weapon_")) {
            return SrpWeaponKind.NONE;
        }
        if (path.contains("scythe")) {
            return SrpWeaponKind.SCYTHE;
        }
        if (path.contains("lance")) {
            return SrpWeaponKind.LANCE;
        }
        if (path.contains("cleaver")) {
            return SrpWeaponKind.CLEAVER;
        }
        if (path.contains("maul")) {
            return SrpWeaponKind.MAUL;
        }
        if (path.contains("axe")) {
            return SrpWeaponKind.AXE;
        }
        if (path.contains("bow")) {
            return SrpWeaponKind.BOW;
        }
        if (path.contains("sword") || path.contains("blade")) {
            return SrpWeaponKind.SWORD;
        }
        return SrpWeaponKind.NONE;
    }

    public static boolean isSrpAdaptiveArmor(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureReflectionInit();
        return srpWeaponArmorBaseClass != null && srpWeaponArmorBaseClass.isInstance(stack.getItem());
    }

    public static boolean hasAnyAdaptiveArmor(EntityAutomaton bot) {
        for (EntityEquipmentSlot slot : new EntityEquipmentSlot[] {
                EntityEquipmentSlot.FEET,
                EntityEquipmentSlot.LEGS,
                EntityEquipmentSlot.CHEST,
                EntityEquipmentSlot.HEAD
        }) {
            if (isSrpAdaptiveArmor(bot.getItemStackFromSlot(slot))) {
                return true;
            }
        }
        return false;
    }

    public static float applyPlayerArmorAdaptation(EntityPlayer fakePlayer, float damage, DamageSource source) {
        if (damage <= 0.0F || fakePlayer == null || source == null) {
            return damage;
        }
        ensureReflectionInit();
        if (srpEventBusInstance == null || playerArmorMethod == null) {
            return damage;
        }
        try {
            Object result = playerArmorMethod.invoke(srpEventBusInstance, fakePlayer, damage, source);
            if (result instanceof Float reduced) {
                return Math.max(0.0F, reduced);
            }
        } catch (Throwable t) {
            AutomataMod.LOGGER.debug("SRP playerArmor adaptation call failed: {}", t.getMessage());
        }
        return damage;
    }

    public static void applyVengeanceDebuffs(EntityLivingBase target) {
        if (target == null || target.isDead) {
            return;
        }
        ensureReflectionInit();
        if (vengeanceSmokePotion != null) {
            target.addPotionEffect(new PotionEffect(vengeanceSmokePotion, 100, 0, false, true));
        }
        if (vengeanceBleedPotion != null) {
            target.addPotionEffect(new PotionEffect(vengeanceBleedPotion, 100, 0, false, true));
        }
        if (vengeanceDebarPotion != null) {
            target.addPotionEffect(new PotionEffect(vengeanceDebarPotion, 100, 0, false, true));
        }
    }

    public static void playVengeanceSound(
            WorldServer world,
            double x,
            double y,
            double z,
            String kind,
            float volume,
            float pitch) {
        ensureReflectionInit();
        SoundEvent event = switch (kind) {
            case "paper" -> vengeancePaperSound;
            case "impact" -> vengeanceImpactSound;
            case "chain" -> vengeanceChainImpactSound;
            case "rock" -> vengeanceRockSound;
            default -> null;
        };
        if (event != null) {
            world.playSound(null, x, y, z, event, SoundCategory.PLAYERS, volume, pitch);
        }
    }

    public static boolean isSrpSentientOrLivingWeapon(net.minecraft.item.ItemStack stack) {
        if (!isSrpWeapon(stack)) {
            return false;
        }
        ResourceLocation key = stack.getItem().getRegistryName();
        if (key == null) {
            return false;
        }
        String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
        return path.contains("sentient") || path.startsWith("weapon_");
    }

    public static double getSrpWeaponReachBonus(net.minecraft.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        ensureReflectionInit();
        net.minecraft.item.Item item = stack.getItem();
        if (iHaveReachClass != null && iHaveReachGetReachMethod != null && iHaveReachClass.isInstance(item)) {
            try {
                Object val = iHaveReachGetReachMethod.invoke(item);
                if (val instanceof Number num) {
                    return Math.max(0.0D, num.doubleValue());
                }
            } catch (Throwable ignored) {
            }
        }
        return 0.0D;
    }
}

