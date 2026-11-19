package com.x4yi.automata.ai.jobs;

import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityFlying;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.compat.SrpCompat;

public final class SrpTacticalClassifier {

    public enum SrpParasiteArchetype {
        NONE,
        FLYING_SKIRMISHER,
        SUICIDE_BOMBER,
        HOST_SPAWNER_AOE,
        SWARM_RUSHER,
        TRAPPER_PULLER,
        RANGED_ARTILLERY,
        AOE_TOXIC_AURA,
        BLINK_RUSHER,
        NEXUS_STATIC,
        STANDARD_PARASITE
    }

    public record SrpCombatProfile(
            SrpParasiteArchetype archetype,
            double preferredMinRangeSq,
            double preferredMaxRangeSq,
            boolean waitForLandingWhenMelee,
            boolean fleeWhenSwelling,
            boolean dodgeProjectiles,
            boolean prioritizeCritOnLanding,
            boolean useShieldOnCooldown,
            boolean avoidStaticKiting,
            double threatWeightMultiplier) {

        public static final SrpCombatProfile NONE = new SrpCombatProfile(
                SrpParasiteArchetype.NONE, 0.0D, 9.0D, false, false, false, false, false, false, 1.0D);

        public boolean isHighPriorityFocusTarget() {
            return archetype == SrpParasiteArchetype.HOST_SPAWNER_AOE
                    || archetype == SrpParasiteArchetype.RANGED_ARTILLERY
                    || archetype == SrpParasiteArchetype.AOE_TOXIC_AURA
                    || archetype == SrpParasiteArchetype.SUICIDE_BOMBER
                    || archetype == SrpParasiteArchetype.SWARM_RUSHER;
        }
    }

    private static final Set<String> BOMBER_PATHS = Set.of(
            "carrier_heavy", "carrier_light", "carrier_flying", "bomber_light", "bomber_heavy");

    private static final Set<String> HOST_SPAWNER_PATHS = Set.of(
            "host", "hostii", "crux", "crux_incomplete");

    private static final Set<String> SWARM_RUSHER_PATHS = Set.of(
            "mangler", "rupter");

    private static final Set<String> FLYING_SKIRMISHER_PATHS = Set.of(
            "pri_yelloweye", "ada_yelloweye", "overseer", "wraith", "bogle", "succor",
            "architect", "sim_dragone", "sim_dragonehead", "seeker");

    private static final Set<String> TRAPPER_PULLER_PATHS = Set.of(
            "pri_arachnida", "ada_arachnida");

    private static final Set<String> RANGED_ARTILLERY_PATHS = Set.of(
            "pri_summoner", "ada_summoner", "vigilante", "draconite",
            "anc_dreadnaut", "anc_overlord", "anc_dreadnaut_ten", "hi_blaze", "hi_skeleton");

    private static final Set<String> AOE_TOXIC_PATHS = Set.of(
            "pri_reeker", "ada_reeker", "pri_tozoon", "ada_tozoon",
            "pri_bolster", "ada_bolster", "movingflesh");

    private static final Set<String> BLINK_RUSHER_PATHS = Set.of(
            "kirin", "sim_enderman", "sim_endermanhead", "mar_enderman", "fer_enderman",
            "pri_burrower", "ada_burrower", "haunter");

    private static final Set<String> NEXUS_STATIC_PATHS = Set.of(
            "beckon_si", "beckon_sii", "beckon_siii", "beckon_siv",
            "dispatcher_si", "dispatcher_sii", "dispatcher_siii", "dispatcher_siv",
            "dispatcherten", "carrier_colony");

    private static final Set<String> SRP_PROJECTILE_PATHS = Set.of(
            "pullingball", "webball", "spineball", "nadeball", "salivaball",
            "ballball", "ancientball", "homming", "biomassball", "missile",
            "balltall", "ballmall", "salivaeff", "heblu_light", "kirin_slash",
            "gore", "nade", "bomb", "meteor");

    private static final Set<String> SRP_EXPLOSIVE_PATHS = Set.of(
            "orbboom", "nadeball", "nade", "bomb", "meteor", "anc_pod");

    private static final Set<String> SRP_AREA_HAZARD_PATHS = Set.of(
            "cloudtoxic", "remain", "source", "orbvoid", "orbscary", "wave", "waveshock");

    private SrpTacticalClassifier() {}

    public static SrpCombatProfile classify(@Nullable Entity entity) {
        if (entity == null) {
            return SrpCombatProfile.NONE;
        }
        ResourceLocation key = EntityList.getKey(entity);
        boolean isSrp = SrpCompat.isSrpEntity(entity);
        if (!isSrp && (key == null || !SrpCompat.SRP_MOD_ID.equals(key.getNamespace()))) {
            return SrpCombatProfile.NONE;
        }
        boolean flying = (entity instanceof EntityFlying) || SrpCompat.isSrpFlying(entity);
        boolean shooter = SrpCompat.isSrpShooter(entity);
        boolean puller = SrpCompat.isSrpPuller(entity);
        boolean swelling = SrpCompat.getSrpSwellState(entity) > 0;
        return classifyByMetadata(key, flying, shooter, puller, swelling);
    }

    public static SrpCombatProfile classifyByMetadata(
            @Nullable ResourceLocation key,
            boolean isFlying,
            boolean isShooter,
            boolean isPuller,
            boolean isSwelling) {
        String path = key != null ? key.getPath().toLowerCase(Locale.ROOT) : "";
        SrpParasiteArchetype archetype = resolveArchetype(path, isFlying, isShooter, isPuller, isSwelling);
        return profileForArchetype(archetype);
    }

    private static SrpParasiteArchetype resolveArchetype(
            String path,
            boolean isFlying,
            boolean isShooter,
            boolean isPuller,
            boolean isSwelling) {
        if (isSwelling || BOMBER_PATHS.contains(path)) {
            return SrpParasiteArchetype.SUICIDE_BOMBER;
        }
        if (HOST_SPAWNER_PATHS.contains(path)) {
            return SrpParasiteArchetype.HOST_SPAWNER_AOE;
        }
        if (SWARM_RUSHER_PATHS.contains(path)) {
            return SrpParasiteArchetype.SWARM_RUSHER;
        }
        if (NEXUS_STATIC_PATHS.contains(path)) {
            return SrpParasiteArchetype.NEXUS_STATIC;
        }
        if (isPuller || TRAPPER_PULLER_PATHS.contains(path)) {
            return SrpParasiteArchetype.TRAPPER_PULLER;
        }
        if (isFlying || FLYING_SKIRMISHER_PATHS.contains(path)) {
            return SrpParasiteArchetype.FLYING_SKIRMISHER;
        }
        if (BLINK_RUSHER_PATHS.contains(path)) {
            return SrpParasiteArchetype.BLINK_RUSHER;
        }
        if (isShooter || RANGED_ARTILLERY_PATHS.contains(path)) {
            return SrpParasiteArchetype.RANGED_ARTILLERY;
        }
        if (AOE_TOXIC_PATHS.contains(path)) {
            return SrpParasiteArchetype.AOE_TOXIC_AURA;
        }
        return SrpParasiteArchetype.STANDARD_PARASITE;
    }

    public static SrpCombatProfile profileForArchetype(SrpParasiteArchetype archetype) {
        return switch (archetype) {
            case FLYING_SKIRMISHER -> new SrpCombatProfile(
                    archetype, 9.0D, 25.0D, true, false, true, true, true, false, 0.65D);
            case SUICIDE_BOMBER -> new SrpCombatProfile(
                    archetype, 25.0D, 64.0D, false, true, true, false, true, false, 0.25D);
            case HOST_SPAWNER_AOE -> new SrpCombatProfile(
                    archetype, 10.0D, 100.0D, false, false, true, true, true, false, 0.30D);
            case SWARM_RUSHER -> new SrpCombatProfile(
                    archetype, 4.0D, 9.0D, false, false, false, true, true, false, 0.45D);
            case TRAPPER_PULLER -> new SrpCombatProfile(
                    archetype, 6.25D, 16.0D, false, false, true, true, true, false, 0.55D);
            case RANGED_ARTILLERY -> new SrpCombatProfile(
                    archetype, 4.0D, 9.0D, false, false, true, true, true, false, 0.50D);
            case AOE_TOXIC_AURA -> new SrpCombatProfile(
                    archetype, 7.5D, 12.25D, false, false, false, true, false, false, 0.70D);
            case BLINK_RUSHER -> new SrpCombatProfile(
                    archetype, 4.0D, 9.0D, false, false, true, true, true, false, 0.60D);
            case NEXUS_STATIC -> new SrpCombatProfile(
                    archetype, 0.0D, 9.0D, false, false, false, true, false, true, 0.75D);
            case STANDARD_PARASITE -> new SrpCombatProfile(
                    archetype, 4.0D, 9.0D, false, false, false, true, false, false, 0.80D);
            case NONE -> SrpCombatProfile.NONE;
        };
    }

    public static boolean isSrpProjectileId(@Nullable ResourceLocation key) {
        if (key == null || !SrpCompat.SRP_MOD_ID.equals(key.getNamespace())) {
            return false;
        }
        return SRP_PROJECTILE_PATHS.contains(key.getPath().toLowerCase(Locale.ROOT));
    }

    public static boolean isSrpExplosiveId(@Nullable ResourceLocation key) {
        if (key == null || !SrpCompat.SRP_MOD_ID.equals(key.getNamespace())) {
            return false;
        }
        return SRP_EXPLOSIVE_PATHS.contains(key.getPath().toLowerCase(Locale.ROOT));
    }

    public static boolean isSrpAreaHazardId(@Nullable ResourceLocation key) {
        if (key == null || !SrpCompat.SRP_MOD_ID.equals(key.getNamespace())) {
            return false;
        }
        return SRP_AREA_HAZARD_PATHS.contains(key.getPath().toLowerCase(Locale.ROOT));
    }
}

