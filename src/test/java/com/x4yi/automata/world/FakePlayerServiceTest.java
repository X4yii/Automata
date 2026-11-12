package com.x4yi.automata.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.Test;

class FakePlayerServiceTest {

    @Test
    void executeWithGuardBlocksReentrantCallsAndCatchesExceptions() {
        AtomicBoolean innerRan = new AtomicBoolean(false);
        String outer = FakePlayerService.executeWithGuard("outer", "fallback", () -> {
            String inner = FakePlayerService.executeWithGuard("inner", "reentrant-blocked", () -> {
                innerRan.set(true);
                return "ran";
            });
            assertEquals("reentrant-blocked", inner);
            return "ok";
        });
        assertEquals("ok", outer);
        assertFalse(innerRan.get());

        String failed = FakePlayerService.executeWithGuard("failing", "safe-fallback", () -> {
            throw new NullPointerException("simulated null connection");
        });
        assertEquals("safe-fallback", failed);

        assertTrue(FakePlayerService.tryEnterReentrancyGuard());
        FakePlayerService.exitReentrancyGuard();
    }

    @Test
    void resolveProfileUsesOwnerIdentityWhenEnabledAndRespectsNameLimit() {
        UUID owner = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
        GameProfile owned = FakePlayerService.resolveProfile(owner, true);
        assertEquals(owner, owned.getId());
        assertTrue(owned.getName().length() <= 16);
        assertEquals("[Auto:12345678]", owned.getName());

        GameProfile shared = FakePlayerService.resolveProfile(owner, false);
        assertEquals("[Automata]", shared.getName());
    }
}

