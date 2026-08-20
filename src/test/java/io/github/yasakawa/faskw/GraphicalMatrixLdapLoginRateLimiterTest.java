/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GraphicalMatrixLdapLoginRateLimiterTest {
    @Test
    void replacesTheOldestUnlockedKeyWhenCapacityIsReached() {
        final GraphicalMatrixLdapLoginRateLimiter limiter =
            new GraphicalMatrixLdapLoginRateLimiter(2);
        limiter.recordFailure("user-a", 100L, 1_000L, 3, 1_000L, 1_000L);
        limiter.recordFailure("user-b", 101L, 1_000L, 3, 1_000L, 1_000L);

        assertFalse(limiter.isLimited("user-c", 102L, 1_000L));
        limiter.recordFailure("user-c", 102L, 1_000L, 3, 1_000L, 1_000L);

        assertEquals(2, limiter.size());
        assertFalse(limiter.contains("user-a"));
        assertTrue(limiter.contains("user-b"));
        assertTrue(limiter.contains("user-c"));
    }

    @Test
    void replacesOldestLockedKeyInsteadOfFailingOpenAtCapacity() {
        final GraphicalMatrixLdapLoginRateLimiter limiter =
            new GraphicalMatrixLdapLoginRateLimiter(1);
        limiter.recordFailure("locked", 100L, 1_000L, 1, 1_000L, 1_000L);

        assertTrue(limiter.isLimited("locked", 101L, 1_000L));
        assertFalse(limiter.isLimited("new", 101L, 1_000L));
        limiter.recordFailure("new", 101L, 1_000L, 1, 1_000L, 1_000L);

        assertEquals(1, limiter.size());
        assertFalse(limiter.contains("locked"));
        assertTrue(limiter.contains("new"));
        assertTrue(limiter.isLimited("new", 102L, 1_000L));
    }

    @Test
    void expiredKeysAreRemovedBeforeCapacityIsApplied() {
        final GraphicalMatrixLdapLoginRateLimiter limiter =
            new GraphicalMatrixLdapLoginRateLimiter(1);
        limiter.recordFailure("old", 100L, 50L, 3, 50L, 50L);

        assertFalse(limiter.isLimited("new", 151L, 50L));
        limiter.recordFailure("new", 151L, 50L, 1, 100L, 50L);
        assertTrue(limiter.isLimited("new", 152L, 50L));
        assertEquals(1, limiter.size());
    }
}
