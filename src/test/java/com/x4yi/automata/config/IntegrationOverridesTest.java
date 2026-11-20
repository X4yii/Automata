package com.x4yi.automata.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.util.ResourceLocation;

import com.x4yi.automata.ai.DecisionTrace;
import com.x4yi.automata.compat.ModCompatBridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationOverridesTest {

    @Test
    void parsesWeaponAndEntityOverridesWithWildcardFallback() {
        String weaponJson = "{\n"
                + "  \"weapons\": {\n"
                + "    \"testmod:greatsword\": {\"useMode\": \"melee\", \"twoHanded\": true, \"reachBonus\": 1.5},\n"
                + "    \"testmod:*\": {\"useMode\": \"ranged\", \"twoHanded\": false}\n"
                + "  }\n"
                + "}";
        String entityJson = "{\n"
                + "  \"entities\": {\n"
                + "    \"testmod:bomber\": {\"preferredMinRange\": 6.0, \"preferredMaxRange\": 12.0, \"fleeWhenSwelling\": true, \"avoidMeleeRush\": true, \"threatWeightMultiplier\": 1.8}\n"
                + "  }\n"
                + "}";

        IntegrationOverrides.loadFromJson(weaponJson, entityJson);

        IntegrationOverrides.WeaponOverride exact =
                IntegrationOverrides.getWeaponOverride(new ResourceLocation("testmod:greatsword"));
        assertNotNull(exact);
        assertTrue(exact.twoHanded());
        assertEquals(1.5D, exact.reachBonus());

        IntegrationOverrides.WeaponOverride wild =
                IntegrationOverrides.getWeaponOverride(new ResourceLocation("testmod:other_bow"));
        assertNotNull(wild);
        assertEquals("ranged", wild.useMode());

        assertNull(IntegrationOverrides.getWeaponOverride(new ResourceLocation("other:sword")));

        IntegrationOverrides.EntityTacticOverride ent =
                IntegrationOverrides.getEntityOverride(new ResourceLocation("testmod:bomber"));
        assertNotNull(ent);
        assertTrue(ent.fleeWhenSwelling());
        assertTrue(ent.avoidMeleeRush());
        assertEquals(1.8D, ent.threatWeightMultiplier());

        IntegrationOverrides.loadFromJson("{\"weapons\":{}}", "{\"entities\":{}}");
    }

    @Test
    void decisionTraceDeduplicatesWithinWindowAndCapsSize() {
        DecisionTrace trace = new DecisionTrace(4);
        trace.record(10L, "JOB", "Guard");
        trace.record(12L, "JOB", "Guard");
        trace.record(25L, "JOB", "Guard");
        trace.record(30L, "OFFHAND", "TORCH");
        trace.record(40L, "OFFHAND", "SHIELD");
        trace.record(50L, "CLAIM", "Denied");

        List<String> lines = trace.snapshotRecent(10);
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("[25] JOB: Guard"));
        assertTrue(lines.get(3).contains("[50] CLAIM: Denied"));
    }

    @Test
    void modCompatBridgeDefaultsWithoutSrp() {
        assertEquals(ModCompatBridge.TacticalDirective.DEFAULT, ModCompatBridge.resolveTacticalDirective(null));
    }
}
