/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixLockoutConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void usesTwoStageDefaults() throws Exception {
        writeProperties("");

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertEquals(5, config.getLockoutFailureLimit());
        assertEquals(900, config.getLockoutLockSeconds());
        assertEquals(900000L, config.getLockoutLockMillis());
        assertEquals(10, config.getLockoutMaxLockFailureCount());
        assertEquals(2592000, config.getLockoutMaxLockSeconds());
        assertEquals(2592000000L, config.getLockoutMaxLockMillis());
    }

    @Test
    void loadsCustomTwoStageSettings() throws Exception {
        writeProperties("""
            graphicalmatrix.lockout.failureLimit = 3
            graphicalmatrix.lockout.lockSeconds = 120
            graphicalmatrix.lockout.maxLockFailureCount = 6
            graphicalmatrix.lockout.maxLockSeconds = 3600
            """);

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertEquals(3, config.getLockoutFailureLimit());
        assertEquals(120, config.getLockoutLockSeconds());
        assertEquals(6, config.getLockoutMaxLockFailureCount());
        assertEquals(3600, config.getLockoutMaxLockSeconds());
    }

    @Test
    void policySelectsNoNormalAndMaximumLock() {
        final GraphicalMatrixLockoutPolicy policy =
            new GraphicalMatrixLockoutPolicy(5, 900000L, 10, 2592000000L);

        assertDecision(policy.afterFailure(4),
            GraphicalMatrixLockoutPolicy.Level.NONE, 0L, false);
        assertDecision(policy.afterFailure(5),
            GraphicalMatrixLockoutPolicy.Level.NORMAL, 900000L, true);
        assertDecision(policy.afterFailure(9),
            GraphicalMatrixLockoutPolicy.Level.NORMAL, 900000L, true);
        assertDecision(policy.afterFailure(10),
            GraphicalMatrixLockoutPolicy.Level.MAXIMUM, 2592000000L, true);
        assertDecision(policy.afterFailure(11),
            GraphicalMatrixLockoutPolicy.Level.MAXIMUM, 2592000000L, true);
        assertDecision(policy.afterFailure(Integer.MAX_VALUE),
            GraphicalMatrixLockoutPolicy.Level.MAXIMUM, 2592000000L, true);
    }

    @Test
    void rejectsInvalidLockoutSettings() throws Exception {
        assertInvalid("graphicalmatrix.lockout.failureLimit = 0\n");
        assertInvalid("graphicalmatrix.lockout.failureLimit = 101\n"
            + "graphicalmatrix.lockout.maxLockFailureCount = 102\n");
        assertInvalid("graphicalmatrix.lockout.lockSeconds = 0\n");
        assertInvalid("graphicalmatrix.lockout.lockSeconds = 2592001\n"
            + "graphicalmatrix.lockout.maxLockSeconds = 2592001\n");
        assertInvalid("graphicalmatrix.lockout.maxLockFailureCount = 5\n");
        assertInvalid("graphicalmatrix.lockout.maxLockFailureCount = 1001\n");
        assertInvalid("graphicalmatrix.lockout.lockSeconds = 901\n"
            + "graphicalmatrix.lockout.maxLockSeconds = 900\n");
        assertInvalid("graphicalmatrix.lockout.maxLockSeconds = 0\n");
        assertInvalid("graphicalmatrix.lockout.maxLockSeconds = 2592001\n");
        assertInvalid("graphicalmatrix.lockout.failureLimit = five\n");
    }

    @Test
    void lockedResultRetainsStoredDeadline() {
        final GraphicalMatrixVerifyResult result =
            GraphicalMatrixVerifyResult.locked("locked_until=12345", 12345L);

        assertEquals("LOCKED", result.getAuditResult());
        assertEquals(12345L, result.getLockedUntil());
    }

    private static void assertDecision(
            final GraphicalMatrixLockoutPolicy.LockDecision decision,
            final GraphicalMatrixLockoutPolicy.Level level,
            final long lockMillis, final boolean locked) {
        assertEquals(level, decision.getLevel());
        assertEquals(lockMillis, decision.getLockMillis());
        assertEquals(lockMillis / 1000L, decision.getLockSeconds());
        if (locked) {
            assertTrue(decision.isLocked());
        } else {
            assertFalse(decision.isLocked());
        }
    }

    private void assertInvalid(final String properties) throws Exception {
        writeProperties(properties);
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixConfig.load(idpHome.toString()));
    }

    private void writeProperties(final String value) throws Exception {
        final Path directory = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("graphicalmatrix.properties"), value);
    }
}
